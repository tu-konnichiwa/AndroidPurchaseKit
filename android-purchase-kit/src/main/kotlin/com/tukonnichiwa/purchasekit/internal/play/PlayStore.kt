package com.tukonnichiwa.purchasekit.internal.play

import android.app.Activity
import com.android.billingclient.api.*
import com.tukonnichiwa.purchasekit.PurchaseKitConfig
import com.tukonnichiwa.purchasekit.internal.StorePort
import com.tukonnichiwa.purchasekit.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/** All access is confined to the composition root's Main dispatcher. */
internal class PlayStore(
    private val config: PurchaseKitConfig,
    private val scope: CoroutineScope,
    private val clientFactory: (PurchasesUpdatedListener) -> BillingClient,
) : StorePort {
    private val definitions = config.products.associateBy { it.id }
    private val connectionMutex = Mutex()
    private val mutableConnection = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val updateChannel = Channel<PurchaseKitResult<List<PurchaseRecord>>>(Channel.UNLIMITED)
    override val connection: StateFlow<ConnectionState> = mutableConnection.asStateFlow()
    override val updates: Flow<PurchaseKitResult<List<PurchaseRecord>>> = updateChannel.receiveAsFlow()
    private var closed = false
    private var activeCheckout: Checkout? = null
    private var generation = 0
    private var client = newClient()

    private fun newClient(): BillingClient {
        val createdGeneration = ++generation
        return clientFactory { result, purchases ->
            scope.launch {
                if (closed || createdGeneration != generation) return@launch
                val update = safely {
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) result.failure()
                    else if (purchases.isNullOrEmpty()) failure(ErrorCode.INVALID_STORE_DATA, "Empty purchase update.")
                    else PurchaseKitResult.Success(purchases.map { it.toRecord() })
                }
                activeCheckout?.takeIf { it.awaitingCallback }?.let { checkout ->
                    val finishesCheckout = when (update) {
                        is PurchaseKitResult.Success -> update.value.any { checkout.matches(it) }
                        // Error callbacks do not carry a checkout identifier. Only accept
                        // them after launch, never while preparing another request.
                        is PurchaseKitResult.Failure -> result.responseCode != BillingClient.BillingResponseCode.OK
                    }
                    if (finishesCheckout) releaseCheckout(checkout)
                }
                updateChannel.send(update)
            }
        }
    }

    override suspend fun connect(): PurchaseKitResult<Unit> = connectionMutex.withLock {
        if (closed) return@withLock closedFailure()
        if (client.isReady) {
            mutableConnection.value = ConnectionState.READY
            return@withLock PurchaseKitResult.Success(Unit)
        }
        mutableConnection.value = ConnectionState.CONNECTING
        val connectingClient = client
        var successful = false
        try {
            val result = awaitSdk<Unit> { complete ->
                connectingClient.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        complete { result.unitResult() }
                    }
                    override fun onBillingServiceDisconnected() {
                        scope.launch {
                            if (!closed && client === connectingClient) {
                                mutableConnection.value = ConnectionState.DISCONNECTED
                            }
                        }
                        complete { failure(ErrorCode.DISCONNECTED, "Store disconnected.", true) }
                    }
                })
            }
            successful = result is PurchaseKitResult.Success
            if (!closed) mutableConnection.value = if (successful) ConnectionState.READY else ConnectionState.DISCONNECTED
            result
        } finally {
            if (!successful && !closed) {
                // Retire timed-out/cancelled clients so old setup callbacks cannot revive a connection.
                connectingClient.endConnection()
                client = newClient()
                mutableConnection.value = ConnectionState.DISCONNECTED
            }
        }
    }

    override suspend fun queryCatalog(): PurchaseKitResult<Catalog> {
        val connected = connect()
        if (connected is PurchaseKitResult.Failure) return connected
        val products = mutableListOf<Product>()
        for ((type, group) in config.products.groupBy { it.type.playType() }) {
            for (batch in group.chunked(20)) {
                when (val result = queryDetails(type, batch.map { it.id })) {
                    is PurchaseKitResult.Failure -> return result
                    is PurchaseKitResult.Success -> {
                        val mapped = safely {
                            PurchaseKitResult.Success(result.value.mapNotNull { details ->
                                definitions[details.productId]?.let { details.toProduct(it) }
                            })
                        }
                        when (mapped) {
                            is PurchaseKitResult.Failure -> return mapped
                            is PurchaseKitResult.Success -> products.addAll(mapped.value)
                        }
                    }
                }
            }
        }
        val byId = products.associateBy { it.definition.id }
        return PurchaseKitResult.Success(Catalog(
            products = config.products.mapNotNull { byId[it.id] },
            unavailableProductIds = definitions.keys - byId.keys,
        ))
    }

    override suspend fun queryPurchases(): PurchaseKitResult<List<PurchaseRecord>> {
        val connected = connect()
        if (connected is PurchaseKitResult.Failure) return connected
        val purchases = mutableListOf<PurchaseRecord>()
        for (type in config.products.map { it.type.playType() }.distinct()) {
            val result = awaitSdk<List<PurchaseRecord>> { complete ->
                val params = QueryPurchasesParams.newBuilder().setProductType(type)
                if (type == BillingClient.ProductType.SUBS) params.includeSuspendedSubscriptions(true)
                client.queryPurchasesAsync(params.build()) { billingResult, records ->
                    complete {
                        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK)
                            PurchaseKitResult.Success(records.map { it.toRecord() })
                        else billingResult.failure()
                    }
                }
            }
            when (result) {
                is PurchaseKitResult.Failure -> return result // Never publish a partial ownership snapshot.
                is PurchaseKitResult.Success -> purchases.addAll(result.value)
            }
        }
        return PurchaseKitResult.Success(purchases.distinctBy { it.token })
    }

    /** Returns only launch acceptance; the listener owns subsequent payment results. */
    suspend fun launch(activity: Activity, request: PurchaseRequest): PurchaseKitResult<Unit> {
        if (closed) return closedFailure()
        if (activeCheckout != null) return failure(ErrorCode.BUSY, "A checkout is already active.")
        val definition = definitions[request.productId]
            ?: return failure(ErrorCode.INVALID_REQUEST, "Product is not configured.")
        if (request.replacement != null && definition.type != ProductType.SUBSCRIPTION)
            return failure(ErrorCode.INVALID_REQUEST, "Replacement requires a subscription.")
        if (activity.isFinishing || activity.isDestroyed)
            return failure(ErrorCode.INVALID_REQUEST, "Checkout requires an active Activity.")
        val checkout = Checkout(request)
        activeCheckout = checkout
        var launched = false
        try {
            val connected = connect()
            if (connected is PurchaseKitResult.Failure) return connected
            // Fresh details avoid launching with a stale ProductDetails object.
            val detailsResult = queryDetails(definition.type.playType(), listOf(definition.id))
            if (detailsResult is PurchaseKitResult.Failure) return detailsResult
            val details = (detailsResult as PurchaseKitResult.Success).value.singleOrNull()
                ?: return failure(ErrorCode.PRODUCT_UNAVAILABLE, "Product is unavailable.")
            return safely {
                val product = details.toProduct(definition)
                    ?: return@safely failure(ErrorCode.PRODUCT_UNAVAILABLE, "No supported offer is available.")
                val selected = selectOffer(product, request.offerToken)
                if (selected is PurchaseKitResult.Failure) return@safely selected
                val token = (selected as PurchaseKitResult.Success).value
                val productParams = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
                token?.let { productParams.setOfferToken(it) }
                request.replacement?.let {
                    productParams.setSubscriptionProductReplacementParams(
                        BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.newBuilder()
                            .setOldProductId(it.oldProductId).setReplacementMode(it.mode.playMode()).build(),
                    )
                }
                val params = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(productParams.build()))
                    .setIsOfferPersonalized(request.personalizedPrice)
                request.obfuscatedAccountId?.let { params.setObfuscatedAccountId(it) }
                request.obfuscatedProfileId?.let { params.setObfuscatedProfileId(it) }
                request.replacement?.let {
                    params.setSubscriptionUpdateParams(BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                        .setOldPurchaseToken(it.oldPurchaseToken).build())
                }
                if (activity.isFinishing || activity.isDestroyed)
                    return@safely failure(ErrorCode.INVALID_REQUEST, "Activity closed before checkout.")
                // Set before calling the SDK, which may deliver its callback synchronously.
                checkout.awaitingCallback = true
                client.launchBillingFlow(activity, params.build()).unitResult().also {
                    launched = it is PurchaseKitResult.Success
                    if (launched && activeCheckout === checkout) {
                        checkout.timeout = scope.launch {
                            delay(config.checkoutTimeoutMillis)
                            if (activeCheckout !== checkout) return@launch
                            checkout.timeout = null
                            releaseCheckout(checkout)
                            updateChannel.send(failure(ErrorCode.TIMEOUT,
                                "Checkout callback timed out; restore purchases to reconcile payment.", true))
                        }
                    }
                }
            }
        } finally {
            if (!launched) releaseCheckout(checkout)
        }
    }

    override suspend fun acknowledge(token: String): PurchaseKitResult<Unit> {
        val connected = connect()
        if (connected is PurchaseKitResult.Failure) return connected
        return awaitSdk { complete ->
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) {
                result -> complete { result.unitResult() }
            }
        }
    }

    override suspend fun consume(token: String): PurchaseKitResult<Unit> {
        val connected = connect()
        if (connected is PurchaseKitResult.Failure) return connected
        return awaitSdk { complete ->
            client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(token).build()) { result, _ ->
                complete { result.unitResult() }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        generation++
        activeCheckout?.let { releaseCheckout(it) }
        client.endConnection()
        mutableConnection.value = ConnectionState.CLOSED
        updateChannel.close()
    }

    private suspend fun queryDetails(type: String, ids: List<String>): PurchaseKitResult<List<ProductDetails>> =
        awaitSdk { complete ->
            val params = QueryProductDetailsParams.newBuilder().setProductList(ids.map {
                QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build()
            }).build()
            client.queryProductDetailsAsync(params) { result, details ->
                complete {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK)
                        PurchaseKitResult.Success(details.productDetailsList.toList())
                    else result.failure()
                }
            }
        }

    private suspend fun <T> awaitSdk(
        register: ((() -> PurchaseKitResult<T>) -> Unit) -> Unit,
    ): PurchaseKitResult<T> {
        if (closed) return closedFailure()
        return withTimeoutOrNull(config.operationTimeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                try {
                    register { value ->
                        scope.launch {
                            if (continuation.isActive) continuation.resume(
                                if (closed) closedFailure() else safely(value),
                            )
                        }
                    }
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(
                        failure(ErrorCode.STORE_ERROR, "Could not start store operation.", true),
                    )
                }
            }
        } ?: failure(ErrorCode.TIMEOUT, "Store operation timed out.", true)
    }

    private fun <T> safely(block: () -> PurchaseKitResult<T>): PurchaseKitResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalArgumentException) {
        failure(ErrorCode.INVALID_STORE_DATA, "Store returned invalid or unsupported data.")
    } catch (_: Exception) {
        failure(ErrorCode.STORE_ERROR, "Store operation failed.", true)
    }

    private fun closedFailure() = failure(ErrorCode.CLOSED, "PurchaseKit is closed.")
    private fun failure(code: ErrorCode, message: String, retryable: Boolean = false) =
        PurchaseKitResult.Failure(PurchaseError(code, message, retryable))

    private fun releaseCheckout(checkout: Checkout) {
        if (activeCheckout !== checkout) return
        activeCheckout = null
        checkout.timeout?.cancel()
    }

    /** Owns only request metadata and its timer, never an Activity. */
    private class Checkout(val request: PurchaseRequest) {
        var awaitingCallback = false
        var timeout: Job? = null

        fun matches(purchase: PurchaseRecord): Boolean {
            if (request.productId in purchase.pendingUpdate?.productIds.orEmpty()) return true
            val replacement = request.replacement
            if (purchase.token == replacement?.oldPurchaseToken) return false
            // A deferred replacement reports the new token with the old product until renewal.
            return request.productId in purchase.productIds ||
                (replacement != null && replacement.oldProductId in purchase.productIds)
        }
    }
}

private fun ReplacementMode.playMode(): Int = when (this) {
    ReplacementMode.WITH_TIME_PRORATION -> BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.WITH_TIME_PRORATION
    ReplacementMode.CHARGE_PRORATED_PRICE -> BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.CHARGE_PRORATED_PRICE
    ReplacementMode.WITHOUT_PRORATION -> BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.WITHOUT_PRORATION
    ReplacementMode.CHARGE_FULL_PRICE -> BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.CHARGE_FULL_PRICE
    ReplacementMode.DEFERRED -> BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.DEFERRED
}

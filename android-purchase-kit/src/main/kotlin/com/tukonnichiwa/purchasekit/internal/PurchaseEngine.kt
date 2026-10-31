package com.tukonnichiwa.purchasekit.internal

import com.tukonnichiwa.purchasekit.PurchaseKitConfig
import com.tukonnichiwa.purchasekit.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Serializes ownership observations and owns verification-before-finalization policy. */
internal class PurchaseEngine(
    private val config: PurchaseKitConfig,
    private val store: StorePort,
    private val verifier: PurchaseVerifier,
    scope: CoroutineScope,
) {
    private val definitions = config.products.associateBy { it.id }
    private val mutation = Mutex()
    private val owned = linkedMapOf<String, OwnedPurchase>()
    private val completed = mutableSetOf<String>()
    private val mutableState = MutableStateFlow(PurchaseKitState())
    private val mutableEvents = MutableSharedFlow<PurchaseEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val state: StateFlow<PurchaseKitState> = mutableState.asStateFlow()
    val events: SharedFlow<PurchaseEvent> = mutableEvents.asSharedFlow()
    private var closed = false

    init {
        scope.launch {
            store.connection.collect { connection ->
                if (!closed) mutableState.update { it.copy(connection = connection) }
            }
        }
        scope.launch {
            store.updates.collect { update ->
                if (!closed) when (update) {
                    is PurchaseKitResult.Success -> mutation.withLock {
                        processUpdates(update.value)
                    }
                    is PurchaseKitResult.Failure -> {
                        report(update.error)
                        if (update.error.code == ErrorCode.ALREADY_OWNED) restorePurchases()
                    }
                }
            }
        }
    }

    suspend fun connect(): PurchaseKitResult<Unit> = if (closed) closedResult() else
        store.connect().also { if (it is PurchaseKitResult.Failure) report(it.error) }

    suspend fun refreshCatalog(): PurchaseKitResult<Catalog> = mutation.withLock {
        if (closed) return@withLock closedResult()
        when (val result = store.queryCatalog()) {
            is PurchaseKitResult.Success -> {
                mutableState.update { it.copy(catalog = result.value, lastError = null) }
                result
            }
            is PurchaseKitResult.Failure -> result.also { report(it.error) }
        }
    }

    suspend fun restorePurchases(): PurchaseKitResult<List<OwnedPurchase>> = mutation.withLock {
        if (closed) return@withLock closedResult()
        when (val result = store.queryPurchases()) {
            is PurchaseKitResult.Failure -> result.also { report(it.error) }
            is PurchaseKitResult.Success -> {
                mutableState.update { it.copy(lastError = null) }
                reconcile(result.value)
                PurchaseKitResult.Success(state.value.purchases)
            }
        }
    }

    private suspend fun processUpdates(updates: List<PurchaseRecord>) {
        val subscriptionChanged = updates.any {
            it.state == PurchaseState.PURCHASED && isSubscription(it)
        }
        if (!subscriptionChanged) {
            for (purchase in updates) process(purchase)
            return
        }

        // A replacement callback contains the new token, not a removal for its predecessor.
        // Query ownership before publishing the new grant. Merge callback observations so a
        // lagging store query cannot hide a just-delivered purchase or pending change.
        when (val snapshot = store.queryPurchases()) {
            is PurchaseKitResult.Success -> reconcile(
                (snapshot.value + updates).associateBy { it.token }.values.toList(),
            )
            is PurchaseKitResult.Failure -> {
                report(snapshot.error)
                // Failed queries cannot prove token removal. Reverify known subscriptions so
                // the backend can reject a replaced token without waiting for a later restore.
                val subscriptions = owned.values.map { it.purchase }.filter { isSubscription(it) }
                for (purchase in (subscriptions + updates).associateBy { it.token }.values) process(purchase)
            }
        }
    }

    private fun isSubscription(purchase: PurchaseRecord): Boolean =
        purchase.productIds.any { definitions[it]?.type == ProductType.SUBSCRIPTION }

    /** Called only while holding mutation; never acquire the mutex recursively. */
    private suspend fun reconcile(records: List<PurchaseRecord>) {
        owned.keys.retainAll(records.mapTo(mutableSetOf()) { it.token })
        publish()
        for (purchase in records) process(purchase)
    }

    /** A closed instance never republishes entitlement state from a late callback. */
    fun close() {
        closed = true
        owned.clear()
        completed.clear()
        mutableState.value = PurchaseKitState(connection = ConnectionState.CLOSED)
    }

    fun report(error: PurchaseError) {
        if (closed) return
        mutableState.update { it.copy(lastError = error) }
        mutableEvents.tryEmit(
            if (error.code == ErrorCode.CANCELLED) PurchaseEvent.UserCancelled else PurchaseEvent.Failed(error),
        )
    }

    private suspend fun process(purchase: PurchaseRecord) {
        if (closed) return
        // Never finalize a token containing unknown or mixed delivery types.
        val products = purchase.productIds.map { definitions[it] }
        if (products.any { it == null }) {
            owned.remove(purchase.token)
            publish()
            return
        }
        val types = products.filterNotNull().map { it.type }.distinct()
        if (types.size != 1) {
            val error = PurchaseError(ErrorCode.INVALID_STORE_DATA, "Purchase contains mixed product types.")
            owned[purchase.token] = OwnedPurchase(purchase, VerificationStatus.REJECTED, error)
            publish()
            report(error)
            return
        }
        if (purchase.suspended) {
            owned[purchase.token] = OwnedPurchase(purchase, VerificationStatus.SUSPENDED)
            publish()
            return
        }
        if (purchase.state == PurchaseState.PENDING) {
            // A late pending callback must not downgrade a completed payment observation.
            if (owned[purchase.token]?.purchase?.state == PurchaseState.PURCHASED || purchase.token in completed) return
            val wasPending = owned[purchase.token]?.verification == VerificationStatus.PENDING
            owned[purchase.token] = OwnedPurchase(purchase, VerificationStatus.PENDING)
            publish()
            if (!wasPending) mutableEvents.tryEmit(PurchaseEvent.Pending(purchase))
            return
        }
        val consumable = types.single() == ProductType.CONSUMABLE
        if (consumable && purchase.token in completed) return
        val verdict = try {
            withTimeoutOrNull(config.verificationTimeoutMillis) { verifier.verifyAndGrant(purchase) }
                ?: VerificationResult.RetryLater("Purchase verification timed out.")
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            VerificationResult.RetryLater("Purchase verifier cancelled its request; restore to retry.")
        } catch (_: Exception) {
            // External exceptions may contain secrets; expose a stable, redacted error instead.
            VerificationResult.RetryLater("Purchase verifier failed; restore to retry.")
        }
        if (closed) return
        when (verdict) {
            is VerificationResult.Rejected -> {
                completed.remove(purchase.token)
                verificationFailure(purchase, VerificationStatus.REJECTED,
                    PurchaseError(ErrorCode.VERIFICATION_FAILED, verdict.reason))
            }
            is VerificationResult.RetryLater -> verificationFailure(purchase, VerificationStatus.RETRY_REQUIRED,
                PurchaseError(ErrorCode.VERIFICATION_UNAVAILABLE, verdict.reason, retryable = true))
            is VerificationResult.Granted -> {
                owned[purchase.token] = OwnedPurchase(purchase, VerificationStatus.GRANTED)
                publish()
                val finalization = when {
                    verdict.finalization == Finalization.BACKEND -> PurchaseKitResult.Success(Unit)
                    consumable -> store.consume(purchase.token)
                    purchase.acknowledged || purchase.token in completed -> PurchaseKitResult.Success(Unit)
                    else -> store.acknowledge(purchase.token)
                }
                if (closed) return
                when (finalization) {
                    is PurchaseKitResult.Failure -> {
                        owned[purchase.token] = OwnedPurchase(purchase, VerificationStatus.GRANTED, finalization.error)
                        publish()
                        report(finalization.error)
                    }
                    is PurchaseKitResult.Success -> {
                        if (consumable) owned.remove(purchase.token)
                        else owned[purchase.token] = OwnedPurchase(
                            purchase.copy(acknowledged = true), VerificationStatus.GRANTED,
                        )
                        publish()
                        if (completed.add(purchase.token)) mutableEvents.tryEmit(PurchaseEvent.Completed(purchase))
                    }
                }
            }
        }
    }

    private fun verificationFailure(purchase: PurchaseRecord, status: VerificationStatus, error: PurchaseError) {
        owned[purchase.token] = OwnedPurchase(purchase, status, error)
        publish()
        report(error)
    }

    private fun publish() {
        if (closed) return
        val previousUpdates = state.value.purchases.associate { it.purchase.token to it.purchase.pendingUpdate }
        val purchases = owned.values.toList()
        val entitlements = purchases.filter { it.verification == VerificationStatus.GRANTED }
            .flatMap { it.purchase.productIds }
            .mapNotNull { definitions[it]?.entitlement }.toSet()
        mutableState.update { it.copy(purchases = purchases, entitlements = entitlements) }
        purchases.forEach { ownedPurchase ->
            val purchase = ownedPurchase.purchase
            purchase.pendingUpdate?.takeIf { it != previousUpdates[purchase.token] }?.let {
                mutableEvents.tryEmit(PurchaseEvent.PendingChange(purchase, it))
            }
        }
    }

    private fun closedResult() = PurchaseKitResult.Failure(PurchaseError(ErrorCode.CLOSED, "PurchaseKit is closed."))
}

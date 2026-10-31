package com.tukonnichiwa.purchasekit

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.PendingPurchasesParams
import com.tukonnichiwa.purchasekit.internal.PurchaseEngine
import com.tukonnichiwa.purchasekit.internal.play.PlayStore
import com.tukonnichiwa.purchasekit.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android entry point. Own one instance per signed-in account, normally at application scope.
 * Create and close on Main; suspend methods can be called from any dispatcher.
 * Activities are used only while launching UI. Closing cancels outstanding work and clears state.
 * Call [restorePurchases] on foreground entry and after account changes recreate this instance.
 */
public class PurchaseKit private constructor(
    context: Context,
    private val config: PurchaseKitConfig,
    verifier: PurchaseVerifier,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = PlayStore(config, scope) { listener ->
        BillingClient.newBuilder(context.applicationContext)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts().enablePrepaidPlans().build())
            .enableAutoServiceReconnection()
            .setListener(listener)
            .build()
    }
    private val engine = PurchaseEngine(config, store, verifier, scope)
    private var closed = false

    /** Current in-memory observations. Backend authorization must not depend on this snapshot. */
    public val state: StateFlow<PurchaseKitState> = engine.state

    /** Transient notifications; no replay, persistence, or exactly-once delivery guarantee. */
    public val events: SharedFlow<PurchaseEvent> = engine.events

    /** Connect, query the catalog, then restore ownership even when the catalog query fails. */
    public suspend fun start(): PurchaseKitResult<PurchaseKitState> = execute {
        when (val connection = engine.connect()) {
            is PurchaseKitResult.Failure -> connection
            is PurchaseKitResult.Success -> {
                val catalog = engine.refreshCatalog()
                val purchases = engine.restorePurchases()
                when {
                    catalog is PurchaseKitResult.Failure -> catalog
                    purchases is PurchaseKitResult.Failure -> purchases
                    else -> PurchaseKitResult.Success(state.value)
                }
            }
        }
    }

    /** Queries current prices and offers; unavailable IDs are included in the result. */
    public suspend fun refreshCatalog(): PurchaseKitResult<Catalog> = execute { engine.refreshCatalog() }

    /**
     * Queries active/pending ownership and retries verification/finalization. Success means the
     * store query succeeded; inspect each [OwnedPurchase] for verification/finalization failures.
     * Consumed products and expired subscriptions cannot be restored from the client store query.
     */
    public suspend fun restorePurchases(): PurchaseKitResult<List<OwnedPurchase>> =
        execute { engine.restorePurchases() }

    /**
     * Success means checkout was launched, not that payment succeeded. Observe [state] and [events].
     * Requeries the selected product before launch. Cancelling the caller after launch does not
     * dismiss the store UI; subsequent callbacks are processed by this instance's own scope.
     */
    public suspend fun launchPurchase(activity: Activity, request: PurchaseRequest): PurchaseKitResult<Unit> =
        execute {
            store.launch(activity, request).also { if (it is PurchaseKitResult.Failure) engine.report(it.error) }
        }

    /** Opens Play subscription management for a configured subscription, or the account list. */
    public suspend fun openSubscriptionManagement(
        activity: Activity,
        productId: String? = null,
    ): PurchaseKitResult<Unit> = execute {
        if (productId != null && config.products.none { it.id == productId && it.type == ProductType.SUBSCRIPTION }) {
            return@execute PurchaseKitResult.Failure(PurchaseError(ErrorCode.INVALID_REQUEST, "Unknown subscription."))
        }
        if (activity.isFinishing || activity.isDestroyed) {
            return@execute PurchaseKitResult.Failure(PurchaseError(ErrorCode.INVALID_REQUEST, "Activity is closed."))
        }
        val uri = Uri.parse("https://play.google.com/store/account/subscriptions").buildUpon()
        if (productId != null) uri.appendQueryParameter("sku", productId)
            .appendQueryParameter("package", activity.packageName)
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri.build()))
            PurchaseKitResult.Success(Unit)
        } catch (_: ActivityNotFoundException) {
            PurchaseKitResult.Failure(PurchaseError(ErrorCode.UNAVAILABLE, "No app can open subscription management."))
        }
    }

    /** Idempotent. Must be called on Main. Outstanding suspend calls are cancelled. */
    override fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        scope.cancel()
        store.close()
        engine.close()
    }

    private suspend fun <T> execute(block: suspend () -> PurchaseKitResult<T>): PurchaseKitResult<T> =
        withContext(Dispatchers.Main.immediate) {
            if (closed) return@withContext PurchaseKitResult.Failure(PurchaseError(ErrorCode.CLOSED, "PurchaseKit is closed."))
            val operation = scope.async { block() }
            try {
                operation.await()
            } finally {
                // Caller cancellation also cancels its operation, without cancelling background updates.
                operation.cancel()
            }
        }

    public companion object {
        /** Creates a disconnected instance. [verifier] is required; no automatic trust policy exists. */
        public fun create(context: Context, config: PurchaseKitConfig, verifier: PurchaseVerifier): PurchaseKit {
            checkMainThread()
            return PurchaseKit(context.applicationContext, config, verifier)
        }

        private fun checkMainThread() {
            check(Looper.myLooper() == Looper.getMainLooper()) { "Create and close PurchaseKit on Main." }
        }
    }
}

package com.tukonnichiwa.purchasekit.model

/** Stable failure vocabulary. Callers need no Billing SDK dependency to handle errors. */
public enum class ErrorCode {
    CLOSED, BUSY, CANCELLED, DISCONNECTED, UNAVAILABLE, NETWORK, TIMEOUT,
    PRODUCT_UNAVAILABLE, ALREADY_OWNED, NOT_OWNED, INVALID_REQUEST, INVALID_STORE_DATA,
    VERIFICATION_FAILED, VERIFICATION_UNAVAILABLE, STORE_ERROR,
}

/** [message] is diagnostic, not localized UI text. Do not put tokens or personal data in it. */
public data class PurchaseError(val code: ErrorCode, val message: String, val retryable: Boolean = false)

/** Expected failures are values; coroutine cancellation still propagates as cancellation. */
public sealed interface PurchaseKitResult<out T> {
    public data class Success<T>(val value: T) : PurchaseKitResult<T>
    public data class Failure(val error: PurchaseError) : PurchaseKitResult<Nothing>
}

public enum class ConnectionState { DISCONNECTED, CONNECTING, READY, CLOSED }

/** An in-memory view of the most recent store observations; not a backend authorization decision. */
public data class PurchaseKitState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val catalog: Catalog = Catalog(),
    val purchases: List<OwnedPurchase> = emptyList(),
    val entitlements: Set<String> = emptySet(),
    val lastError: PurchaseError? = null,
)

/**
 * Best-effort UI notifications without replay. Never use these to credit balances or persist grants;
 * that work belongs in [PurchaseVerifier]. Collect [PurchaseKitState] for current state.
 */
public sealed interface PurchaseEvent {
    public data class Completed(val purchase: PurchaseRecord) : PurchaseEvent
    public data class Pending(val purchase: PurchaseRecord) : PurchaseEvent
    /** The existing purchase retains its own verification status while [update] awaits payment. */
    public data class PendingChange(val purchase: PurchaseRecord, val update: PendingPurchaseUpdate) : PurchaseEvent
    public data class Failed(val error: PurchaseError) : PurchaseEvent
    public data object UserCancelled : PurchaseEvent
}

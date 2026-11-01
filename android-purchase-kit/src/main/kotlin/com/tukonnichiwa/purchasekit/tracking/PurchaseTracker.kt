package com.tukonnichiwa.purchasekit.tracking

/** Analytics labels, not proof of a subscription's current term or expiry. */
public enum class PurchaseTrackingType(public val wireValue: String) {
    DAILY("daily"), WEEKLY("weekly"), MONTHLY("monthly"), YEARLY("yearly"),
    ONE_TIME("one-time"), CONSUMABLE("consumable"), UNKNOWN("unknown"),
}

/** Contains a SHA-256 token key, never the original purchase token or signed receipt. */
public data class TrackedPurchase(
    val id: String,
    val productIds: List<String>,
    val type: PurchaseTrackingType,
    val context: String?,
) {
    override fun toString(): String = "TrackedPurchase(id=<redacted>, productIds=$productIds, type=$type, context=<redacted>)"
}

/** Return true only when delivery is accepted; false/exception retains the event for replay. */
public fun interface PurchaseTracker {
    public suspend fun track(purchase: TrackedPurchase): Boolean
}

public enum class TrackingFailure { STORAGE, DELIVERY }

/** Tracking failures are independent of billing/verification results. */
public data class PurchaseTrackingStatus(val pendingCount: Int = 0, val lastFailure: TrackingFailure? = null)

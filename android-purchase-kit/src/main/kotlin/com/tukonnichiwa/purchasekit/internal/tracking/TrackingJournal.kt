package com.tukonnichiwa.purchasekit.internal.tracking

import com.tukonnichiwa.purchasekit.tracking.*

internal data class TrackingAttempt(
    val id: String,
    val productId: String,
    val type: PurchaseTrackingType,
    val context: String?,
    val knownKeys: Set<String>,
    val replacementProductId: String? = null,
)

internal data class TrackingEntry(val purchase: TrackedPurchase, val ready: Boolean, val sent: Boolean)

internal data class TrackingSnapshot(
    val attempts: List<TrackingAttempt> = emptyList(),
    val entries: List<TrackingEntry> = emptyList(),
)

/** Application-owned persistence boundary; implementations must atomically replace a snapshot. */
internal interface TrackingJournal {
    suspend fun read(): TrackingSnapshot
    suspend fun write(snapshot: TrackingSnapshot)
}

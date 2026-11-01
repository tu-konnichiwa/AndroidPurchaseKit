package com.tukonnichiwa.purchasekit.tracking

import android.content.Context
import com.tukonnichiwa.purchasekit.internal.tracking.PreferencesTrackingJournal
import com.tukonnichiwa.purchasekit.internal.tracking.TrackingCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Optional persistent tracking configuration. Share with one live PurchaseKit instance only.
 * Use a distinct preferencesName per account/environment; retain the name across restarts.
 * Persists product IDs, attribution and token hashes, never raw tokens or signed receipts.
 * Do not put personal data or secrets in trackingContext. Data is not an entitlement cache.
 */
public class PurchaseTracking(
    context: Context,
    private val tracker: PurchaseTracker,
    preferencesName: String = "purchasekit_tracking",
    private val timeoutMillis: Long = 15_000,
) {
    private val journal = PreferencesTrackingJournal(context, preferencesName)
    private val mutableStatus = MutableStateFlow(PurchaseTrackingStatus())
    public val status: StateFlow<PurchaseTrackingStatus> = mutableStatus.asStateFlow()

    init {
        require(preferencesName.isNotBlank())
        require(timeoutMillis > 0)
    }

    internal fun coordinator(scope: CoroutineScope): TrackingCoordinator =
        TrackingCoordinator(journal, tracker, scope, timeoutMillis) { mutableStatus.value = it }
}

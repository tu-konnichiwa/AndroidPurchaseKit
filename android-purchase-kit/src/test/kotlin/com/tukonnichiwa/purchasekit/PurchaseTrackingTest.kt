package com.tukonnichiwa.purchasekit

import com.tukonnichiwa.purchasekit.internal.PurchaseEngine
import com.tukonnichiwa.purchasekit.internal.StorePort
import com.tukonnichiwa.purchasekit.internal.tracking.*
import com.tukonnichiwa.purchasekit.model.*
import com.tukonnichiwa.purchasekit.tracking.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseTrackingTest {
    private val definition = ProductDefinition("premium", ProductType.SUBSCRIPTION, "access")
    private val catalog = Catalog(listOf(product()))

    @Test fun selectedOfferAndContextSurvivePendingPaymentAndRestart() = runTest {
        val journal = MemoryJournal()
        val sent = mutableListOf<TrackedPurchase>()
        val first = TrackingCoordinator(journal, { sent += it; true }, backgroundScope)
        first.prepare(PurchaseRequest("premium", "yearly", trackingContext = "onboarding"), product())
        first.observePending(record().copy(state = PurchaseState.PENDING), catalog)
        runCurrent()
        assertTrue(sent.isEmpty())
        val restarted = TrackingCoordinator(journal, { sent += it; true }, backgroundScope)
        restarted.verified(record(), catalog)
        runCurrent()
        assertEquals(1, sent.size)
        assertEquals(PurchaseTrackingType.YEARLY, sent.single().type)
        assertEquals("onboarding", sent.single().context)
        assertEquals(TrackingCoordinator.tokenKey("secret-token"), sent.single().id)
        assertFalse(journal.raw!!.contains("secret-token"))
        assertFalse(journal.raw!!.contains("signature"))
    }

    @Test fun retriesFailedDeliveryAfterRestartWithoutNeedingStoreOwnership() = runTest {
        val journal = MemoryJournal()
        var calls = 0
        val first = TrackingCoordinator(journal, { calls++; false }, backgroundScope)
        first.verified(record(), catalog)
        runCurrent()
        assertEquals(1, calls)
        assertFalse(journal.read().entries.single().sent)
        val restarted = TrackingCoordinator(journal, { calls++; true }, backgroundScope)
        restarted.retry()
        runCurrent()
        assertEquals(2, calls)
        assertTrue(journal.read().entries.single().sent)
    }

    @Test fun duplicatesAndConcurrentRetrySignalsDoNotResendAcceptedEvents() = runTest {
        val journal = MemoryJournal()
        var calls = 0
        val coordinator = TrackingCoordinator(journal, { calls++; delay(10); true }, backgroundScope)
        coordinator.verified(record(), catalog)
        runCurrent()
        repeat(10) { coordinator.retry() }
        coordinator.verified(record(), catalog)
        advanceTimeBy(11)
        runCurrent()
        assertEquals(1, calls)
        val restarted = TrackingCoordinator(journal, { calls++; true }, backgroundScope)
        restarted.verified(record().copy(acknowledged = true), catalog)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test fun acknowledgedOwnershipOnFreshInstallDoesNotCountAsNewSale() = runTest {
        var calls = 0
        val journal = MemoryJournal()
        val coordinator = TrackingCoordinator(journal, { calls++; true }, backgroundScope)
        coordinator.verified(record().copy(acknowledged = true), catalog)
        runCurrent()
        assertEquals(0, calls)
        assertTrue(journal.read().entries.single().sent)
    }

    @Test fun knownOwnershipDoesNotStealNewCheckoutAttribution() = runTest {
        val sent = mutableListOf<TrackedPurchase>()
        val journal = MemoryJournal()
        val coordinator = TrackingCoordinator(journal, { sent += it; true }, backgroundScope)
        coordinator.verified(record().copy(acknowledged = true), catalog)
        coordinator.prepare(PurchaseRequest("premium", "yearly", trackingContext = "upgrade"), product())
        coordinator.verified(record().copy(acknowledged = true), catalog)
        coordinator.verified(record().copy(token = "new-token"), catalog)
        runCurrent()
        assertEquals("upgrade", sent.single().context)
        assertEquals(PurchaseTrackingType.YEARLY, sent.single().type)
    }

    @Test fun abandoningAnOldAttemptDoesNotEraseItsReplacement() = runTest {
        val journal = MemoryJournal()
        val coordinator = TrackingCoordinator(journal, { true }, backgroundScope)
        val old = coordinator.prepare(PurchaseRequest("premium", trackingContext = "old"), product())
        coordinator.prepare(PurchaseRequest("premium", trackingContext = "new"), product())
        coordinator.abandon(old)
        assertEquals("new", journal.read().attempts.single().context)
    }

    @Test fun deferredReplacementUsesTheNewTokenEvenWhenItCarriesTheOldProductId() = runTest {
        val journal = MemoryJournal()
        val sent = mutableListOf<TrackedPurchase>()
        val coordinator = TrackingCoordinator(journal, { sent += it; true }, backgroundScope)
        coordinator.prepare(PurchaseRequest("premium", "yearly", trackingContext = "upgrade",
            replacement = SubscriptionReplacement("basic", "old-token", ReplacementMode.DEFERRED)), product())
        coordinator.verified(record().copy(productIds = listOf("basic"), token = "old-token", acknowledged = true), catalog)
        coordinator.verified(record().copy(productIds = listOf("basic"), token = "new-token"), catalog)
        runCurrent()
        assertEquals("upgrade", sent.single().context)
        assertEquals(PurchaseTrackingType.YEARLY, sent.single().type)
    }

    @Test fun lostLocalSentMarkerLeavesTheAcceptedRequestRetryable() = runTest {
        val journal = MemoryJournal()
        var calls = 0
        val coordinator = TrackingCoordinator(journal, {
            calls++
            journal.failWrites = true
            true
        }, backgroundScope)
        coordinator.verified(record(), catalog)
        runCurrent()
        journal.failWrites = false
        assertFalse(journal.read().entries.single().sent)
        val restarted = TrackingCoordinator(journal, { calls++; true }, backgroundScope)
        restarted.retry()
        runCurrent()
        assertEquals(2, calls)
        assertTrue(journal.read().entries.single().sent)
    }

    @Test fun ambiguousRestoredPlanUsesUnknownRatherThanGuessingPeriod() = runTest {
        val sent = mutableListOf<TrackedPurchase>()
        val coordinator = TrackingCoordinator(MemoryJournal(), { sent += it; true }, backgroundScope)
        coordinator.verified(record(), catalog)
        runCurrent()
        assertEquals(PurchaseTrackingType.UNKNOWN, sent.single().type)
    }

    @Test fun allBillingPeriodsAndOneTimeLabelsMapToTheWireContract() = runTest {
        for ((period, expected) in mapOf("P1D" to PurchaseTrackingType.DAILY,
            "P1W" to PurchaseTrackingType.WEEKLY, "P1M" to PurchaseTrackingType.MONTHLY,
            "P1Y" to PurchaseTrackingType.YEARLY, "P12M" to PurchaseTrackingType.YEARLY)) {
            val sent = mutableListOf<TrackedPurchase>()
            val coordinator = TrackingCoordinator(MemoryJournal(), { sent += it; true }, backgroundScope)
            coordinator.prepare(PurchaseRequest("premium", "selected"),
                SubscriptionProduct(definition, "Premium", "", listOf(offer("selected", period))))
            coordinator.verified(record(), catalog)
            runCurrent()
            assertEquals(expected, sent.single().type)
        }
        for ((type, expected) in mapOf(ProductType.CONSUMABLE to PurchaseTrackingType.CONSUMABLE,
            ProductType.NON_CONSUMABLE to PurchaseTrackingType.ONE_TIME)) {
            val sent = mutableListOf<TrackedPurchase>()
            val coordinator = TrackingCoordinator(MemoryJournal(), { sent += it; true }, backgroundScope)
            val product = OneTimeProduct(ProductDefinition("item", type), "Item", "", emptyList())
            coordinator.prepare(PurchaseRequest("item"), product)
            coordinator.verified(record().copy(productIds = listOf("item")), Catalog(listOf(product)))
            runCurrent()
            assertEquals(expected, sent.single().type)
        }
    }

    @Test fun timeoutExceptionAndTrackerOwnedCancellationKeepEventsRetryable() = runTest {
        for (tracker in listOf<PurchaseTracker>(
            PurchaseTracker { delay(100); true },
            PurchaseTracker { throw IllegalStateException("secret") },
            PurchaseTracker { throw CancellationException("secret") },
        )) {
            val journal = MemoryJournal()
            val statuses = mutableListOf<PurchaseTrackingStatus>()
            val coordinator = TrackingCoordinator(journal, tracker, backgroundScope, 10) { statuses += it }
            coordinator.verified(record(), catalog)
            runCurrent()
            advanceTimeBy(11)
            runCurrent()
            assertFalse(journal.read().entries.single().sent)
            assertEquals(TrackingFailure.DELIVERY, statuses.last().lastFailure)
        }
    }

    @Test fun ownerCancellationDoesNotMarkDeliveryAsAccepted() = runTest {
        val job = SupervisorJob(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext + job)
        val journal = MemoryJournal()
        val coordinator = TrackingCoordinator(journal, { awaitCancellation() }, scope)
        coordinator.verified(record(), catalog)
        runCurrent()
        job.cancel()
        runCurrent()
        assertFalse(journal.read().entries.single().sent)
    }

    @Test fun engineStagesBeforeConsumptionAndDoesNotWaitForNetwork() = runTest {
        val journal = MemoryJournal()
        val gate = CompletableDeferred<Boolean>()
        val coordinator = TrackingCoordinator(journal, { gate.await() }, backgroundScope)
        var consumed = false
        val store = store(record().copy(productIds = listOf("coins"))) {
            assertTrue(journal.read().entries.single().ready)
            consumed = true
        }
        val engine = PurchaseEngine(PurchaseKitConfig(listOf(ProductDefinition("coins", ProductType.CONSUMABLE))),
            store, { VerificationResult.Granted() }, backgroundScope, coordinator)
        engine.restorePurchases()
        assertTrue(consumed)
        assertTrue(engine.state.value.purchases.isEmpty())
        runCurrent()
        assertFalse(journal.read().entries.single().sent)
        gate.complete(true)
        runCurrent()
        assertTrue(journal.read().entries.single().sent)
    }

    @Test fun trackingStorageFailureDoesNotPreventPurchaseFinalization() = runTest {
        val journal = MemoryJournal().apply { failWrites = true }
        val statuses = mutableListOf<PurchaseTrackingStatus>()
        val coordinator = TrackingCoordinator(journal, { true }, backgroundScope, status = { statuses += it })
        var finalized = false
        val engine = PurchaseEngine(PurchaseKitConfig(listOf(definition)), store(record()) { finalized = true },
            { VerificationResult.Granted() }, backgroundScope, coordinator)
        engine.restorePurchases()
        assertTrue(finalized)
        assertEquals(setOf("access"), engine.state.value.entitlements)
        assertTrue(statuses.any { it.lastFailure == TrackingFailure.STORAGE })
    }

    @Test fun rejectedAndPendingPurchasesNeverBecomeReadyForDelivery() = runTest {
        for (purchase in listOf(record(), record().copy(state = PurchaseState.PENDING))) {
            val journal = MemoryJournal()
            var calls = 0
            val coordinator = TrackingCoordinator(journal, { calls++; true }, backgroundScope)
            val engine = PurchaseEngine(PurchaseKitConfig(listOf(definition)), store(purchase) { fail() },
                { VerificationResult.Rejected("invalid") }, backgroundScope, coordinator)
            engine.restorePurchases()
            runCurrent()
            assertEquals(0, calls)
            assertTrue(journal.read().entries.none { it.ready })
        }
    }

    @Test fun codecPreservesOutboxAndAttributionWithoutRawPurchaseData() {
        val snapshot = TrackingSnapshot(listOf(TrackingAttempt("attempt", "premium", PurchaseTrackingType.YEARLY,
            "quote\"\nsource", setOf("hash"))), listOf(
            TrackingEntry(TrackedPurchase("hash", listOf("premium"), PurchaseTrackingType.YEARLY, null), true, false)))
        assertEquals(snapshot, TrackingJournalCodec.decode(TrackingJournalCodec.encode(snapshot)))
        assertThrows(IllegalArgumentException::class.java) {
            TrackingJournalCodec.decode("""{"version":99}""")
        }
        assertThrows(Exception::class.java) { TrackingJournalCodec.decode("broken") }
    }

    @Test fun httpPayloadIsExactlyFiveFieldsAndDoesNotExposeTrackingKeys() {
        val tracker = HttpPurchaseTracker("https://example.com/purchases", "example.app", "1.0", "Device")
        val purchase = TrackedPurchase("private-hash", listOf("premium"), PurchaseTrackingType.MONTHLY, "onboarding")
        val payload = JSONObject(tracker.payload(purchase))
        assertEquals(setOf("appID", "purchase", "device_name", "action_case", "app_version"), payload.keys().asSequence().toSet())
        assertEquals("example.app", payload.getString("appID"))
        assertEquals("monthly", payload.getString("purchase"))
        assertEquals("Device", payload.getString("device_name"))
        assertEquals("onboarding", payload.getString("action_case"))
        assertEquals("1.0", payload.getString("app_version"))
        assertFalse(payload.toString().contains("private-hash"))
        assertEquals("unlock", JSONObject(tracker.payload(purchase.copy(context = null))).getString("action_case"))
        assertFalse(purchase.toString().contains("onboarding"))
    }

    @Test fun trackingConfigurationRejectsUnsafeEndpointAndSensitiveOversizedContext() {
        assertThrows(IllegalArgumentException::class.java) {
            HttpPurchaseTracker("http://example.com", "app", "1", "Device")
        }
        assertThrows(IllegalArgumentException::class.java) { PurchaseRequest("premium", trackingContext = " ") }
        assertThrows(IllegalArgumentException::class.java) { PurchaseRequest("premium", trackingContext = "a".repeat(257)) }
        assertFalse(PurchaseRequest("premium", trackingContext = "private-context").toString().contains("private-context"))
    }

    private fun record() = PurchaseRecord(listOf("premium"), "secret-token", "example.app",
        PurchaseState.PURCHASED, 1, 1, false, true)

    private fun product() = SubscriptionProduct(definition, "Premium", "",
        listOf(offer("monthly", "P1M"), offer("yearly", "P1Y")))

    private fun offer(token: String, period: String) = SubscriptionOffer(token, token, null, emptyList(), listOf(
        PricingPhase(Price(1_000_000, "USD", "$1"), period, Recurrence.INFINITE, 0)))

    private fun store(purchase: PurchaseRecord, finalize: suspend () -> Unit): StorePort = object : StorePort {
        override val connection = MutableStateFlow(ConnectionState.READY)
        override val updates = emptyFlow<PurchaseKitResult<List<PurchaseRecord>>>()
        override suspend fun connect() = PurchaseKitResult.Success(Unit)
        override suspend fun queryCatalog() = PurchaseKitResult.Success(Catalog())
        override suspend fun queryPurchases() = PurchaseKitResult.Success(listOf(purchase))
        override suspend fun acknowledge(token: String): PurchaseKitResult<Unit> { finalize(); return PurchaseKitResult.Success(Unit) }
        override suspend fun consume(token: String): PurchaseKitResult<Unit> { finalize(); return PurchaseKitResult.Success(Unit) }
        override fun close() = Unit
    }

    private class MemoryJournal : TrackingJournal {
        var raw: String? = null
        var failWrites = false
        override suspend fun read(): TrackingSnapshot = raw?.let(TrackingJournalCodec::decode) ?: TrackingSnapshot()
        override suspend fun write(snapshot: TrackingSnapshot) {
            check(!failWrites)
            raw = TrackingJournalCodec.encode(snapshot)
        }
    }
}

package com.tukonnichiwa.purchasekit

import com.tukonnichiwa.purchasekit.internal.PurchaseEngine
import com.tukonnichiwa.purchasekit.internal.StorePort
import com.tukonnichiwa.purchasekit.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseEngineTest {
    private val definitions = listOf(
        ProductDefinition("premium", ProductType.NON_CONSUMABLE, "pro"),
        ProductDefinition("monthly", ProductType.SUBSCRIPTION, "pro"),
        ProductDefinition("coins", ProductType.CONSUMABLE),
    )
    private val config = PurchaseKitConfig(definitions, verificationTimeoutMillis = 100)

    @Test fun pendingIsNotVerifiedOrFinalized() = runTest {
        val store = FakeStore(listOf(record().copy(state = PurchaseState.PENDING)))
        var calls = 0
        val engine = PurchaseEngine(config, store, { calls++; VerificationResult.Granted() }, backgroundScope)
        engine.restorePurchases()
        assertEquals(0, calls)
        assertTrue(store.actions.isEmpty())
        assertTrue(engine.state.value.entitlements.isEmpty())
        assertEquals(VerificationStatus.PENDING, engine.state.value.purchases.single().verification)
    }

    @Test fun grantIsCommittedBeforeConsumptionAndDuplicateCallbacksDoNotConsumeAgain() = runTest {
        val purchase = record("coins").copy(quantity = 3)
        val store = FakeStore(listOf(purchase))
        val actions = store.actions
        val engine = PurchaseEngine(config, store, {
            assertEquals(3, it.quantity)
            actions += "grant"
            VerificationResult.Granted()
        }, backgroundScope)
        engine.restorePurchases()
        store.send(PurchaseKitResult.Success(listOf(purchase)))
        runCurrent()
        assertEquals(listOf("grant", "consume"), actions)
        assertTrue(engine.state.value.purchases.isEmpty())
        assertTrue(engine.state.value.entitlements.isEmpty())
    }

    @Test fun durablePurchaseIsVerifiedAgainButAcknowledgedOnce() = runTest {
        val store = FakeStore(listOf(record()))
        var calls = 0
        val engine = PurchaseEngine(config, store, { calls++; VerificationResult.Granted() }, backgroundScope)
        engine.restorePurchases()
        engine.restorePurchases()
        assertEquals(2, calls)
        assertEquals(listOf("acknowledge"), store.actions)
        assertEquals(setOf("pro"), engine.state.value.entitlements)
        assertTrue(engine.state.value.purchases.single().purchase.acknowledged)
    }

    @Test fun rejectionRevokesPriorGrantWithoutFinalizingAgain() = runTest {
        val store = FakeStore(listOf(record()))
        var verdict: VerificationResult = VerificationResult.Granted()
        val engine = PurchaseEngine(config, store, { verdict }, backgroundScope)
        engine.restorePurchases()
        verdict = VerificationResult.Rejected("revoked")
        engine.restorePurchases()
        assertTrue(engine.state.value.entitlements.isEmpty())
        assertEquals(VerificationStatus.REJECTED, engine.state.value.purchases.single().verification)
        assertEquals(listOf("acknowledge"), store.actions)
    }

    @Test fun finalizationFailureKeepsVerifiedGrantAndRetriesOnRestore() = runTest {
        val store = FakeStore(listOf(record()))
        store.finalization = failure(ErrorCode.NETWORK)
        val engine = PurchaseEngine(config, store, { VerificationResult.Granted() }, backgroundScope)
        engine.restorePurchases()
        assertEquals(setOf("pro"), engine.state.value.entitlements)
        assertEquals(ErrorCode.NETWORK, engine.state.value.purchases.single().error?.code)
        store.finalization = PurchaseKitResult.Success(Unit)
        engine.restorePurchases()
        assertEquals(listOf("acknowledge", "acknowledge"), store.actions)
        assertNull(engine.state.value.purchases.single().error)
    }

    @Test fun verifierExceptionIsRedactedAndRetryable() = runTest {
        val store = FakeStore(listOf(record()))
        val engine = PurchaseEngine(config, store, { throw IllegalStateException("secret-token") }, backgroundScope)
        engine.restorePurchases()
        assertTrue(store.actions.isEmpty())
        assertEquals(ErrorCode.VERIFICATION_UNAVAILABLE, engine.state.value.lastError?.code)
        assertFalse(engine.state.value.lastError.toString().contains("secret-token"))
    }

    @Test fun verifierTimeoutDoesNotGrantOrFinalize() = runTest {
        val store = FakeStore(listOf(record()))
        val engine = PurchaseEngine(config, store, { awaitCancellation() }, backgroundScope)
        engine.restorePurchases()
        assertEquals(100, testScheduler.currentTime)
        assertTrue(store.actions.isEmpty())
        assertTrue(engine.state.value.entitlements.isEmpty())
        assertEquals(VerificationStatus.RETRY_REQUIRED, engine.state.value.purchases.single().verification)
    }

    @Test fun cancellingRestorePropagatesAndReleasesTheMutex() = runTest {
        val store = FakeStore(listOf(record()))
        var blocked = true
        val engine = PurchaseEngine(config, store, {
            if (blocked) awaitCancellation() else VerificationResult.Granted()
        }, backgroundScope)
        val operation = launch { engine.restorePurchases() }
        runCurrent()
        operation.cancelAndJoin()
        assertTrue(operation.isCancelled)
        assertTrue(store.actions.isEmpty())
        blocked = false
        engine.restorePurchases()
        assertEquals(setOf("pro"), engine.state.value.entitlements)
    }

    @Test fun failedQueryPreservesSnapshotButSuccessfulEmptyQueryRemovesIt() = runTest {
        val store = FakeStore(listOf(record()))
        val engine = PurchaseEngine(config, store, { VerificationResult.Granted() }, backgroundScope)
        engine.restorePurchases()
        store.query = { failure(ErrorCode.NETWORK) }
        assertTrue(engine.restorePurchases() is PurchaseKitResult.Failure)
        assertEquals(setOf("pro"), engine.state.value.entitlements)
        store.query = { PurchaseKitResult.Success(emptyList()) }
        engine.restorePurchases()
        assertTrue(engine.state.value.entitlements.isEmpty())
    }

    @Test fun callbackArrivingDuringRestoreIsAppliedAfterTheSnapshot() = runTest {
        val store = FakeStore(emptyList())
        val snapshot = CompletableDeferred<PurchaseKitResult<List<PurchaseRecord>>>()
        store.query = { snapshot.await() }
        val engine = PurchaseEngine(config, store, { VerificationResult.Granted() }, backgroundScope)
        val restoring = launch { engine.restorePurchases() }
        runCurrent()
        store.send(PurchaseKitResult.Success(listOf(record())))
        runCurrent()
        snapshot.complete(PurchaseKitResult.Success(emptyList()))
        restoring.join()
        runCurrent()
        assertEquals(setOf("pro"), engine.state.value.entitlements)
    }

    @Test fun backendFinalizedAndAlreadyAcknowledgedPurchasesSkipClientFinalization() = runTest {
        val store = FakeStore(listOf(record()))
        val engine = PurchaseEngine(config, store, { VerificationResult.Granted(Finalization.BACKEND) }, backgroundScope)
        engine.restorePurchases()
        assertTrue(store.actions.isEmpty())
        assertEquals(setOf("pro"), engine.state.value.entitlements)
        val otherStore = FakeStore(listOf(record().copy(acknowledged = true)))
        PurchaseEngine(config, otherStore, { VerificationResult.Granted() }, backgroundScope).restorePurchases()
        assertTrue(otherStore.actions.isEmpty())
    }

    @Test fun suspendedAndUnknownPurchasesNeverGrantOrFinalize() = runTest {
        val store = FakeStore(listOf(record().copy(suspended = true), record("unknown")))
        var calls = 0
        val engine = PurchaseEngine(config, store, { calls++; VerificationResult.Granted() }, backgroundScope)
        engine.restorePurchases()
        assertEquals(0, calls)
        assertTrue(store.actions.isEmpty())
        assertTrue(engine.state.value.entitlements.isEmpty())
        assertEquals(VerificationStatus.SUSPENDED, engine.state.value.purchases.single().verification)
    }

    @Test fun mixedDeliveryTypesNeverFinalizeTheWholeToken() = runTest {
        val store = FakeStore(listOf(record().copy(productIds = listOf("premium", "coins"))))
        val engine = PurchaseEngine(config, store, { fail("Must not verify"); VerificationResult.Granted() }, backgroundScope)
        engine.restorePurchases()
        assertTrue(store.actions.isEmpty())
        assertEquals(ErrorCode.INVALID_STORE_DATA, engine.state.value.lastError?.code)
    }

    @Test fun multipleProductsCanShareOneEntitlementAndRemovalDoesNotRevokeTheOther() = runTest {
        val store = FakeStore(listOf(record(), record("monthly")))
        val engine = PurchaseEngine(config, store, { VerificationResult.Granted() }, backgroundScope)
        engine.restorePurchases()
        store.query = { PurchaseKitResult.Success(listOf(record("monthly"))) }
        engine.restorePurchases()
        assertEquals(setOf("pro"), engine.state.value.entitlements)
    }

    @Test fun pendingCanTransitionToPurchasedAndEmitCompletionOnlyOnce() = runTest {
        val store = FakeStore(listOf(record().copy(state = PurchaseState.PENDING)))
        val engine = PurchaseEngine(config, store, { VerificationResult.Granted() }, backgroundScope)
        val events = mutableListOf<PurchaseEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.events.toList(events) }
        engine.restorePurchases()
        store.send(PurchaseKitResult.Success(listOf(record())))
        store.send(PurchaseKitResult.Success(listOf(record())))
        runCurrent()
        assertEquals(1, events.filterIsInstance<PurchaseEvent.Pending>().size)
        assertEquals(1, events.filterIsInstance<PurchaseEvent.Completed>().size)
        assertEquals(setOf("pro"), engine.state.value.entitlements)
    }

    @Test fun closedEngineIgnoresLateUpdatesAndClearsAccess() = runTest {
        val store = FakeStore(listOf(record()))
        val engine = PurchaseEngine(config, store, { VerificationResult.Granted() }, backgroundScope)
        engine.restorePurchases()
        engine.close()
        store.send(PurchaseKitResult.Success(listOf(record())))
        runCurrent()
        assertEquals(ConnectionState.CLOSED, engine.state.value.connection)
        assertTrue(engine.state.value.entitlements.isEmpty())
        assertEquals(ErrorCode.CLOSED, (engine.restorePurchases() as PurchaseKitResult.Failure).error.code)
    }

    @Test fun latePendingUpdateCannotDowngradeVerifiedAccess() = runTest {
        val store = FakeStore(listOf(record()))
        val engine = PurchaseEngine(config, store, { VerificationResult.Granted() }, backgroundScope)
        engine.restorePurchases()
        store.send(PurchaseKitResult.Success(listOf(record().copy(state = PurchaseState.PENDING))))
        runCurrent()
        assertEquals(setOf("pro"), engine.state.value.entitlements)
        assertEquals(VerificationStatus.GRANTED, engine.state.value.purchases.single().verification)
    }

    @Test fun verifierOwnedCancellationDoesNotStopSubsequentUpdates() = runTest {
        val store = FakeStore(emptyList())
        var attempts = 0
        val engine = PurchaseEngine(config, store, {
            if (attempts++ == 0) throw CancellationException("backend request cancelled")
            VerificationResult.Granted()
        }, backgroundScope)
        store.send(PurchaseKitResult.Success(listOf(record())))
        runCurrent()
        assertEquals(VerificationStatus.RETRY_REQUIRED, engine.state.value.purchases.single().verification)
        store.send(PurchaseKitResult.Success(listOf(record())))
        runCurrent()
        assertEquals(setOf("pro"), engine.state.value.entitlements)
        assertEquals(2, attempts)
    }

    @Test fun consumableIsRetriedAfterFailureButNeverGrantsPersistentAccess() = runTest {
        val store = FakeStore(listOf(record("coins")))
        store.finalization = failure(ErrorCode.NETWORK)
        val engine = PurchaseEngine(config, store, { VerificationResult.Granted() }, backgroundScope)
        engine.restorePurchases()
        assertTrue(engine.state.value.entitlements.isEmpty())
        assertEquals(ErrorCode.NETWORK, engine.state.value.purchases.single().error?.code)
        store.finalization = PurchaseKitResult.Success(Unit)
        engine.restorePurchases()
        assertTrue(engine.state.value.purchases.isEmpty())
        assertEquals(listOf("consume", "consume"), store.actions)
    }

    @Test fun newProcessReverifiesAndReliesOnIdempotentBackendDelivery() = runTest {
        val store = FakeStore(listOf(record("coins")))
        val deliveries = mutableSetOf<String>()
        var calls = 0
        val verifier = PurchaseVerifier {
            calls++
            deliveries.add(it.token)
            VerificationResult.Granted()
        }
        PurchaseEngine(config, store, verifier, backgroundScope).restorePurchases()
        PurchaseEngine(config, store, verifier, backgroundScope).restorePurchases()
        assertEquals(2, calls)
        assertEquals(1, deliveries.size)
    }

    private fun record(product: String = "premium") = PurchaseRecord(
        listOf(product), "token-$product", "example.app", PurchaseState.PURCHASED,
        1, 1_000, false, product == "monthly",
    )
    private fun failure(code: ErrorCode) = PurchaseKitResult.Failure(PurchaseError(code, "test"))

    private class FakeStore(records: List<PurchaseRecord>) : StorePort {
        override val connection = MutableStateFlow(ConnectionState.READY)
        private val channel = Channel<PurchaseKitResult<List<PurchaseRecord>>>(Channel.UNLIMITED)
        override val updates = channel.receiveAsFlow()
        val actions = mutableListOf<String>()
        var query: suspend () -> PurchaseKitResult<List<PurchaseRecord>> = { PurchaseKitResult.Success(records) }
        var finalization: PurchaseKitResult<Unit> = PurchaseKitResult.Success(Unit)
        suspend fun send(update: PurchaseKitResult<List<PurchaseRecord>>) = channel.send(update)
        override suspend fun connect() = PurchaseKitResult.Success(Unit)
        override suspend fun queryCatalog() = PurchaseKitResult.Success(Catalog())
        override suspend fun queryPurchases() = query()
        override suspend fun acknowledge(token: String): PurchaseKitResult<Unit> {
            actions += "acknowledge"
            return finalization
        }
        override suspend fun consume(token: String): PurchaseKitResult<Unit> {
            actions += "consume"
            return finalization
        }
        override fun close() { channel.close() }
    }
}

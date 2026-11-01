package com.tukonnichiwa.purchasekit

import com.tukonnichiwa.purchasekit.internal.PurchaseEngine
import com.tukonnichiwa.purchasekit.internal.StorePort
import com.tukonnichiwa.purchasekit.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PlayBillingPurchaseVerifierTest {
    private val products = listOf(
        ProductDefinition("premium", ProductType.SUBSCRIPTION, "access"),
        ProductDefinition("lifetime", ProductType.NON_CONSUMABLE, "access"),
    )
    private val verifier = PlayBillingPurchaseVerifier("example.app", products)

    @Test fun eligibleUnsignedSubscriptionsAndLifetimeUseClientFinalization() = runTest {
        for (product in listOf("premium", "lifetime")) {
            val purchase = record(product)
            assertNull(purchase.signedData)
            assertEquals(VerificationResult.Granted(Finalization.CLIENT), verifier.verifyAndGrant(purchase))
        }
    }

    @Test fun wrongPackageAndUnknownProductsAreRejectedWithoutLeakingTokens() = runTest {
        for (purchase in listOf(
            record().copy(packageName = "other.app"),
            record("unknown"),
            record().copy(productIds = listOf("premium", "unknown")),
        )) {
            val result = verifier.verifyAndGrant(purchase)
            assertTrue(result is VerificationResult.Rejected)
            assertFalse(result.toString().contains(purchase.token))
        }
    }

    @Test fun pendingAndSuspendedPurchasesCannotGrantAccess() = runTest {
        for (purchase in listOf(
            record().copy(state = PurchaseState.PENDING),
            record().copy(suspended = true),
        )) assertTrue(verifier.verifyAndGrant(purchase) is VerificationResult.Rejected)
    }

    @Test fun invalidConfigurationAndConsumablesFailFast() {
        assertThrows(IllegalArgumentException::class.java) {
            PlayBillingPurchaseVerifier(" ", products)
        }
        for (definitions in listOf(emptyList(), products + products.first(),
            products + ProductDefinition("coins", ProductType.CONSUMABLE))) {
            assertThrows(IllegalArgumentException::class.java) {
                PlayBillingPurchaseVerifier("example.app", definitions)
            }
        }
    }

    @Test fun callerCannotChangeTheProductAllowlistAfterConstruction() = runTest {
        val mutableProducts = products.toMutableList()
        val local = PlayBillingPurchaseVerifier("example.app", mutableProducts)
        mutableProducts.clear()
        mutableProducts += ProductDefinition("unknown", ProductType.NON_CONSUMABLE)
        assertTrue(local.verifyAndGrant(record()) is VerificationResult.Granted)
        assertTrue(local.verifyAndGrant(record("unknown")) is VerificationResult.Rejected)
    }

    @Test fun signatureDataIsNotExaminedInExplicitTrustMode() = runTest {
        val purchase = record().copy(signedData = SignedPurchaseData("not-json", "not-a-signature"))
        assertEquals(VerificationResult.Granted(Finalization.CLIENT), verifier.verifyAndGrant(purchase))
    }

    @Test fun engineAcknowledgesOnceAndRemovesAccessAfterSuccessfulEmptyRestore() = runTest {
        val store = FakeStore(listOf(record().copy(
            pendingUpdate = PendingPurchaseUpdate(listOf("premium"), "pending-token"),
        )))
        val engine = PurchaseEngine(PurchaseKitConfig(products), store, verifier, backgroundScope)
        engine.restorePurchases()
        engine.restorePurchases()
        assertEquals(setOf("access"), engine.state.value.entitlements)
        assertEquals(listOf("secret-token"), store.acknowledged)
        assertEquals("pending-token", engine.state.value.purchases.single().purchase.pendingUpdate!!.token)
        assertTrue(store.consumed.isEmpty())
        store.records = emptyList()
        engine.restorePurchases()
        assertTrue(engine.state.value.entitlements.isEmpty())
    }

    @Test fun engineDoesNotAcknowledgeAnIneligibleObservation() = runTest {
        val store = FakeStore(listOf(record().copy(packageName = "other.app")))
        val engine = PurchaseEngine(PurchaseKitConfig(products), store, verifier, backgroundScope)
        engine.restorePurchases()
        assertTrue(engine.state.value.entitlements.isEmpty())
        assertEquals(VerificationStatus.REJECTED, engine.state.value.purchases.single().verification)
        assertTrue(store.acknowledged.isEmpty())
        assertTrue(store.consumed.isEmpty())
    }

    private fun record(product: String = "premium") = PurchaseRecord(
        productIds = listOf(product), token = "secret-token", packageName = "example.app",
        state = PurchaseState.PURCHASED, quantity = 1, purchaseTimeMillis = 1234,
        acknowledged = false, autoRenewing = product == "premium",
    )

    private class FakeStore(var records: List<PurchaseRecord>) : StorePort {
        override val connection = MutableStateFlow(ConnectionState.READY)
        override val updates = emptyFlow<PurchaseKitResult<List<PurchaseRecord>>>()
        val acknowledged = mutableListOf<String>()
        val consumed = mutableListOf<String>()
        override suspend fun connect() = PurchaseKitResult.Success(Unit)
        override suspend fun queryCatalog() = PurchaseKitResult.Success(Catalog())
        override suspend fun queryPurchases() = PurchaseKitResult.Success(records)
        override suspend fun acknowledge(token: String): PurchaseKitResult<Unit> {
            acknowledged += token
            return PurchaseKitResult.Success(Unit)
        }
        override suspend fun consume(token: String): PurchaseKitResult<Unit> {
            consumed += token
            return PurchaseKitResult.Success(Unit)
        }
        override fun close() = Unit
    }
}

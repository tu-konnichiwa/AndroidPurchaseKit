package com.tukonnichiwa.purchasekit

import android.app.Activity
import com.android.billingclient.api.*
import com.tukonnichiwa.purchasekit.internal.PurchaseEngine
import com.tukonnichiwa.purchasekit.internal.StorePort
import com.tukonnichiwa.purchasekit.internal.play.PlayStore
import com.tukonnichiwa.purchasekit.internal.play.toRecord
import com.tukonnichiwa.purchasekit.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseRegressionTest {
    @get:Rule val textUtils = AndroidTextRule()
    private val config = PurchaseKitConfig(listOf(
        ProductDefinition("premium", ProductType.SUBSCRIPTION, "premium_access"),
        ProductDefinition("basic", ProductType.SUBSCRIPTION, "basic_access"),
    ))

    @Test fun replacementCallbackMustRetireOldEntitlement() = runTest {
        val old = record("premium", "old-token")
        val replacement = record("basic", "new-token")
        val store = FakeStore(listOf(old))
        var replaced = false
        val engine = PurchaseEngine(config, store, {
            if (replaced && it.token == old.token) VerificationResult.Rejected("Replaced")
            else VerificationResult.Granted(Finalization.BACKEND)
        }, backgroundScope)
        engine.restorePurchases()
        replaced = true
        store.records = listOf(replacement)
        store.channel.send(PurchaseKitResult.Success(listOf(replacement)))
        runCurrent()
        assertEquals("The old subscription was replaced and is rejected by the backend.",
            setOf("basic_access"), engine.state.value.entitlements)
        assertEquals(listOf("new-token"), engine.state.value.purchases.map { it.purchase.token })
    }

    @Test fun failedOwnershipQueryStillReverifiesReplacedSubscription() = runTest {
        val old = record("premium", "old-token")
        val replacement = record("basic", "new-token")
        val store = FakeStore(listOf(old))
        var replaced = false
        val engine = PurchaseEngine(config, store, {
            if (replaced && it.token == old.token) VerificationResult.Rejected("Replaced")
            else VerificationResult.Granted(Finalization.BACKEND)
        }, backgroundScope)
        engine.restorePurchases()
        replaced = true
        store.queryFailure = PurchaseKitResult.Failure(PurchaseError(ErrorCode.NETWORK, "Offline", true))
        store.channel.send(PurchaseKitResult.Success(listOf(replacement)))
        runCurrent()
        assertEquals(setOf("basic_access"), engine.state.value.entitlements)
        assertEquals(VerificationStatus.REJECTED,
            engine.state.value.purchases.single { it.purchase.token == old.token }.verification)
    }

    @Test fun staleOwnershipSnapshotDoesNotLoseNewSubscriptionCallback() = runTest {
        val old = record("premium", "old-token")
        val replacement = record("basic", "new-token")
        val store = FakeStore(listOf(old))
        var replaced = false
        val engine = PurchaseEngine(config, store, {
            if (replaced && it.token == old.token) VerificationResult.Rejected("Replaced")
            else VerificationResult.Granted(Finalization.BACKEND)
        }, backgroundScope)
        engine.restorePurchases()
        replaced = true
        // Play's query has not caught up with the callback yet.
        store.channel.send(PurchaseKitResult.Success(listOf(replacement)))
        runCurrent()
        assertEquals(setOf("basic_access"), engine.state.value.entitlements)
        assertEquals(VerificationStatus.GRANTED,
            engine.state.value.purchases.single { it.purchase.token == replacement.token }.verification)
    }

    @Test fun unrelatedCallbackMustNotUnlockPreparingCheckout() = runTest {
        val client = mock(BillingClient::class.java)
        `when`(client.isReady).thenReturn(true)
        `when`(client.launchBillingFlow(any(), any())).thenReturn(ok())
        val details = ProductDetails::class.java.getDeclaredConstructor(String::class.java).let {
            it.isAccessible = true
            it.newInstance("""{
                "productId":"premium", "type":"inapp", "name":"Premium", "description":"Access",
                "skuDetailsToken":"details", "oneTimePurchaseOfferDetails":{
                  "priceAmountMicros":1000000,"priceCurrencyCode":"USD","formattedPrice":"$1.00",
                  "offerIdToken":"offer","purchaseOptionId":"buy"
                }
            }""")
        }
        var firstQuery: ProductDetailsResponseListener? = null
        var calls = 0
        doAnswer {
            val callback = it.getArgument<ProductDetailsResponseListener>(1)
            if (calls++ == 0) firstQuery = callback
            else callback.onProductDetailsResponse(ok(), QueryProductDetailsResult.create(listOf(details), emptyList()))
            null
        }.`when`(client).queryProductDetailsAsync(any(), any())
        var listener: PurchasesUpdatedListener? = null
        val store = PlayStore(PurchaseKitConfig(listOf(ProductDefinition("premium", ProductType.NON_CONSUMABLE))),
            backgroundScope) { listener = it; client }
        val activity = mock(Activity::class.java)
        val preparing = async { store.launch(activity, PurchaseRequest("premium")) }
        runCurrent()
        assertNotNull(firstQuery)
        // A prior pending purchase completes outside the current checkout.
        listener!!.onPurchasesUpdated(ok(), listOf(Purchase("""{
            "productId":"other-product", "token":"other-token", "packageName":"example.app",
            "purchaseState":0,"acknowledged":true
        }""", "")))
        runCurrent()
        val second = store.launch(activity, PurchaseRequest("premium"))
        preparing.cancelAndJoin()
        assertTrue("Another checkout must remain BUSY until the preparing launch releases its guard.",
            second is PurchaseKitResult.Failure && second.error.code == ErrorCode.BUSY)
        verify(client, never()).launchBillingFlow(any(), any())
    }

    @Test fun duplicatePendingChangeIsNotFinalizedUntilItBecomesPurchased() = runTest {
        val old = record("premium", "old-token")
        val change = PendingPurchaseUpdate(listOf("basic"), "next-token")
        val store = FakeStore(listOf(old))
        val verified = mutableListOf<String>()
        val engine = PurchaseEngine(config, store, {
            verified += it.token
            VerificationResult.Granted()
        }, backgroundScope)
        engine.restorePurchases()
        val events = mutableListOf<PurchaseEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.events.toList(events) }
        val pending = old.copy(pendingUpdate = change)
        repeat(2) { store.channel.send(PurchaseKitResult.Success(listOf(pending))) }
        runCurrent()
        assertEquals(1, events.filterIsInstance<PurchaseEvent.PendingChange>().size)
        assertTrue(verified.all { it == old.token })
        assertTrue(store.finalizedTokens.isEmpty())
        assertEquals(setOf("premium_access"), engine.state.value.entitlements)

        val purchased = record("basic", change.token).copy(acknowledged = false)
        store.records = listOf(purchased)
        store.channel.send(PurchaseKitResult.Success(listOf(purchased)))
        runCurrent()
        assertEquals(setOf("basic_access"), engine.state.value.entitlements)
        assertEquals(listOf(change.token), store.finalizedTokens)
        assertNull(engine.state.value.purchases.single().purchase.pendingUpdate)
        assertEquals(change.token, events.filterIsInstance<PurchaseEvent.Completed>().single().purchase.token)
    }

    @Test fun cancelledPendingChangeDisappearsOnRestoreWithoutRevokingOldAccess() = runTest {
        val old = record("premium", "old-token")
        val store = FakeStore(listOf(old.copy(pendingUpdate = PendingPurchaseUpdate(listOf("basic"), "next-token"))))
        val engine = PurchaseEngine(config, store, { VerificationResult.Granted() }, backgroundScope)
        engine.restorePurchases()
        assertNotNull(engine.state.value.purchases.single().purchase.pendingUpdate)
        store.records = listOf(old)
        engine.restorePurchases()
        assertNull(engine.state.value.purchases.single().purchase.pendingUpdate)
        assertEquals(setOf("premium_access"), engine.state.value.entitlements)
        assertTrue(store.finalizedTokens.isEmpty())
    }

    @Test fun pendingSubscriptionChangeMustRemainObservableAfterMapping() = runTest {
        val old = record("premium", "old-token")
        val store = FakeStore(listOf(old))
        val engine = PurchaseEngine(config, store, { VerificationResult.Granted(Finalization.BACKEND) }, backgroundScope)
        engine.restorePurchases()
        val events = mutableListOf<PurchaseEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.events.toList(events) }
        val raw = Purchase("""{
            "productId":"premium","token":"old-token","packageName":"example.app",
            "purchaseState":0,"acknowledged":true,
            "pendingPurchaseUpdate":{"purchaseToken":"pending-token","productIds":["basic"]}
        }""", "")
        assertEquals("pending-token", raw.pendingPurchaseUpdate!!.purchaseToken)
        store.channel.send(PurchaseKitResult.Success(listOf(raw.toRecord())))
        runCurrent()
        val current = engine.state.value.purchases.single()
        assertEquals("old-token", current.purchase.token)
        assertEquals(VerificationStatus.GRANTED, current.verification)
        assertEquals(setOf("premium_access"), engine.state.value.entitlements)
        assertEquals("pending-token", current.purchase.pendingUpdate?.token)
        assertEquals(listOf("basic"), current.purchase.pendingUpdate?.productIds)
        val pending = events.filterIsInstance<PurchaseEvent.PendingChange>().single()
        assertEquals("pending-token", pending.update.token)
        assertEquals("old-token", pending.purchase.token)
        assertTrue(events.filterIsInstance<PurchaseEvent.Completed>().isEmpty())
    }

    private fun ok() = BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build()
    private fun record(product: String, token: String) = PurchaseRecord(
        listOf(product), token, "example.app", PurchaseState.PURCHASED, 1, 1_000, true, true,
    )
    private class FakeStore(var records: List<PurchaseRecord>) : StorePort {
        override val connection = MutableStateFlow(ConnectionState.READY)
        val channel = Channel<PurchaseKitResult<List<PurchaseRecord>>>(Channel.UNLIMITED)
        override val updates = channel.receiveAsFlow()
        var queryFailure: PurchaseKitResult.Failure? = null
        val finalizedTokens = mutableListOf<String>()
        override suspend fun connect() = PurchaseKitResult.Success(Unit)
        override suspend fun queryCatalog() = PurchaseKitResult.Success(Catalog())
        override suspend fun queryPurchases(): PurchaseKitResult<List<PurchaseRecord>> =
            queryFailure ?: PurchaseKitResult.Success(records)
        override suspend fun acknowledge(token: String): PurchaseKitResult<Unit> {
            finalizedTokens += token
            return PurchaseKitResult.Success(Unit)
        }
        override suspend fun consume(token: String): PurchaseKitResult<Unit> {
            finalizedTokens += token
            return PurchaseKitResult.Success(Unit)
        }
        override fun close() { channel.close() }
    }
}

package com.tukonnichiwa.purchasekit

import android.app.Activity
import com.android.billingclient.api.*
import com.tukonnichiwa.purchasekit.internal.play.PlayStore
import com.tukonnichiwa.purchasekit.internal.tracking.*
import com.tukonnichiwa.purchasekit.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlayStoreTest {
    @get:Rule val textUtils = AndroidTextRule()
    private val config = PurchaseKitConfig(listOf(
        ProductDefinition("premium", ProductType.NON_CONSUMABLE),
        ProductDefinition("monthly", ProductType.SUBSCRIPTION),
    ), operationTimeoutMillis = 100, checkoutTimeoutMillis = 500)

    @Test fun concurrentConnectCallsShareOneSetup() = runTest {
        val client = mock(BillingClient::class.java)
        var setup: BillingClientStateListener? = null
        doAnswer { setup = it.getArgument(0); null }.`when`(client).startConnection(any())
        val store = PlayStore(config, backgroundScope) { client }
        val first = async { store.connect() }
        val second = async { store.connect() }
        runCurrent()
        assertEquals(ConnectionState.CONNECTING, store.connection.value)
        `when`(client.isReady).thenReturn(true)
        setup!!.onBillingSetupFinished(ok())
        assertTrue(first.await() is PurchaseKitResult.Success)
        assertTrue(second.await() is PurchaseKitResult.Success)
        verify(client, times(1)).startConnection(any())
        assertEquals(ConnectionState.READY, store.connection.value)
    }

    @Test fun timedOutSetupRetiresClientAndLateCallbacksDoNotReviveConnection() = runTest {
        val firstClient = mock(BillingClient::class.java)
        val nextClient = mock(BillingClient::class.java)
        var setup: BillingClientStateListener? = null
        doAnswer { setup = it.getArgument(0); null }.`when`(firstClient).startConnection(any())
        var creations = 0
        val store = PlayStore(config, backgroundScope) { if (creations++ == 0) firstClient else nextClient }
        val result = store.connect() as PurchaseKitResult.Failure
        assertEquals(ErrorCode.TIMEOUT, result.error.code)
        verify(firstClient).endConnection()
        setup!!.onBillingSetupFinished(ok())
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, store.connection.value)
        `when`(nextClient.isReady).thenReturn(true)
        assertTrue(store.connect() is PurchaseKitResult.Success)
        setup!!.onBillingServiceDisconnected()
        runCurrent()
        assertEquals(ConnectionState.READY, store.connection.value)
    }

    @Test fun cancelledConnectionCanBeRetried() = runTest {
        val firstClient = mock(BillingClient::class.java)
        val nextClient = mock(BillingClient::class.java)
        var creations = 0
        val store = PlayStore(config, backgroundScope) { if (creations++ == 0) firstClient else nextClient }
        val connecting = launch { store.connect() }
        runCurrent()
        connecting.cancelAndJoin()
        verify(firstClient).endConnection()
        `when`(nextClient.isReady).thenReturn(true)
        assertTrue(store.connect() is PurchaseKitResult.Success)
    }

    @Test fun queryFailureNeverReturnsPartialOwnership() = runTest {
        val client = readyClient()
        var calls = 0
        doAnswer {
            val listener = it.getArgument<PurchasesResponseListener>(1)
            if (calls++ == 0) listener.onQueryPurchasesResponse(ok(), listOf(purchase()))
            else listener.onQueryPurchasesResponse(result(BillingClient.BillingResponseCode.NETWORK_ERROR), emptyList())
            null
        }.`when`(client).queryPurchasesAsync(any(), any())
        val store = PlayStore(config, backgroundScope) { client }
        assertEquals(ErrorCode.NETWORK, (store.queryPurchases() as PurchaseKitResult.Failure).error.code)
        assertEquals(2, calls)
    }

    @Test fun sdkTimeoutIsBoundedAndLateResultIsIgnored() = runTest {
        val client = readyClient()
        var listener: AcknowledgePurchaseResponseListener? = null
        doAnswer { listener = it.getArgument(1); null }.`when`(client).acknowledgePurchase(any(), any())
        val store = PlayStore(config, backgroundScope) { client }
        assertEquals(ErrorCode.TIMEOUT, (store.acknowledge("token") as PurchaseKitResult.Failure).error.code)
        listener!!.onAcknowledgePurchaseResponse(ok())
        runCurrent()
        assertEquals(100, testScheduler.currentTime)
    }

    @Test fun duplicateSdkCallbacksResumeOnlyOnce() = runTest {
        val client = readyClient()
        doAnswer {
            val listener = it.getArgument<AcknowledgePurchaseResponseListener>(1)
            listener.onAcknowledgePurchaseResponse(ok())
            listener.onAcknowledgePurchaseResponse(ok())
            null
        }.`when`(client).acknowledgePurchase(any(), any())
        val store = PlayStore(config, backgroundScope) { client }
        assertTrue(store.acknowledge("token") is PurchaseKitResult.Success)
        runCurrent()
    }

    @Test fun callbacksAreQueuedEvenBeforeACollectorStarts() = runTest {
        val client = readyClient()
        var listener: PurchasesUpdatedListener? = null
        val store = PlayStore(config, backgroundScope) { listener = it; client }
        listener!!.onPurchasesUpdated(ok(), listOf(purchase()))
        runCurrent()
        val update = store.updates.first() as PurchaseKitResult.Success
        assertEquals("token", update.value.single().token)
    }

    @Test fun malformedAndCancelledUpdatesBecomeExplicitResults() = runTest {
        val client = readyClient()
        var listener: PurchasesUpdatedListener? = null
        val store = PlayStore(config, backgroundScope) { listener = it; client }
        listener!!.onPurchasesUpdated(ok(), emptyList())
        assertEquals(ErrorCode.INVALID_STORE_DATA, (store.updates.first() as PurchaseKitResult.Failure).error.code)
        listener!!.onPurchasesUpdated(result(BillingClient.BillingResponseCode.USER_CANCELED), null)
        assertEquals(ErrorCode.CANCELLED, (store.updates.first() as PurchaseKitResult.Failure).error.code)
    }

    @Test fun catalogReportsProductsThatPlayDidNotReturn() = runTest {
        val client = readyClient()
        doAnswer {
            it.getArgument<ProductDetailsResponseListener>(1).onProductDetailsResponse(ok(),
                QueryProductDetailsResult.create(emptyList(), emptyList()))
            null
        }.`when`(client).queryProductDetailsAsync(any(), any())
        val store = PlayStore(config, backgroundScope) { client }
        val catalog = (store.queryCatalog() as PurchaseKitResult.Success).value
        assertEquals(setOf("premium", "monthly"), catalog.unavailableProductIds)
    }

    @Test fun closeIsIdempotentAndRejectsFurtherOperations() = runTest {
        val client = readyClient()
        val store = PlayStore(config, backgroundScope) { client }
        store.close()
        store.close()
        assertEquals(ConnectionState.CLOSED, store.connection.value)
        assertEquals(ErrorCode.CLOSED, (store.queryPurchases() as PurchaseKitResult.Failure).error.code)
        verify(client, times(1)).endConnection()
    }

    @Test fun launchUsesFreshDetailsAndBlocksOnlyUntilCallback() = runTest {
        val client = checkoutClient()
        var listener: PurchasesUpdatedListener? = null
        val store = PlayStore(config, backgroundScope) { listener = it; client }
        val activity = mock(Activity::class.java)
        val request = PurchaseRequest("premium")
        assertTrue(store.launch(activity, request) is PurchaseKitResult.Success)
        assertEquals(ErrorCode.BUSY, (store.launch(activity, request) as PurchaseKitResult.Failure).error.code)
        listener!!.onPurchasesUpdated(result(BillingClient.BillingResponseCode.USER_CANCELED), null)
        runCurrent()
        assertTrue(store.launch(activity, request) is PurchaseKitResult.Success)
        verify(client, times(2)).queryProductDetailsAsync(any(), any())
        verify(client, times(2)).launchBillingFlow(any(), any())
    }

    @Test fun missingOfferNeverFallsBackToAnUnselectedOffer() = runTest {
        val client = checkoutClient()
        val store = PlayStore(config, backgroundScope) { client }
        val activity = mock(Activity::class.java)
        val result = store.launch(activity, PurchaseRequest("premium", offerToken = "stale-token"))
        assertEquals(ErrorCode.INVALID_REQUEST, (result as PurchaseKitResult.Failure).error.code)
        verify(client, never()).launchBillingFlow(any(), any())
        assertTrue(store.launch(activity, PurchaseRequest("premium")) is PurchaseKitResult.Success)
    }

    @Test fun unrelatedPurchaseAfterLaunchDoesNotReleaseGuardOrCancelItsTimeout() = runTest {
        val client = checkoutClient()
        var listener: PurchasesUpdatedListener? = null
        val store = PlayStore(config, backgroundScope) { listener = it; client }
        val activity = mock(Activity::class.java)
        assertTrue(store.launch(activity, PurchaseRequest("premium")) is PurchaseKitResult.Success)
        listener!!.onPurchasesUpdated(ok(), listOf(Purchase("""{
            "productId":"monthly","token":"other-token","packageName":"example.app",
            "purchaseState":0,"acknowledged":true
        }""", "")))
        runCurrent()
        assertEquals(ErrorCode.BUSY,
            (store.launch(activity, PurchaseRequest("premium")) as PurchaseKitResult.Failure).error.code)
        // The unrelated payment must still reach the core.
        assertEquals("other-token", (store.updates.first() as PurchaseKitResult.Success).value.single().token)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(ErrorCode.TIMEOUT, (store.updates.first() as PurchaseKitResult.Failure).error.code)
        assertTrue(store.launch(activity, PurchaseRequest("premium")) is PurchaseKitResult.Success)
    }

    @Test fun immediateLaunchErrorReleasesTheGuard() = runTest {
        val client = checkoutClient()
        `when`(client.launchBillingFlow(any(), any())).thenReturn(
            result(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED), ok(),
        )
        val store = PlayStore(config, backgroundScope) { client }
        val activity = mock(Activity::class.java)
        assertEquals(ErrorCode.ALREADY_OWNED,
            (store.launch(activity, PurchaseRequest("premium")) as PurchaseKitResult.Failure).error.code)
        assertTrue(store.launch(activity, PurchaseRequest("premium")) is PurchaseKitResult.Success)
    }

    @Test fun checkoutTimeoutAllowsRetryAndLatePaymentsStillReachTheCore() = runTest {
        val client = checkoutClient()
        var listener: PurchasesUpdatedListener? = null
        val store = PlayStore(config, backgroundScope) { listener = it; client }
        val activity = mock(Activity::class.java)
        assertTrue(store.launch(activity, PurchaseRequest("premium")) is PurchaseKitResult.Success)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(ErrorCode.TIMEOUT, (store.updates.first() as PurchaseKitResult.Failure).error.code)
        listener!!.onPurchasesUpdated(ok(), listOf(purchase()))
        assertTrue(store.updates.first() is PurchaseKitResult.Success)
        assertTrue(store.launch(activity, PurchaseRequest("premium")) is PurchaseKitResult.Success)
    }

    @Test fun callerCancellationBeforeLaunchReleasesTheGuard() = runTest {
        val client = checkoutClient()
        var detailsListener: ProductDetailsResponseListener? = null
        doAnswer { detailsListener = it.getArgument(1); null }.`when`(client).queryProductDetailsAsync(any(), any())
        val store = PlayStore(config, backgroundScope) { client }
        val activity = mock(Activity::class.java)
        val launch = launch { store.launch(activity, PurchaseRequest("premium")) }
        runCurrent()
        launch.cancelAndJoin()
        detailsListener!!.onProductDetailsResponse(ok(), QueryProductDetailsResult.create(listOf(checkoutDetails()), emptyList()))
        runCurrent()
        verify(client, never()).launchBillingFlow(any(), any())
        respondWithDetails(client)
        assertTrue(store.launch(activity, PurchaseRequest("premium")) is PurchaseKitResult.Success)
    }

    @Test fun busyCheckoutDoesNotReplaceAttributionAndCancellationClearsIt() = runTest {
        val journal = TrackingMemoryJournal()
        val tracking = TrackingCoordinator(journal, { fail("No verified purchase exists"); false }, backgroundScope)
        val client = checkoutClient()
        var listener: PurchasesUpdatedListener? = null
        val store = PlayStore(config, backgroundScope, tracking) { listener = it; client }
        val activity = mock(Activity::class.java)
        assertTrue(store.launch(activity, PurchaseRequest("premium", trackingContext = "first")) is PurchaseKitResult.Success)
        assertEquals(ErrorCode.BUSY, (store.launch(activity,
            PurchaseRequest("premium", trackingContext = "second")) as PurchaseKitResult.Failure).error.code)
        assertEquals("first", journal.snapshot.attempts.single().context)
        listener!!.onPurchasesUpdated(result(BillingClient.BillingResponseCode.USER_CANCELED), null)
        runCurrent()
        assertTrue(journal.snapshot.attempts.isEmpty())
    }

    @Test fun immediateCheckoutErrorDoesNotLeaveStaleAttribution() = runTest {
        val journal = TrackingMemoryJournal()
        val tracking = TrackingCoordinator(journal, { true }, backgroundScope)
        val client = checkoutClient()
        `when`(client.launchBillingFlow(any(), any())).thenReturn(result(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE))
        val store = PlayStore(config, backgroundScope, tracking) { client }
        assertTrue(store.launch(mock(Activity::class.java),
            PurchaseRequest("premium", trackingContext = "failed")) is PurchaseKitResult.Failure)
        assertTrue(journal.snapshot.attempts.isEmpty())
    }

    private class TrackingMemoryJournal : TrackingJournal {
        var snapshot = TrackingSnapshot()
        override suspend fun read() = snapshot
        override suspend fun write(snapshot: TrackingSnapshot) { this.snapshot = snapshot }
    }

    private fun checkoutClient() = readyClient().also {
        respondWithDetails(it)
        `when`(it.launchBillingFlow(any(), any())).thenReturn(ok())
    }

    private fun respondWithDetails(client: BillingClient) {
        val details = checkoutDetails()
        doAnswer {
            it.getArgument<ProductDetailsResponseListener>(1).onProductDetailsResponse(ok(),
                QueryProductDetailsResult.create(listOf(details), emptyList()))
            null
        }.`when`(client).queryProductDetailsAsync(any(), any())
    }

    private fun checkoutDetails(): ProductDetails = productDetails("""{
        "productId":"premium", "type":"inapp", "name":"Premium", "description":"Unlock premium",
        "skuDetailsToken":"details-token", "oneTimePurchaseOfferDetails":{
            "priceAmountMicros":1000000, "priceCurrencyCode":"USD", "formattedPrice":"$1.00",
            "offerIdToken":"offer", "purchaseOptionId":"buy"
        }
    }""")

    // The SDK exposes no public ProductDetails constructor. Test fixtures use its real JSON parser
    // so BillingFlowParams sees the same internal metadata as it receives from Play.
    private fun productDetails(json: String): ProductDetails =
        ProductDetails::class.java.getDeclaredConstructor(String::class.java).let {
            it.isAccessible = true
            it.newInstance(json)
        }

    private fun readyClient() = mock(BillingClient::class.java).also { `when`(it.isReady).thenReturn(true) }
    private fun ok() = result(BillingClient.BillingResponseCode.OK)
    private fun result(code: Int) = BillingResult.newBuilder().setResponseCode(code).build()
    private fun purchase() = Purchase("""{"productId":"premium","token":"token","packageName":"example.app","purchaseState":0,"acknowledged":false}""", "")
}

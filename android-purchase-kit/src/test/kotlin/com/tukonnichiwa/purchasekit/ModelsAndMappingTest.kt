package com.tukonnichiwa.purchasekit

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.tukonnichiwa.purchasekit.internal.play.*
import com.tukonnichiwa.purchasekit.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.mockito.Mockito.*

class ModelsAndMappingTest {
    @get:Rule val textUtils = AndroidTextRule()
    private val definition = ProductDefinition("premium", ProductType.NON_CONSUMABLE)
    private val price = Price(1_990_000, "USD", "$1.99")

    @Test fun invalidConfigurationIsRejectedBeforeStoreConnection() {
        assertThrows(IllegalArgumentException::class.java) { PurchaseKitConfig(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { PurchaseKitConfig(listOf(definition, definition)) }
        assertThrows(IllegalArgumentException::class.java) { PurchaseKitConfig(listOf(definition), operationTimeoutMillis = 0) }
        assertThrows(IllegalArgumentException::class.java) { ProductDefinition(" padded ", ProductType.SUBSCRIPTION) }
        assertThrows(IllegalArgumentException::class.java) { ProductDefinition("coins", ProductType.CONSUMABLE, "pro") }
    }

    @Test fun configurationCopiesCallerOwnedCollection() {
        val products = mutableListOf(definition)
        val config = PurchaseKitConfig(products)
        products.clear()
        assertEquals(listOf(definition), config.products)
    }

    @Test fun invalidMoneyAndPeriodsDoNotReachConsumers() {
        assertThrows(IllegalArgumentException::class.java) { Price(-1, "USD", "$1") }
        assertThrows(IllegalArgumentException::class.java) { Price(1, "usd", "$1") }
        assertThrows(IllegalArgumentException::class.java) { PricingPhase(price, "P0D", Recurrence.INFINITE, 0) }
        assertThrows(IllegalArgumentException::class.java) { PricingPhase(price, "P1M", Recurrence.FINITE, 0) }
        assertEquals("P1W", PricingPhase(price, "P1W", Recurrence.FINITE, 2).billingPeriod)
    }

    @Test fun identifiersAreValidatedAndSecretsAreRedacted() {
        assertThrows(IllegalArgumentException::class.java) { PurchaseRequest("premium", obfuscatedAccountId = "a".repeat(65)) }
        val request = PurchaseRequest("premium", obfuscatedAccountId = "account-secret",
            replacement = SubscriptionReplacement("monthly", "old-secret", ReplacementMode.DEFERRED))
        assertFalse(request.toString().contains("secret"))
        assertFalse(request.replacement.toString().contains("old-secret"))
        assertFalse(PurchaseRecord(listOf("premium"), "secret", "example.app", PurchaseState.PURCHASED,
            1, 0, false, false).toString().contains("secret"))
    }

    @Test fun offerSelectionNeverSilentlyChoosesBetweenOffers() {
        val first = OneTimeOffer("first", "buy", null, price, emptyList())
        val product = OneTimeProduct(definition, "Premium", "", listOf(first, first.copy(token = "second")))
        assertTrue(selectOffer(product, null) is PurchaseKitResult.Failure)
        assertTrue(selectOffer(product, "expired") is PurchaseKitResult.Failure)
        assertEquals("second", (selectOffer(product, "second") as PurchaseKitResult.Success).value)
        assertEquals("first", (selectOffer(product.copy(offers = listOf(first)), null) as PurchaseKitResult.Success).value)
    }

    @Test fun rawPurchaseMapsQuantityPendingStateAndAccountBinding() {
        val raw = Purchase("""{
            "productIds":["coins"], "token":"secret", "packageName":"example.app",
            "purchaseState":4, "quantity":3, "purchaseTime":1234,
            "acknowledged":false, "obfuscatedAccountId":"hashed-account"
        }""".trimIndent(), "signature")
        val mapped = raw.toRecord()
        assertEquals(PurchaseState.PENDING, mapped.state)
        assertEquals(3, mapped.quantity)
        assertEquals("hashed-account", mapped.obfuscatedAccountId)
        assertEquals(1234L, mapped.purchaseTimeMillis)
    }

    @Test fun rawPurchaseMissingTokenIsRejected() {
        val raw = Purchase("""{"productId":"premium","packageName":"example.app","purchaseState":0}""", "")
        assertThrows(IllegalArgumentException::class.java) { raw.toRecord() }
    }

    @Test fun storeFailuresUseStableVocabularyWithoutDebugMessage() {
        val cases = mapOf(
            BillingClient.BillingResponseCode.USER_CANCELED to ErrorCode.CANCELLED,
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED to ErrorCode.ALREADY_OWNED,
            BillingClient.BillingResponseCode.NETWORK_ERROR to ErrorCode.NETWORK,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED to ErrorCode.DISCONNECTED,
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE to ErrorCode.PRODUCT_UNAVAILABLE,
            BillingClient.BillingResponseCode.DEVELOPER_ERROR to ErrorCode.INVALID_REQUEST,
        )
        cases.forEach { (raw, expected) ->
            val error = BillingResult.newBuilder().setResponseCode(raw).setDebugMessage("secret-token").build().failure().error
            assertEquals(expected, error.code)
            assertFalse(error.message.contains("secret-token"))
        }
    }

    @Test fun oneTimeMappingPreservesPriceAndHandlesNullableTags() {
        val details = details("premium", BillingClient.ProductType.INAPP)
        val offer = mock(ProductDetails.OneTimePurchaseOfferDetails::class.java)
        `when`(offer.priceAmountMicros).thenReturn(price.amountMicros)
        `when`(offer.priceCurrencyCode).thenReturn(price.currencyCode)
        `when`(offer.formattedPrice).thenReturn(price.formatted)
        `when`(offer.offerToken).thenReturn("buy-token")
        `when`(offer.offerTags).thenReturn(null)
        `when`(details.oneTimePurchaseOfferDetailsList).thenReturn(listOf(offer))
        val product = details.toProduct(definition) as OneTimeProduct
        assertEquals(price, product.offers.single().price)
        assertTrue(product.offers.single().tags.isEmpty())
    }

    @Test fun unsupportedRentalIsExcludedAndMismatchedTypeRejected() {
        val details = details("premium", BillingClient.ProductType.INAPP)
        val offer = mock(ProductDetails.OneTimePurchaseOfferDetails::class.java)
        `when`(offer.rentalDetails).thenReturn(mock(ProductDetails.OneTimePurchaseOfferDetails.RentalDetails::class.java))
        `when`(details.oneTimePurchaseOfferDetailsList).thenReturn(listOf(offer))
        assertNull(details.toProduct(definition))
        assertThrows(IllegalArgumentException::class.java) {
            details.toProduct(ProductDefinition("premium", ProductType.SUBSCRIPTION))
        }
    }

    @Test fun subscriptionMappingRetainsTrialAndRecurringPriceInOrder() {
        val details = details("monthly", BillingClient.ProductType.SUBS)
        val offer = mock(ProductDetails.SubscriptionOfferDetails::class.java)
        val phases = mock(ProductDetails.PricingPhases::class.java)
        val trial = phase(0, "Free", "P1W", ProductDetails.RecurrenceMode.FINITE_RECURRING, 1)
        val recurring = phase(1_990_000, "$1.99", "P1M", ProductDetails.RecurrenceMode.INFINITE_RECURRING, 0)
        `when`(offer.offerToken).thenReturn("trial-token")
        `when`(offer.basePlanId).thenReturn("monthly-base")
        `when`(offer.offerId).thenReturn("trial")
        `when`(offer.pricingPhases).thenReturn(phases)
        `when`(phases.pricingPhaseList).thenReturn(listOf(trial, recurring))
        `when`(details.subscriptionOfferDetails).thenReturn(listOf(offer))
        val product = details.toProduct(ProductDefinition("monthly", ProductType.SUBSCRIPTION)) as SubscriptionProduct
        assertEquals(listOf(0L, 1_990_000L), product.offers.single().pricingPhases.map { it.price.amountMicros })
        assertEquals(listOf("P1W", "P1M"), product.offers.single().pricingPhases.map { it.billingPeriod })
    }

    private fun details(id: String, type: String): ProductDetails = mock(ProductDetails::class.java).also {
        `when`(it.productId).thenReturn(id)
        `when`(it.productType).thenReturn(type)
        `when`(it.name).thenReturn("Name")
        `when`(it.description).thenReturn("Description")
    }

    private fun phase(amount: Long, formatted: String, period: String, recurrence: Int, cycles: Int) =
        mock(ProductDetails.PricingPhase::class.java).also {
            `when`(it.priceAmountMicros).thenReturn(amount)
            `when`(it.priceCurrencyCode).thenReturn("USD")
            `when`(it.formattedPrice).thenReturn(formatted)
            `when`(it.billingPeriod).thenReturn(period)
            `when`(it.recurrenceMode).thenReturn(recurrence)
            `when`(it.billingCycleCount).thenReturn(cycles)
        }
}

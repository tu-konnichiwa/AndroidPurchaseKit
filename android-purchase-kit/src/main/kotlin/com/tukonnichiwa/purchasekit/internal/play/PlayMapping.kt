package com.tukonnichiwa.purchasekit.internal.play

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.tukonnichiwa.purchasekit.model.*

@Suppress("DEPRECATION") // Older Play services may still report SERVICE_TIMEOUT.
internal fun BillingResult.failure(): PurchaseKitResult.Failure {
    val (code, retryable) = when (responseCode) {
        BillingClient.BillingResponseCode.USER_CANCELED -> ErrorCode.CANCELLED to false
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> ErrorCode.DISCONNECTED to true
        BillingClient.BillingResponseCode.SERVICE_TIMEOUT -> ErrorCode.TIMEOUT to true
        BillingClient.BillingResponseCode.NETWORK_ERROR -> ErrorCode.NETWORK to true
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> ErrorCode.UNAVAILABLE to true
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> ErrorCode.UNAVAILABLE to false
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> ErrorCode.PRODUCT_UNAVAILABLE to false
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> ErrorCode.ALREADY_OWNED to false
        BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> ErrorCode.NOT_OWNED to false
        BillingClient.BillingResponseCode.DEVELOPER_ERROR -> ErrorCode.INVALID_REQUEST to false
        else -> ErrorCode.STORE_ERROR to true
    }
    return PurchaseKitResult.Failure(PurchaseError(code, "Google Play operation failed: $code.", retryable))
}

internal fun BillingResult.unitResult(): PurchaseKitResult<Unit> =
    if (responseCode == BillingClient.BillingResponseCode.OK) PurchaseKitResult.Success(Unit) else failure()

internal fun ProductType.playType(): String =
    if (this == ProductType.SUBSCRIPTION) BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP

internal fun Purchase.toRecord(): PurchaseRecord = PurchaseRecord(
    productIds = products.toList(),
    token = purchaseToken,
    packageName = packageName,
    state = when (purchaseState) {
        Purchase.PurchaseState.PURCHASED -> PurchaseState.PURCHASED
        Purchase.PurchaseState.PENDING -> PurchaseState.PENDING
        else -> throw IllegalArgumentException("Unsupported purchase state.")
    },
    quantity = quantity,
    purchaseTimeMillis = purchaseTime,
    acknowledged = isAcknowledged,
    autoRenewing = isAutoRenewing,
    suspended = isSuspended,
    orderId = orderId,
    obfuscatedAccountId = accountIdentifiers?.obfuscatedAccountId,
    obfuscatedProfileId = accountIdentifiers?.obfuscatedProfileId,
    pendingUpdate = pendingPurchaseUpdate?.let { PendingPurchaseUpdate(it.products.toList(), it.purchaseToken) },
)

/** Unsupported rental, preorder and installment offers are excluded, not silently misrepresented. */
internal fun ProductDetails.toProduct(definition: ProductDefinition): Product? {
    require(productId == definition.id && productType == definition.type.playType())
    require(name.isNotBlank())
    return if (definition.type == ProductType.SUBSCRIPTION) {
        val offers = subscriptionOfferDetails.orEmpty().filter { it.installmentPlanDetails == null }.map {
            SubscriptionOffer(
                token = it.offerToken,
                basePlanId = it.basePlanId,
                offerId = it.offerId,
                tags = it.offerTags.toList(),
                pricingPhases = it.pricingPhases.pricingPhaseList.map { phase ->
                    PricingPhase(
                        price = Price(phase.priceAmountMicros, phase.priceCurrencyCode, phase.formattedPrice),
                        billingPeriod = phase.billingPeriod,
                        recurrence = when (phase.recurrenceMode) {
                            ProductDetails.RecurrenceMode.INFINITE_RECURRING -> Recurrence.INFINITE
                            ProductDetails.RecurrenceMode.FINITE_RECURRING -> Recurrence.FINITE
                            ProductDetails.RecurrenceMode.NON_RECURRING -> Recurrence.NON_RECURRING
                            else -> throw IllegalArgumentException("Unknown recurrence mode.")
                        },
                        cycleCount = phase.billingCycleCount,
                    )
                },
            )
        }
        if (offers.isEmpty()) null else SubscriptionProduct(definition, name, description, offers)
    } else {
        val offers = (oneTimePurchaseOfferDetailsList ?: listOfNotNull(oneTimePurchaseOfferDetails))
            .filter { it.rentalDetails == null && it.preorderDetails == null }.map {
                OneTimeOffer(
                    token = it.offerToken?.takeIf(String::isNotBlank),
                    purchaseOptionId = it.purchaseOptionId,
                    offerId = it.offerId,
                    price = Price(it.priceAmountMicros, it.priceCurrencyCode, it.formattedPrice),
                    tags = it.offerTags.orEmpty().toList(),
                )
            }
        if (offers.isEmpty()) null else OneTimeProduct(definition, name, description, offers)
    }
}

internal fun selectOffer(product: Product, requestedToken: String?): PurchaseKitResult<String?> {
    val tokens = when (product) {
        is OneTimeProduct -> product.offers.map { it.token }
        is SubscriptionProduct -> product.offers.map { it.token }
    }
    return when {
        requestedToken != null && requestedToken in tokens -> PurchaseKitResult.Success(requestedToken)
        requestedToken == null && tokens.size == 1 -> PurchaseKitResult.Success(tokens.single())
        else -> PurchaseKitResult.Failure(PurchaseError(
            ErrorCode.INVALID_REQUEST, "Choose an available offer token from the catalog.",
        ))
    }
}

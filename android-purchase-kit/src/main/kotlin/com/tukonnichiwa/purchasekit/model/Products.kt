package com.tukonnichiwa.purchasekit.model

/** How a product is delivered. Consumables never contribute a persistent entitlement. */
public enum class ProductType { CONSUMABLE, NON_CONSUMABLE, SUBSCRIPTION }

/** Product identifiers must exactly match Play Console. [entitlement] is a host-defined access key. */
public data class ProductDefinition(
    val id: String,
    val type: ProductType,
    val entitlement: String? = if (type == ProductType.CONSUMABLE) null else id,
) {
    init {
        require(id.isNotBlank() && id == id.trim()) { "Product ID must be nonblank and unpadded." }
        require(entitlement == null || entitlement.isNotBlank()) { "Entitlement must be nonblank." }
        require(type != ProductType.CONSUMABLE || entitlement == null) {
            "Consumables cannot grant a persistent entitlement."
        }
    }
}

/** Monetary amounts are in millionths of the currency unit; display [formatted] from Play as-is. */
public data class Price(val amountMicros: Long, val currencyCode: String, val formatted: String) {
    init {
        require(amountMicros >= 0)
        require(currencyCode.matches(Regex("[A-Z]{3}")))
        require(formatted.isNotBlank())
    }
}

/** A buy offer. Rental and preorder offers are deliberately excluded from this API. */
public data class OneTimeOffer(
    val token: String?,
    val purchaseOptionId: String?,
    val offerId: String?,
    val price: Price,
    val tags: List<String>,
)

/** Recurrence of a pricing phase; a free trial is a phase with zero price. */
public enum class Recurrence { INFINITE, FINITE, NON_RECURRING }

/** [billingPeriod] is an ISO-8601 period; [cycleCount] is the number of finite cycles. */
public data class PricingPhase(
    val price: Price,
    val billingPeriod: String,
    val recurrence: Recurrence,
    val cycleCount: Int,
) {
    init {
        val period = java.time.Period.parse(billingPeriod)
        require(!period.isNegative && !period.isZero)
        require(cycleCount >= 0)
        require(recurrence != Recurrence.FINITE || cycleCount > 0)
    }
}

/** Pricing phases retain Play's order, including trials and introductory prices. */
public data class SubscriptionOffer(
    val token: String,
    val basePlanId: String,
    val offerId: String?,
    val tags: List<String>,
    val pricingPhases: List<PricingPhase>,
) {
    init {
        require(token.isNotBlank() && basePlanId.isNotBlank())
        require(pricingPhases.isNotEmpty())
    }
}

/** Store-localized catalog data, independent of the Billing SDK. */
public sealed interface Product {
    public val definition: ProductDefinition
    public val title: String
    public val description: String
}

public data class OneTimeProduct(
    override val definition: ProductDefinition,
    override val title: String,
    override val description: String,
    val offers: List<OneTimeOffer>,
) : Product

public data class SubscriptionProduct(
    override val definition: ProductDefinition,
    override val title: String,
    override val description: String,
    val offers: List<SubscriptionOffer>,
) : Product

/** Missing, ineligible and unsupported products are reported explicitly, never silently omitted. */
public data class Catalog(
    val products: List<Product> = emptyList(),
    val unavailableProductIds: Set<String> = emptySet(),
)

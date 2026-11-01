package com.tukonnichiwa.purchasekit.internal

import com.tukonnichiwa.purchasekit.model.*

/** Shared eligibility checks for local verifiers; does not establish receipt authenticity. */
internal class LocalPurchasePolicy(
    private val expectedPackageName: String,
    products: List<ProductDefinition>,
) {
    private val allowedProductIds = products.map { it.id }.toSet()

    init {
        require(expectedPackageName.isNotBlank()) { "Expected package name is required." }
        require(products.isNotEmpty() && allowedProductIds.size == products.size) {
            "Configure a non-empty list of unique products."
        }
        require(products.none { it.type == ProductType.CONSUMABLE }) {
            "Consumables require a verifier that durably delivers purchases before finalization."
        }
    }

    fun rejection(purchase: PurchaseRecord): VerificationResult.Rejected? = when {
        purchase.state != PurchaseState.PURCHASED || purchase.suspended ->
            VerificationResult.Rejected("Purchase is not eligible for access.")
        purchase.packageName != expectedPackageName || purchase.productIds.any { it !in allowedProductIds } ->
            VerificationResult.Rejected("Purchase package or product does not match configuration.")
        else -> null
    }
}

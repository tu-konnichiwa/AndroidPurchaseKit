package com.tukonnichiwa.purchasekit

import com.tukonnichiwa.purchasekit.model.ProductDefinition

/**
 * Catalog and bounded waits. Construction rejects duplicate IDs and nonpositive timeouts.
 * Timeouts are milliseconds. [operationTimeoutMillis] bounds each SDK request and
 * [verificationTimeoutMillis] bounds each verifier call. [checkoutTimeoutMillis] releases the
 * launch guard after a missing callback; it never dismisses checkout or cancels a payment.
 */
public class PurchaseKitConfig(
    products: List<ProductDefinition>,
    public val operationTimeoutMillis: Long = 30_000,
    public val verificationTimeoutMillis: Long = 30_000,
    public val checkoutTimeoutMillis: Long = 300_000,
) {
    public val products: List<ProductDefinition> = products.toList()

    init {
        require(products.isNotEmpty()) { "Configure at least one product." }
        require(products.map { it.id }.distinct().size == products.size) { "Duplicate product IDs." }
        require(operationTimeoutMillis > 0 && verificationTimeoutMillis > 0 && checkoutTimeoutMillis > 0)
    }
}

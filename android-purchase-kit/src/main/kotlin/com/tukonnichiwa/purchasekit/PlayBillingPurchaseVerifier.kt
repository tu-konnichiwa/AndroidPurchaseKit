package com.tukonnichiwa.purchasekit

import com.tukonnichiwa.purchasekit.internal.LocalPurchasePolicy
import com.tukonnichiwa.purchasekit.model.*

/**
 * Explicitly trusts Play Billing observations when no backend or billing public key is available.
 * Checks the configured package, product allowlist, PURCHASED state and suspension flag, then
 * approves local access and client acknowledgement. Supports subscriptions and non-consumables.
 * Consumables require durable delivery and are rejected at configuration time.
 *
 * This does not verify signatures or receipt authenticity, account ownership, current expiry,
 * refunds or replay. Even fabricated records can pass these local checks. Use only observations
 * delivered through PurchaseKit; restore on foreground entry to reconcile Play ownership.
 * Prefer ClientSidePurchaseVerifier when a public key is available, or a backend verifier for
 * stronger guarantees. This mode is never selected automatically when signature verification fails.
 */
public class PlayBillingPurchaseVerifier(
    expectedPackageName: String,
    products: List<ProductDefinition>,
) : PurchaseVerifier {
    private val policy = LocalPurchasePolicy(expectedPackageName, products)

    override suspend fun verifyAndGrant(purchase: PurchaseRecord): VerificationResult =
        policy.rejection(purchase) ?: VerificationResult.Granted(Finalization.CLIENT)
}

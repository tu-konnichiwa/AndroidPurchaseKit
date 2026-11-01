package com.tukonnichiwa.purchasekit.model

/** Pending transactions must never be verified, granted, acknowledged or consumed. */
public enum class PurchaseState { PENDING, PURCHASED }

/** Original Play receipt and signature. Sensitive data; never log or persist without protection. */
public data class SignedPurchaseData(val originalJson: String, val signature: String) {
    override fun toString(): String = "SignedPurchaseData(originalJson=<redacted>, signature=<redacted>)"
}

/**
 * An uncommitted prepaid top-up or subscription replacement. The existing purchase remains
 * independently valid. Never verify, grant, acknowledge or consume [token] until Play reports it
 * as a top-level PURCHASED record. This update has no confirmed quantity or purchase time yet.
 */
public data class PendingPurchaseUpdate(val productIds: List<String>, val token: String) {
    init {
        require(productIds.isNotEmpty() && productIds.all { it.isNotBlank() })
        require(token.isNotBlank())
    }

    override fun toString(): String = "PendingPurchaseUpdate(productIds=$productIds, token=<redacted>)"
}

/**
 * A store observation, not proof of entitlement. Tokens are sensitive; [toString] redacts them.
 * [quantity] must be honored by the backend when delivering consumables.
 */
public data class PurchaseRecord(
    val productIds: List<String>,
    val token: String,
    val packageName: String,
    val state: PurchaseState,
    val quantity: Int,
    val purchaseTimeMillis: Long,
    val acknowledged: Boolean,
    val autoRenewing: Boolean,
    val suspended: Boolean = false,
    val orderId: String? = null,
    val obfuscatedAccountId: String? = null,
    val obfuscatedProfileId: String? = null,
    /** A pending change to this existing purchase; it does not replace the parent's state. */
    val pendingUpdate: PendingPurchaseUpdate? = null,
    /** Optional signed receipt for local signature verification; absent on manually created records. */
    val signedData: SignedPurchaseData? = null,
) {
    init {
        require(productIds.isNotEmpty() && productIds.all { it.isNotBlank() })
        require(token.isNotBlank() && packageName.isNotBlank())
        require(quantity > 0 && purchaseTimeMillis >= 0)
    }

    override fun toString(): String =
        "PurchaseRecord(productIds=$productIds, token=<redacted>, state=$state, quantity=$quantity)"
}

/** Client finalization happens only after the verifier approves the purchase and any delivery. */
public enum class Finalization { CLIENT, BACKEND }

public sealed interface VerificationResult {
    /** The verifier approved access; consumable delivery must already be durably committed. */
    public data class Granted(val finalization: Finalization = Finalization.CLIENT) : VerificationResult
    /** Authoritative rejection. This removes any previously observed local entitlement. */
    public data class Rejected(val reason: String) : VerificationResult
    /** Temporary verification failure; foreground restore retries it. */
    public data class RetryLater(val reason: String) : VerificationResult
}

/**
 * Prefer an authenticated backend: validate package, product, token, account binding,
 * purchase state and subscription expiry; persist delivery idempotently by token before Granted.
 * Apps without a backend can explicitly use ClientSidePurchaseVerifier (with a public key) or
 * PlayBillingPurchaseVerifier (trusting store observations) for subscriptions/non-consumables.
 * Invoked serially; suspending I/O must be cancellation-cooperative and must not block Main.
 * Repeated calls are expected, including after process death and during every restore.
 */
public fun interface PurchaseVerifier {
    public suspend fun verifyAndGrant(purchase: PurchaseRecord): VerificationResult
}

public enum class VerificationStatus { PENDING, RETRY_REQUIRED, REJECTED, GRANTED, SUSPENDED }

/** Local observation. [error] can describe failed verification or unfinished finalization. */
public data class OwnedPurchase(
    val purchase: PurchaseRecord,
    val verification: VerificationStatus,
    val error: PurchaseError? = null,
)

/** Google Play decides whether a replacement mode is valid for the selected plans. */
public enum class ReplacementMode {
    WITH_TIME_PRORATION, CHARGE_PRORATED_PRICE, WITHOUT_PRORATION, CHARGE_FULL_PRICE, DEFERRED,
}

public data class SubscriptionReplacement(
    val oldProductId: String,
    val oldPurchaseToken: String,
    val mode: ReplacementMode,
) {
    init { require(oldProductId.isNotBlank() && oldPurchaseToken.isNotBlank()) }
    override fun toString(): String = "SubscriptionReplacement(oldProductId=$oldProductId, oldPurchaseToken=<redacted>, mode=$mode)"
}

/**
 * Select an offer token from the current catalog. Omitting it is allowed only for one eligible offer.
 * Obfuscated IDs must be nonblank, at most 64 characters, and must not contain raw personal data.
 */
public data class PurchaseRequest(
    val productId: String,
    val offerToken: String? = null,
    val obfuscatedAccountId: String? = null,
    val obfuscatedProfileId: String? = null,
    val replacement: SubscriptionReplacement? = null,
    val personalizedPrice: Boolean = false,
    /** Optional analytics source (for example onboarding). Never include personal data or secrets. */
    val trackingContext: String? = null,
) {
    init {
        require(productId.isNotBlank())
        require(offerToken == null || offerToken.isNotBlank())
        require(trackingContext == null || (trackingContext.isNotBlank() && trackingContext.length <= 256))
        listOf(obfuscatedAccountId, obfuscatedProfileId).forEach {
            require(it == null || (it.isNotBlank() && it.length <= 64))
        }
    }
    override fun toString(): String = "PurchaseRequest(productId=$productId, identifiers=<redacted>)"
}

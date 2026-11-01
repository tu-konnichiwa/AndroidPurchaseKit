package com.tukonnichiwa.purchasekit

import com.android.billingclient.api.Purchase
import com.tukonnichiwa.purchasekit.internal.LocalPurchasePolicy
import com.tukonnichiwa.purchasekit.internal.play.toRecord
import com.tukonnichiwa.purchasekit.model.*
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException

/**
 * Opt-in verification for apps without a backend. Checks Play's SHA1withRSA receipt signature,
 * expected package, configured products and agreement between the signed receipt and observation.
 * Use the Base64 licensing/billing public key from Play Console, not an app-signing certificate.
 *
 * Supports subscriptions and non-consumables. Consumables require durable, idempotent delivery
 * and are rejected at configuration time. No persistence, account authorization, current expiry,
 * refund lookup or replay protection is provided. A valid receipt can be old; call foreground
 * restore to reconcile Play ownership. Backend verification provides stronger guarantees.
 * Invalid configuration throws IllegalArgumentException; invalid receipts return Rejected.
 */
public class ClientSidePurchaseVerifier(
    base64PublicKey: String,
    expectedPackageName: String,
    products: List<ProductDefinition>,
) : PurchaseVerifier {
    private val policy = LocalPurchasePolicy(expectedPackageName, products)
    private val publicKey = try {
        val encoded = Base64.getDecoder().decode(base64PublicKey.filterNot(Char::isWhitespace))
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encoded))
    } catch (_: GeneralSecurityException) {
        throw IllegalArgumentException("A valid Base64 RSA Play billing public key is required.")
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("A valid Base64 RSA Play billing public key is required.")
    }

    override suspend fun verifyAndGrant(purchase: PurchaseRecord): VerificationResult =
        withContext(Dispatchers.Default) {
            policy.rejection(purchase)?.let { return@withContext it }
            val receipt = purchase.signedData
                ?: return@withContext rejected("Signed purchase data is missing.")
            try {
                val signature = Signature.getInstance("SHA1withRSA")
                signature.initVerify(publicKey)
                signature.update(receipt.originalJson.toByteArray(Charsets.UTF_8))
                val bytes = Base64.getDecoder().decode(receipt.signature.filterNot(Char::isWhitespace))
                if (!signature.verify(bytes)) {
                    return@withContext rejected("Purchase signature is invalid.")
                }
                // Reuse the SDK boundary mapper so every field used by the engine is bound to
                // the signed payload, including quantity, pending changes and account identifiers.
                if (Purchase(receipt.originalJson, receipt.signature).toRecord() != purchase) {
                    return@withContext rejected("Purchase does not match its signed data.")
                }
                VerificationResult.Granted(Finalization.CLIENT)
            } catch (_: GeneralSecurityException) {
                rejected("Purchase signature could not be verified.")
            } catch (_: IllegalArgumentException) {
                rejected("Signed purchase data is invalid.")
            } catch (_: JSONException) {
                rejected("Signed purchase data is invalid.")
            }
        }

    private fun rejected(reason: String): VerificationResult.Rejected = VerificationResult.Rejected(reason)
}

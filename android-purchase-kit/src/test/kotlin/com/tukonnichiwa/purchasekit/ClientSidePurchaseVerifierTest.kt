package com.tukonnichiwa.purchasekit

import android.text.TextUtils
import com.android.billingclient.api.Purchase
import com.tukonnichiwa.purchasekit.internal.play.toRecord
import com.tukonnichiwa.purchasekit.model.*
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class ClientSidePurchaseVerifierTest {
    private val products = listOf(
        ProductDefinition("premium", ProductType.SUBSCRIPTION, "access"),
        ProductDefinition("lifetime", ProductType.NON_CONSUMABLE, "access"),
    )
    private val verifier = ClientSidePurchaseVerifier(publicKey, "example.app", products)

    @Test fun validSignedSubscriptionAndLifetimeAllowClientFinalization() = check {
        for (product in listOf("premium", "lifetime")) {
            assertEquals(VerificationResult.Granted(Finalization.CLIENT),
                verifier.verifyAndGrant(record(json(product = product))))
        }
    }

    @Test fun tamperedPayloadAndWrongSigningKeyAreRejected() = check {
        val original = record()
        val tampered = original.signedData!!.copy(originalJson = json(token = "tampered"))
        assertRejected(original.copy(token = "tampered", signedData = tampered))
        val otherKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val otherVerifier = ClientSidePurchaseVerifier(
            Base64.getEncoder().encodeToString(otherKey.public.encoded), "example.app", products,
        )
        assertTrue(otherVerifier.verifyAndGrant(original) is VerificationResult.Rejected)
    }

    @Test fun missingEmptyAndMalformedProofNeverGrantAccess() = check {
        val original = record()
        assertRejected(original.copy(signedData = null))
        for (signature in listOf("", "%%%", Base64.getEncoder().encodeToString(byteArrayOf(1)))) {
            assertRejected(original.copy(signedData = original.signedData!!.copy(signature = signature)))
        }
        assertRejected(original.copy(signedData = signed("not-json")))
        assertRejected(original.copy(signedData = signed("{}")))
    }

    @Test fun validSignatureDoesNotAuthorizeAnotherPackageOrProduct() = check {
        assertRejected(record(json(packageName = "other.app")))
        assertRejected(record(json(product = "unknown")))
        assertRejected(record(json().replace("[\"premium\"]", "[\"premium\",\"unknown\"]")))
    }

    @Test fun pendingAndSuspendedPurchasesNeverGrantAccess() = check {
        assertRejected(record(json().replace("\"purchaseState\":0", "\"purchaseState\":4")))
        assertRejected(record().copy(suspended = true))
    }

    @Test fun allMappedFieldsAreBoundToTheSignedReceipt() = check {
        val original = record()
        val modified = listOf(
            original.copy(productIds = listOf("lifetime")),
            original.copy(token = "another-token"),
            original.copy(quantity = 2),
            original.copy(purchaseTimeMillis = 99),
            original.copy(acknowledged = true),
            original.copy(autoRenewing = false),
            original.copy(orderId = "another-order"),
            original.copy(obfuscatedAccountId = "another-account"),
            original.copy(obfuscatedProfileId = "another-profile"),
            original.copy(pendingUpdate = PendingPurchaseUpdate(listOf("premium"), "new-token")),
        )
        modified.forEach { assertRejected(it) }
    }

    @Test fun malformedKeysAndUnsupportedConfigurationFailFast() {
        for (key in listOf("", "%%%", Base64.getEncoder().encodeToString(byteArrayOf(1)))) {
            assertThrows(IllegalArgumentException::class.java) {
                ClientSidePurchaseVerifier(key, "example.app", products)
            }
        }
        for (definitions in listOf(emptyList(), products + products.first(),
            listOf(ProductDefinition("coins", ProductType.CONSUMABLE)))) {
            assertThrows(IllegalArgumentException::class.java) {
                ClientSidePurchaseVerifier(publicKey, "example.app", definitions)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClientSidePurchaseVerifier(publicKey, "", products)
        }
    }

    @Test fun receiptMappingPreservesOriginalBytesAndRedactsSecrets() = check {
        val original = record()
        assertEquals(json(), original.signedData!!.originalJson)
        assertFalse(original.toString().contains("secret-token"))
        assertFalse(original.signedData.toString().contains("secret-token"))
        assertFalse(original.signedData.toString().contains(original.signedData!!.signature))
        val rejection = verifier.verifyAndGrant(original.copy(signedData = signed("secret-token")))
        assertFalse(rejection.toString().contains("secret-token"))
    }

    @Test fun callerCannotChangeProductAllowlistAfterConstruction() = check {
        val mutableProducts = products.toMutableList()
        val local = ClientSidePurchaseVerifier("\n$publicKey\n", "example.app", mutableProducts)
        mutableProducts.clear()
        assertTrue(local.verifyAndGrant(record()) is VerificationResult.Granted)
    }

    private suspend fun assertRejected(purchase: PurchaseRecord) {
        assertTrue(verifier.verifyAndGrant(purchase) is VerificationResult.Rejected)
    }

    private fun record(raw: String = json()): PurchaseRecord = signed(raw).let {
        Purchase(it.originalJson, it.signature).toRecord()
    }

    private fun signed(raw: String): SignedPurchaseData {
        val signer = Signature.getInstance("SHA1withRSA")
        signer.initSign(keyPair.private)
        signer.update(raw.toByteArray(Charsets.UTF_8))
        return SignedPurchaseData(raw, Base64.getEncoder().encodeToString(signer.sign()))
    }

    private fun json(product: String = "premium", packageName: String = "example.app", token: String = "secret-token") =
        """{ "productIds":["$product"],"packageName":"$packageName","token":"$token",
            "purchaseState":0,"purchaseTime":1234,"quantity":1,"acknowledged":false,
            "autoRenewing":true,"orderId":"order","obfuscatedAccountId":"account",
            "obfuscatedProfileId":"profile" }""".trimIndent()

    // Static SDK utility mocks are thread-local. Keep receipt parsing and local verification
    // on the same Default worker; the verifier's same-dispatcher context does not dispatch again.
    private fun check(block: suspend () -> Unit) = runBlocking(Dispatchers.Default) {
        Mockito.mockStatic(TextUtils::class.java).use { mock ->
            mock.`when`<Boolean> { TextUtils.isEmpty(Mockito.any()) }.thenAnswer {
                it.getArgument<CharSequence?>(0).isNullOrEmpty()
            }
            block()
        }
    }

    companion object {
        private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
    }
}

# AndroidPurchaseKit Usage Guide

This guide covers the API in the `android-purchase-kit` module, under the
`com.tukonnichiwa.purchasekit` package. The code snippets are examples for your
consuming application. This repository contains only the Android library.

## 1. Add the library to your application

Android API 26 or newer is required. For installation through JitPack, follow the
[repository and dependency setup](../README.md#install-with-jitpack). Use a pushed
tag whose JitPack build has succeeded. The published artifact includes dependency
metadata; no catalog entries need to be copied into the consuming build.

For local development, if the module is already part of your Gradle
build, add this dependency to your application's `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":android-purchase-kit"))
}
```

If the library checkout sits next to your application, add the following to the
**consuming application's** `settings.gradle.kts`:

```kotlin
include(":android-purchase-kit")
project(":android-purchase-kit").projectDir =
    file("../AndroidPurchaseKit/android-purchase-kit")
```

The consuming build must provide a compatible Android Library plugin and the
`libs` aliases used by the module. Merge the relevant entries from the
[version catalog](../gradle/libs.versions.toml) and review the
[module configuration](../android-purchase-kit/build.gradle.kts). Preserve your
application's existing catalog entries.

If you distribute the raw AAR,
you must declare its runtime dependencies separately because an AAR alone does
not include dependency metadata.

## 2. Configure products and entitlements

`ProductDefinition.id` must match the Product ID configured in Play Console.
An `entitlement` is an access key defined by your application; it can differ from
the Product ID.

| Type | Example | Entitlement |
|---|---|---|
| `NON_CONSUMABLE` | Remove ads purchase | `ad_free` |
| `SUBSCRIPTION` | Premium subscription | `premium_access` |
| `CONSUMABLE` | Pack of 100 coins | No persistent entitlement |

Multiple products can grant the same entitlement. For non-consumables and
subscriptions, omitting `entitlement` defaults it to the Product ID. For
consumables, it must be `null`; your backend owns the user's balance.

The examples below use these imports, in addition to any imports shown in each
snippet:

```kotlin
import com.tukonnichiwa.purchasekit.PurchaseKit
import com.tukonnichiwa.purchasekit.PurchaseKitConfig
import com.tukonnichiwa.purchasekit.model.*
```

## 3. Choose a PurchaseVerifier

`PurchaseVerifier` is required. Select the implementation explicitly:

| Available infrastructure | Implementation |
|---|---|
| Authenticated backend | Custom `PurchaseVerifier` (recommended) |
| Play Console billing RSA public key, no backend | `ClientSidePurchaseVerifier` |
| Neither a backend nor public key | `PlayBillingPurchaseVerifier` (trusts store observations) |

### Built-in client verification (no backend)

```kotlin
import com.tukonnichiwa.purchasekit.ClientSidePurchaseVerifier

val verifier = ClientSidePurchaseVerifier(
    base64PublicKey = playConsoleBillingPublicKey,
    expectedPackageName = context.packageName,
    products = config.products,
)
val kit = PurchaseKit.create(context, config, verifier) // Call on Main.
```

`config` is your `PurchaseKitConfig`; `playConsoleBillingPublicKey` is your app's
Base64 licensing/billing RSA public key from Play Console. Do not use the
app-signing certificate or embed any service-account credentials. The public key
is parsed once and malformed keys fail immediately with `IllegalArgumentException`.

This implementation supports `SUBSCRIPTION` and `NON_CONSUMABLE` products. It
verifies Play's SHA1withRSA receipt signature, the configured package and products,
and that the observed purchase matches the signed payload. Missing or invalid
proof is rejected. Valid purchased, non-suspended records return `Granted(CLIENT)`;
the engine acknowledges them. Pending changes are not independently granted.
`PurchaseRecord.signedData` carries the original JSON and signature; its string
representation is redacted, but the properties themselves remain sensitive.

Consumable configuration is rejected: use a custom verifier that commits delivery
idempotently before returning `Granted`. This local verifier does not implement
account ownership, replay prevention, current subscription expiry/refund checks
or persistent premium state. Signed historical receipts can remain valid after
ownership changes. Restore on foreground entry to reconcile Play observations;
backend verification offers stronger protection and remains recommended. See
[Google's security guidance](https://developer.android.com/google/play/billing/security).

### Built-in Play Billing trust mode (no backend or public key)

```kotlin
import com.tukonnichiwa.purchasekit.PlayBillingPurchaseVerifier

val verifier = PlayBillingPurchaseVerifier(
    expectedPackageName = context.packageName,
    products = config.products,
)
val kit = PurchaseKit.create(context, config, verifier) // Call on Main.
```

Use this only when you explicitly choose to trust the store observations delivered
through PurchaseKit. It checks package name, the product allowlist, `PURCHASED`
state and that the purchase is not suspended, then returns `Granted(CLIENT)`.
The engine grants the configured entitlement and acknowledges the purchase.
Pending/suspended purchases and package/product mismatches never grant access.
The existing parent purchase can retain access while a nested change is pending;
the pending token itself is not granted or acknowledged.

Only `SUBSCRIPTION` and `NON_CONSUMABLE` products are supported. Blank package
names, empty/duplicate products and any consumable configuration fail immediately
with `IllegalArgumentException`. Product configuration is copied at construction.

This implementation does not examine `signedData` or prove receipt authenticity;
a fabricated `PurchaseRecord` can pass its local checks. It does not validate
account ownership, current expiry/refunds or replay, and it does not persist
premium state. Reconcile ownership using foreground restore. Prefer the RSA
implementation when you have a key, or backend verification for stronger
guarantees. Signature verification never silently falls back to this mode.

### Custom backend verification

The library does not provide an endpoint, HTTP client or sign-in implementation.

Your backend must validate the token, package, product, purchase state, account
ownership and subscription validity. It must then persist the entitlement or
balance update idempotently: processing the same token again must not deliver the
reward twice. For consumables, the backend must verify and handle `quantity`.

The following example maps backend responses to verification results.
`PurchaseBackend` and `BackendDecision` are **application-defined example types**,
not AndroidPurchaseKit APIs. Your HTTP implementation maps actual server responses
to these values.

```kotlin
enum class BackendDecision {
    GRANTED_NEEDS_FINALIZATION,
    GRANTED_AND_FINALIZED,
    REJECTED,
    TEMPORARILY_UNAVAILABLE,
}

interface PurchaseBackend {
    // Return GRANTED only after verification and durable entitlement/balance delivery.
    suspend fun verifyAndGrant(purchase: PurchaseRecord): BackendDecision
}

fun createVerifier(backend: PurchaseBackend): PurchaseVerifier =
    PurchaseVerifier { purchase ->
        when (backend.verifyAndGrant(purchase)) {
            BackendDecision.GRANTED_NEEDS_FINALIZATION ->
                VerificationResult.Granted(Finalization.CLIENT)
            BackendDecision.GRANTED_AND_FINALIZED ->
                VerificationResult.Granted(Finalization.BACKEND)
            BackendDecision.REJECTED ->
                VerificationResult.Rejected("The backend rejected the purchase.")
            BackendDecision.TEMPORARILY_UNAVAILABLE ->
                VerificationResult.RetryLater("Verification is temporarily unavailable.")
        }
    }
```

| Verifier result | Library behavior |
|---|---|
| `Granted(CLIENT)` | Acknowledges non-consumables/subscriptions or consumes consumables after the backend has persisted delivery |
| `Granted(BACKEND)` | Skips client finalization; the backend must have acknowledged or consumed the purchase before returning this result |
| `Rejected` | Does not finalize; removes the local entitlement associated with the purchase |
| `RetryLater` | Marks verification for retry; restoring purchases calls the verifier again |

The verifier can be called multiple times for the same token, including after a
process restart. Use asynchronous I/O or move blocking I/O to an appropriate
dispatcher. Do not call `start()`, `restorePurchases()` or `refreshCatalog()` from
inside the verifier, because purchase processing is serialized.

## 4. Create and start an instance

Create one instance for the current account session in your application's
composition root, then inject it into the components that need it. `create()` and
`close()` must run on the Main thread. Suspend methods can be called from any
dispatcher.

```kotlin
import android.content.Context

// Call on Main. The application supplies the backend implementation.
fun createPurchaseKit(context: Context, backend: PurchaseBackend): PurchaseKit =
    PurchaseKit.create(
        context = context.applicationContext,
        config = PurchaseKitConfig(
            products = listOf(
                ProductDefinition("remove_ads", ProductType.NON_CONSUMABLE, "ad_free"),
                ProductDefinition("premium", ProductType.SUBSCRIPTION, "premium_access"),
                ProductDefinition("coins_100", ProductType.CONSUMABLE),
            ),
            operationTimeoutMillis = 30_000,
            verificationTimeoutMillis = 30_000,
            checkoutTimeoutMillis = 300_000,
        ),
        verifier = createVerifier(backend),
    )
```

From a coroutine owned by your application, call:

```kotlin
val result: PurchaseKitResult<PurchaseKitState> = kit.start()
```

`start()` connects to Billing, loads the catalog and restores purchases. If the
connection succeeds but the catalog query fails, the library still attempts to
restore purchases. Even when the result is `Success`, inspect `state.purchases`
for individual verification or finalization errors.

## 5. Observe state and events

Collect the two flows in separate coroutines. This helper accepts an
application-owned scope and callbacks from your presentation layer:

```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

fun observePurchases(
    scope: CoroutineScope,
    kit: PurchaseKit,
    onState: (PurchaseKitState) -> Unit,
    onEvent: (PurchaseEvent) -> Unit,
): Job = scope.launch {
    launch { kit.state.collect { onState(it) } }
    launch { kit.events.collect { onEvent(it) } }
}
```

For UI observation, use a scope tied to the screen's lifecycle and cancel the job
when observation is no longer needed. Purchase processing continues in the
`PurchaseKit` instance's own scope. Your presentation layer should map state to
display values before the View renders them.

| Data | Usage |
|---|---|
| `state.connection` | Display connection status |
| `state.catalog` | Read available products, prices and offers |
| `state.purchases` | Observe pending payments, verification and per-purchase finalization errors |
| `state.entitlements` | Check local access, for example `"premium_access" in state.entitlements` |
| `events` | Receive transient UI notifications without replay or durable storage |

Use `PurchaseEvent.Completed` to show a completion message or request an updated
balance from your backend. Do not credit coins in the collector: events can be
missed, and the backend must persist delivery before the verifier returns `Granted`.

## 6. Load products and select offers

Call `kit.refreshCatalog()` to update prices and offers. Product IDs without an
available product or supported offer appear in `Catalog.unavailableProductIds`.

Display `Price.formatted` as supplied by Play. `amountMicros` is measured in
millionths of a currency unit; for example, `1_990_000` represents `1.99` currency
units.

Subscription `pricingPhases` preserve the store's order, including trials,
introductory prices and recurring prices when present. The first phase is not
necessarily the renewal price.

This example selects a base plan without a promotional offer. It returns `null`
when there is no unique match so the presentation layer can display an unavailable
state:

```kotlin
fun selectBasePlan(
    catalog: Catalog,
    productId: String,
    basePlanId: String,
): SubscriptionOffer? = catalog.products
    .filterIsInstance<SubscriptionProduct>()
    .singleOrNull { it.definition.id == productId }
    ?.offers
    ?.singleOrNull { it.basePlanId == basePlanId && it.offerId == null }
```

Use the `basePlanId` configured in Play Console. If the user selects a trial or
promotional offer, pass that specific offer's token in the purchase request.

## 7. Buy one-time products and consumables

```kotlin
import android.app.Activity

suspend fun buyOneTimeOffer(
    kit: PurchaseKit,
    activity: Activity,
    productId: String,
    selectedOffer: OneTimeOffer,
    accountHash: String,
): PurchaseKitResult<Unit> = kit.launchPurchase(
    activity = activity,
    request = PurchaseRequest(
        productId = productId,
        offerToken = selectedOffer.token,
        obfuscatedAccountId = accountHash,
    ),
)
```

Take `selectedOffer` from the user's selection in `OneTimeProduct.offers`. The
same call works for `remove_ads` and `coins_100`; the configured `ProductDefinition`
type determines acknowledgement or consumption after verification. No manual
consume call is needed.

You can omit `offerToken` when exactly one eligible offer is available. If there
are multiple offers, select a specific token. The library fetches fresh
ProductDetails before checkout and returns an error if the selected token is no
longer available.

`obfuscatedAccountId` and `obfuscatedProfileId` accept obfuscated identifiers of up
to 64 characters. Supply stable values that your backend can use for account
binding; do not pass raw email addresses or phone numbers.

**`Success(Unit)` from `launchPurchase()` means checkout was launched.** Observe
state/events to determine payment, verification and finalization outcomes. Do not
unlock access or deliver rewards based solely on this method returning success.

## 8. Buy, change and manage subscriptions

Purchase the selected offer:

```kotlin
import android.app.Activity

suspend fun subscribe(
    kit: PurchaseKit,
    activity: Activity,
    selectedOffer: SubscriptionOffer,
    accountHash: String,
): PurchaseKitResult<Unit> = kit.launchPurchase(
    activity,
    PurchaseRequest(
        productId = "premium",
        offerToken = selectedOffer.token,
        obfuscatedAccountId = accountHash,
    ),
)
```

Replace an existing subscription with another offer:

```kotlin
import android.app.Activity

suspend fun changePlan(
    kit: PurchaseKit,
    activity: Activity,
    oldProductId: String,
    oldPurchaseToken: String,
    newProductId: String,
    selectedOffer: SubscriptionOffer,
    accountHash: String,
): PurchaseKitResult<Unit> = kit.launchPurchase(
    activity,
    PurchaseRequest(
        productId = newProductId,
        offerToken = selectedOffer.token,
        obfuscatedAccountId = accountHash,
        replacement = SubscriptionReplacement(
            oldProductId = oldProductId,
            oldPurchaseToken = oldPurchaseToken,
            mode = ReplacementMode.DEFERRED,
        ),
    ),
)
```

Obtain the old product ID and token from the verified purchase in
`state.purchases`, selecting the subscription the user intends to replace.
`DEFERRED` is an example; choose the mode according to your application's plan
change policy. Play checks whether the mode is valid for the selected plans.
Your backend must handle linked tokens and access granted by the replaced plan.

When a purchased subscription callback arrives, the library reconciles ownership
to remove retired tokens. If that query fails, it reverifies known subscriptions
so the backend can reject old access. Foreground restore is still needed to
recover changes missed while the application was not connected.

Open subscription management from a coroutine:

```kotlin
val managementResult = kit.openSubscriptionManagement(activity, productId = "premium")
// Omit productId to open the account's subscription list.
```

Inspect `managementResult` to handle a failure to open subscription management.

## 9. Restore purchases and handle pending payments

Call `kit.restorePurchases()` when your application returns to the foreground,
when the user selects Restore purchases, or when verification/finalization needs
to be retried. Your application connects lifecycle events to this call; the
library does not register a lifecycle observer automatically.

```kotlin
val restoreResult: PurchaseKitResult<List<OwnedPurchase>> = kit.restorePurchases()
```

| `OwnedPurchase.verification` | Meaning |
|---|---|
| `PENDING` | Payment is incomplete; no verification, entitlement grant or finalization occurs |
| `RETRY_REQUIRED` | Verification temporarily failed; restore to retry |
| `REJECTED` | The selected verifier rejected the purchase |
| `GRANTED` | The selected verifier approved access; inspect `error` for unfinished finalization |
| `SUSPENDED` | The subscription is suspended; no local access is granted |

A successful `restoreResult` confirms that the store query succeeded. Inspect each
item's `verification` and `error` as well. Successfully finalized consumables are
removed from the owned purchase list; retrieve their delivered balance from your
backend.

For a pending prepaid top-up or subscription change, the existing purchase remains
in the list with its own verification status. The new transaction is available as
`purchase.pendingUpdate`, without replacing the parent's entitlement:

```kotlin
val pendingChanges: List<PendingPurchaseUpdate> = kit.state.value.purchases
    .mapNotNull { it.purchase.pendingUpdate }
```

Each update contains `token` and `productIds`. Do not verify or finalize that token
yourself: the library processes it only when Play reports it as a top-level
`PURCHASED` record. A restore that no longer contains the pending change clears it,
while keeping the old purchase's independently verified access.

`PurchaseEvent.PendingChange` provides a transient notification of a newly observed
change; duplicate observations do not emit it again. Include this case in exhaustive
`when` expressions over events. Render current pending changes from state so that
recreating a screen does not depend on receiving an earlier event.

A failed query preserves the previous snapshot. A successful empty query removes
local access. A temporary verifier failure sets `RETRY_REQUIRED` and removes that
purchase's local entitlement until verification succeeds again.

## 10. Handle errors and timeouts

Map `PurchaseError.code` to messages in your presentation layer. `message` is a
diagnostic string, not localized UI text. A checkout error can appear in both the
method result and `events`; choose one notification path to avoid duplicate messages.

| Error code | Application response |
|---|---|
| `CANCELLED` | The user cancelled; return to the previous screen |
| `BUSY` | Checkout is active; wait for a callback or timeout |
| `ALREADY_OWNED` | Restore to synchronize existing ownership |
| `NETWORK`, `DISCONNECTED`, `TIMEOUT` | Allow retry/restore when appropriate; also inspect `retryable` |
| `PRODUCT_UNAVAILABLE`, `INVALID_REQUEST` | Refresh the catalog and check the Product ID, offer or request |
| `VERIFICATION_UNAVAILABLE` | Check backend availability, then restore to retry |
| `VERIFICATION_FAILED` | The backend rejected the purchase; follow your application's support policy |
| `CLOSED` | The instance was closed; create one for the current session |

All timeout values are in milliseconds:

| Configuration | Default | Scope |
|---|---|---|
| `operationTimeoutMillis` | `30_000` | Each SDK request |
| `verificationTimeoutMillis` | `30_000` | Each verifier call |
| `checkoutTimeoutMillis` | `300_000` | Waiting for a callback before allowing another launch |

A checkout timeout does not dismiss Play's UI or prove that payment failed. Late
callbacks are still processed; restore to reconcile the outcome. Cancelling a
coroutine after checkout has launched does not cancel payment either.

## 11. Manage lifecycle and account changes

- Keep one instance for the same account session and inject it where needed.
- Reuse that instance across purchase screens.
- On sign-out or account change, close the old instance on Main and create a new
  one with a verifier bound to the correct backend session. Reconnect collectors
  to the new instance.
- `kit.close()` cancels operations, ends the connection and clears local access.
  Calling it repeatedly is safe.
- Keep a shared instance alive when an Activity is destroyed for a configuration
  change, such as screen rotation.

Local entitlements help present access state in the application. Your backend
remains responsible for protected access, refunds, expiry and durable balances.

## 12. Build and validate the integration

Run from the library repository root:

```sh
./gradlew :android-purchase-kit:build
```

If your shell cannot locate Java, for example on macOS with Android Studio installed:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :android-purchase-kit:build
```

Unit tests exercise policy and adapters on the JVM. Validate actual purchases
using your consuming application, a Play license tester account and your backend.
See the [device validation checklist](readiness.md#device-validation-matrix) for
integration scenarios.

Current limits include one product per checkout, no client query restoration of
consumed or expired purchase history, and no support for rental, preorder,
installment, add-on checkout or in-app messaging. See the
[README](../README.md#limits) and [architecture documentation](architecture.md).

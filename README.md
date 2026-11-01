# AndroidPurchaseKit

A Kotlin Android library for Google Play purchases. A single `android-purchase-kit`
module provides consumable and non-consumable products, subscriptions, catalog
queries, checkout, restore, and verification-before-finalization.

**Usage guide:** [Integrating and using AndroidPurchaseKit](docs/usage.md).

## Requirements

- Android API 26 or newer; compile SDK 37 for this repository.
- Kotlin and coroutines. Android Gradle Plugin 9.3.3 supplies built-in Kotlin support.
- The checked-in Gradle 9.5.0 wrapper and its JDK 25 daemon configuration.
- Google Play Billing 9.1.0 and coroutines 1.11.0 (managed by the version catalog).
- Play Console products and an authenticated backend implementing `PurchaseVerifier`.

There is no application or sample module or UI framework. JitPack publishing is
configured for the release AAR and sources; Maven Central publishing is not configured.

## Install with JitPack

Add the repository to the consuming application's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.tu-konnichiwa") }
        }
    }
}
```

Then add the dependency to the application's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.tu-konnichiwa:AndroidPurchaseKit:<tag>")
}
```

Replace `<tag>` with a pushed Git tag, starting with `1.0.0`, after its JitPack build
succeeds. This example does not assert that a release has already been published.
The Maven publication includes transitive dependencies, so the consuming app does
not need this repository's version catalog. See [JitPack release instructions](docs/publishing.md).

## Local integration

When this library module is included in your consuming build:

```kotlin
// Consuming application's build.gradle.kts
implementation(project(":android-purchase-kit"))
```

For an external checkout, add the module directory in the **consuming** project's
settings, and use the same dependency above:

```kotlin
include(":android-purchase-kit")
project(":android-purchase-kit").projectDir = file("../AndroidPurchaseKit/android-purchase-kit")
```

That build must provide compatible `libs` catalog aliases from this repository
and the Android library plugin. Copy the catalog entries when integrating this
way. A raw AAR does not carry dependency metadata; use the JitPack dependency or
consume the module to retain dependency resolution.

## Quick start

Create one instance on Main for the current signed-in user. Inject it into the
features that need it. `backendVerifier` below is your implementation of the
contract in the next section, not a permissive local verification function.

```kotlin
import com.tukonnichiwa.purchasekit.PurchaseKit
import com.tukonnichiwa.purchasekit.PurchaseKitConfig
import com.tukonnichiwa.purchasekit.model.*

val kit = PurchaseKit.create(
    context = applicationContext,
    config = PurchaseKitConfig(
        products = listOf(
            ProductDefinition("remove_ads", ProductType.NON_CONSUMABLE, "ad_free"),
            ProductDefinition("premium", ProductType.SUBSCRIPTION, "premium_access"),
            ProductDefinition("coins_100", ProductType.CONSUMABLE),
        ),
    ),
    verifier = backendVerifier,
)

// From a host-owned coroutine:
when (val result = kit.start()) {
    is PurchaseKitResult.Success -> renderCatalog(result.value.catalog)
    is PurchaseKitResult.Failure -> showBillingError(result.error)
}

// Observe using a lifecycle-aware coroutine in your consuming application.
kit.state.collect { state ->
    renderPremiumAccess("premium_access" in state.entitlements)
    renderPurchases(state.purchases)
}
```

The rendering and error functions above belong to the host application. This library
has no lifecycle or UI dependency. Start state/event collectors in separate host
coroutines; `collect` is a long-running operation.

## Verification and delivery

Implement `PurchaseVerifier.verifyAndGrant(purchase)` using your authenticated
backend. It must:

1. Validate the token with Google, expected package/product, purchase state,
   subscription validity and the current user's account binding.
2. Store the delivery atomically and idempotently using the token. For consumables,
   apply the verified quantity exactly once to the durable balance.
3. Return `Granted` only after that commit. Return `Rejected` for an authoritative
   rejection or `RetryLater` for a temporary failure.

Return `Granted(Finalization.BACKEND)` only if the backend has also acknowledged
or consumed the purchase. Otherwise return `Granted(Finalization.CLIENT)` and the
library will acknowledge non-consumables/subscriptions or consume consumables.
Backend finalization is preferable when fulfillment must continue while the app
is offline. Keep credentials and Google service-account access on your server.

The verifier can be called repeatedly for the same token. It must be suspending,
cancellation-cooperative, and safe to retry. Move blocking I/O off Main. Do not call
PurchaseKit methods from inside the verifier; reconciliation is serialized.

`PurchaseEvent.Completed` is a UI notification after successful finalization.
**Never credit consumable balances from this event.** Events are transient and may
be missed when there is no collector, while the backend delivery is durable.

See Google's [purchase integration](https://developer.android.com/google/play/billing/integrate)
and [verification guidance](https://developer.android.com/google/play/billing/security).

## Buying a product

```kotlin
val result = kit.launchPurchase(
    activity,
    PurchaseRequest(
        productId = "remove_ads",
        obfuscatedAccountId = stableAccountHash,
    ),
)
```

`Success(Unit)` means Google Play accepted the checkout launch. Payment completion,
user cancellation, pending payment and verification failure arrive later through
`state`/`events`. Caller cancellation after launch cannot cancel payment.

Offer selection is explicit if more than one offer is available:

```kotlin
val product = kit.state.value.catalog.products
    .filterIsInstance<SubscriptionProduct>()
    .first { it.definition.id == "premium" }
val offer = product.offers.first { it.basePlanId == "monthly" && it.offerId == null }

kit.launchPurchase(activity, PurchaseRequest("premium", offerToken = offer.token))
```

Use safe selection and an unavailable state in production UI; an offer can disappear
between display and launch. The library fetches fresh product details and rejects
missing/ambiguous offers. It never silently switches the user's selected offer.
`PricingPhase` retains trial, introductory and recurring prices in store order.
Use Play's formatted prices; amounts are integer micro-units, not floating point.
`personalizedPrice` in `PurchaseRequest` sets the store's personalized-price flag.

## Changing and managing subscriptions

```kotlin
kit.launchPurchase(
    activity,
    PurchaseRequest(
        productId = "premium",
        offerToken = annualOffer.token,
        replacement = SubscriptionReplacement(
            oldProductId = currentPurchase.productIds.single(),
            oldPurchaseToken = currentPurchase.token,
            mode = ReplacementMode.DEFERRED,
        ),
    ),
)
kit.openSubscriptionManagement(activity, productId = "premium")
```

Replacement modes are checked by Play against the selected plans. The backend must
handle linked tokens and retire replaced access. The local store snapshot cannot
infer all replacement/expiry rules; restore after returning to the foreground.
Purchased subscription callbacks also trigger an ownership reconciliation to remove
retired tokens. If that query fails, known subscriptions are reverified so the
backend can reject replaced access without waiting for a manual restore.

## Restore, errors and lifecycle

Call `restorePurchases()` whenever the host returns to the foreground. It queries
configured product types, handles purchases completed outside the app, revalidates
ownership and retries pending verification/finalization. A failed or partial query
preserves the previous ownership snapshot. A successful empty query removes it.

A successful restore means the store query succeeded. Inspect `OwnedPurchase.error`
and `verification` for individual processing failures. A verifier failure sets
`RETRY_REQUIRED` and removes that purchase's local entitlement until verified again;
it does not make an authoritative rejection. Suspended purchases never grant access.

Pending prepaid top-ups and subscription changes are exposed in
`OwnedPurchase.purchase.pendingUpdate`, with their own token and product IDs.
The parent purchase retains its existing verification status and entitlement.
`PurchaseEvent.PendingChange` is emitted when a new pending update is observed;
duplicate observations do not emit it again. No verification or finalization is
performed for the pending token until Play returns it as a top-level purchased
record. Restore clears changes that are no longer pending.

Consumers with exhaustive `when` statements over `PurchaseEvent` must handle
`PendingChange` in addition to the existing event cases.

- `CANCELLED`: a normal user choice; do not show it as a payment failure.
- `ALREADY_OWNED`: call restore. Listener-delivered already-owned errors also trigger restore.
- `BUSY`: wait for the existing checkout result.
- `NETWORK`, `DISCONNECTED`, `TIMEOUT`: retry on foreground/user action; `retryable`
  indicates whether retry can help. Initial connection is serialized and subsequent
  operations reconnect as needed.
- `PRODUCT_UNAVAILABLE` / `INVALID_REQUEST`: refresh the catalog or correct configuration.
- Verification errors: inspect purchase state and correct/retry the backend operation.

`lastError` is a convenience diagnostic; operation results and per-purchase errors
are more precise. Diagnostic messages are not localized user-facing strings.
SDK exception/debug strings are not exposed. Avoid logging tokens or account IDs.

Configuration options are per SDK request timeout (30 seconds), per verification
attempt timeout (30 seconds), and checkout callback timeout (5 minutes), all in
milliseconds. The checkout timeout releases the local launch guard and emits a
retryable timeout; it does **not** dismiss the store UI or mean that payment failed.
Late purchase callbacks are still processed; restore to reconcile uncertain outcomes.

Create and close on Main. Suspend methods can be invoked from any dispatcher.
`close()` is idempotent and cancels operations, ends the connection and clears access.
Close/recreate on sign-out/account switch. No global singleton or Activity is retained.
Do not retain this instance across different authenticated backend identities.

## Limits

- Entitlements are an in-memory projection, not an offline license or server authorization.
  Refund/expiry/account-hold correctness requires backend Google notifications and reconciliation.
- Restores cannot recover already consumed products or historical expired subscriptions;
  obtain durable balances and account entitlements from your backend.
- No exactly-once event delivery, durable client queue or background worker is claimed.
- Checkout supports one product per flow. Rental, preorder and installment offers are
  excluded; products with no supported offers appear in `unavailableProductIds`.
- Alternative billing, external offers, subscription add-on checkout and in-app messaging
  are not part of this API.
- Device/Play Console checkout and backend integration require testing in the consuming app.

## Build and test

```sh
./gradlew :android-purchase-kit:build
```

If your shell does not discover Java, point `JAVA_HOME` to the JDK bundled with
Android Studio or another installed JDK 25. The wrapper, version catalog and daemon
configuration are retained. `build` compiles debug/release AARs, executes JVM unit tests
and runs Android lint. No emulator, device or live billing account is needed for unit tests.

Tests cover delivery ordering, pending/rejected/suspended purchases, verification
retry/timeout/cancellation, deduplication, restore races, connection generations,
partial query failure, mapping and offer selection. The actual SDK boundary is mocked;
a small JUnit rule supplies `TextUtils.isEmpty` for the real SDK JSON models on the JVM.

## API compatibility

The first release is planned as `1.0.0`. Use semantic versioning for subsequent
releases, document breaking changes, and deprecate APIs before removal.
JitPack builds are configured; automated tagging
and Maven Central publishing are not configured. Design decisions are in [architecture](docs/architecture.md), and
release prerequisites are in [readiness](docs/readiness.md).

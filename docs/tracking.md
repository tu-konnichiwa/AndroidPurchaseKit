# Purchase tracking

Tracking is optional and independent of purchase verification, entitlement state
and finalization. There are no tracking requests or journal writes when it is not
configured. The repository still has one Android library module and no additional
networking dependencies.

## Configure HTTP tracking

```kotlin
import com.tukonnichiwa.purchasekit.PurchaseKit
import com.tukonnichiwa.purchasekit.model.PurchaseRequest
import com.tukonnichiwa.purchasekit.tracking.HttpPurchaseTracker
import com.tukonnichiwa.purchasekit.tracking.PurchaseTracking

val tracking = PurchaseTracking(
    context = applicationContext,
    tracker = HttpPurchaseTracker(
        context = applicationContext,
        endpointUrl = "https://your-api.example/purchases",
        defaultActionCase = "unlock",
    ),
    preferencesName = "purchasekit_tracking",
    timeoutMillis = 15_000,
)

// config and verifier are the same objects used for ordinary PurchaseKit setup.
// Create and close PurchaseKit on Main.
val kit = PurchaseKit.create(applicationContext, config, verifier, tracking)

kit.launchPurchase(activity, PurchaseRequest(
    productId = "premium_subscription",
    offerToken = selectedOffer.token,
    trackingContext = "onboarding",
))
```

Keep one tracking configuration and one live kit per account. Use a stable,
distinct `preferencesName` per account/environment. Avoid raw account IDs in that
name. Reusing a journal concurrently across kits/processes is unsupported.
`trackingContext` is optional, nonblank when present, and limited to 256 characters.
Use a short source label; do not include personal data, URLs with credentials or
other secrets. The public request/event string representations redact attribution.

The built-in adapter requires HTTPS and sends this JSON shape:

```json
{
  "appID": "com.example.app",
  "purchase": "yearly",
  "device_name": "Manufacturer Model",
  "action_case": "onboarding",
  "app_version": "1.0.0"
}
```

Package, app version and device name are derived automatically in the Context
constructor. A second constructor accepts explicit metadata and socket timeout
values. `purchase` is one of `daily`, `weekly`, `monthly`, `yearly`, `one-time`,
`consumable` or `unknown`. The last pricing phase of the selected subscription
offer determines its label; trial phases do not replace the recurring label.
Without a selected offer, an ambiguous catalog produces `unknown`, not a guessed
base plan. Labels do not imply a verified expiry date.

The payload contains exactly those five fields. It does not include a token,
token hash, receipt, signature, product ID or account identifier. Only HTTP 2xx is
accepted; redirects are not followed. The manifest includes INTERNET permission.
The built-in adapter uses platform HTTP with 15-second connect/read timeouts;
socket cancellation can take until a socket timeout. No OkHttp or serialization
plugin is required.

## Attribution, persistence and retries

Immediately before launching checkout, the kit persists the selected product,
period label and context. It captures known token hashes so a previously observed
purchase does not take the new checkout's attribution. BUSY/invalid requests do
not overwrite attribution. Immediate launch errors and terminal error/cancellation
callbacks clear their own attempt. A checkout timeout retains attribution because
payment can still complete later.

When a pending purchase is observed, its context is bound to the SHA-256 hash of
its token, but no tracking is sent. The existing parent of a pending subscription
change is handled independently; the nested pending token is never tracked as a
completed purchase.

After the verifier returns `Granted`, a replayable event is persisted **before**
acknowledgement or consumption. Delivery can start at that point; it records a
verified purchased observation, not confirmation that finalization succeeded.
This ordering preserves consumable tracking if the app dies after consumption.
Pending, suspended, rejected and retry-required records do not become eligible.

The network worker runs separately from billing and serializes sends. An event is
marked sent only after the tracker accepts it and the journal saves that result.
Repeat callbacks/restores and later kit instances use the saved sent hash to
suppress resending. Tracking is once per purchase token, not once per recurring
subscription order. Already acknowledged ownership first seen with no checkout
attribution is recorded as an existing restore rather than a new sale.

Failed sends remain queued. `start()`, `restorePurchases()`, new verified purchases
and `retryTracking()` trigger another pass. Retry also works without the token in
Play ownership, including consumed products. There is no persistent Android
background worker, retry timer or automatic network observer; the app must run
the kit again for another attempt.

```kotlin
tracking.status.collect { status ->
    // Diagnostic state: pendingCount and lastFailure (STORAGE or DELIVERY).
    // Report through your host's diagnostics; do not treat it as a billing failure.
}

// Schedules a pass; Success means scheduled, not that all HTTP requests succeeded.
kit.retryTracking()
```

Journal writes happen on IO and store only attribution, product IDs, type labels,
token hashes and sent markers. They contain no raw purchase token or receipt and
are not an offline entitlement cache. Entries/markers are retained until app data
or this preference file is removed; include this retention in the host's data
policy. Storage errors do not prevent acknowledgement/consumption and can lose
tracking; `status` exposes the failure. Do not delete the journal to recover from
temporary network errors.

## Delivery and correlation limits

This outbox is not an exactly-once protocol. If the server accepts a request and
the response or local sent-marker write is lost, a retry can duplicate it. The
five-field endpoint contract carries no idempotency key. For server deduplication,
implement a custom tracker that sends `TrackedPurchase.id` as an idempotency key
or header. Restoring an already acknowledged purchase on a fresh install does not
provide a complete analytics history, and reinstall/data clearing resets the log.

Play callbacks do not expose an application checkout identifier. Attribution
matches a new token to a prepared product. Concurrent/out-of-app purchases of the
same product and late callbacks after a new attempt can remain ambiguous.
For replacements, the old product ID can match a new token, but the explicitly
replaced token is excluded even if it was not previously observed by this journal.
Keep attribution advisory;
it must not drive account authorization, grants or financial accounting.

## Custom transport or payload

```kotlin
import com.tukonnichiwa.purchasekit.tracking.PurchaseTracker

val tracker = PurchaseTracker { event ->
    // Use an app-owned HTTP client, authentication or a different endpoint schema.
    // event.id is a token hash; event.context is the optional source label.
    analyticsApi.sendPurchase(
        idempotencyKey = event.id,
        productIds = event.productIds,
        purchaseType = event.type.wireValue,
        source = event.context,
    ) // Return Boolean: true only when accepted.
}
```

`analyticsApi` is an application-defined example. Custom implementations must be
cancellation-cooperative, move blocking work off Main and never grant entitlements.
False, exceptions, a tracker-owned cancellation or timeout retain the event for
retry. Kit closure cancels delivery; it does not mark an interrupted send accepted.

## Migrating an existing integration

Choose a stable journal namespace and disable any previous purchase tracker when
enabling this one, otherwise both integrations can send the same sale. Existing
external journal schemas are not imported automatically. Leave premium caching
and app-specific policies in the consuming application's repository.

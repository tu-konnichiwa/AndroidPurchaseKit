# Architecture decisions

## One module, three logical boundaries

We considered (1) a direct BillingClient wrapper exposing SDK objects and (2) a
Kotlin API with a small store port and an Android adapter. The second approach
keeps verification, entitlement policy and reconciliation testable on the JVM.
The physical Gradle module remains `android-purchase-kit`; additional modules
would add build complexity without a separate owner or deployment boundary.

- `model`: immutable Kotlin contracts and input validation; no Android or SDK imports.
- `internal/PurchaseEngine`: serializes reconciliation, verifies purchases, and
  controls finalization. Its store port exposes only catalog, ownership and
  finalization operations used by this policy.
- `internal/play`: Google Play callbacks, mapping, connection and checkout UI.
- `PurchaseKit`: outer composition root and Android entry point. Owns a coroutine
  scope and the adapter. An Activity is used only during checkout and never stored.

## Checkout and completion are different operations

We considered waiting for checkout completion in a suspended caller versus
returning after Google Play accepts a launch. We use the second option: a
successful `launchPurchase` means the checkout UI was launched. Purchase processing
continues in the instance's scope, independently of the initiating screen.
`state` is the current local snapshot. `events` are transient notifications, never
a durable delivery queue. Google Play may complete a pending payment outside the
app, and `restorePurchases` reconciles it when the host returns to the foreground.

## Verification and delivery precede finalization

A required `PurchaseVerifier` approves access before finalization. A backend
implementation validates the purchase and persists its grant idempotently before
returning `Granted`. No verifier is selected implicitly. Consumable quantity must
be applied exactly once by the delivery implementation,
keyed by purchase token. The backend may finalize itself, or request client
acknowledgement/consumption after its grant commits. This avoids a consumable
being consumed before the user's balance is durably credited.

An in-memory completion set suppresses repeated finalization in the current
process; it is not a security or exactly-once boundary. Restores reverify durable
purchases so rejection/revocation can remove a prior local entitlement. Network
failure never becomes rejection, and partial store queries never replace the
ownership snapshot. Local entitlement state is a convenience projection, not an
authorization authority or an offline subscription database.

## Concurrency and lifetime

Public suspend calls switch to the instance's Main dispatcher. SDK callbacks are
queued and reconciliation is serialized by a mutex, including ownership queries,
so an update cannot be overwritten by an older in-flight query. All SDK waits
and verifier calls have configurable timeouts. Caller cancellation propagates;
late SDK replies are ignored by cancelled continuations. Closing the instance
cancels its jobs, ends the store connection and clears entitlement state.

No Activity, singleton, global Context, automatic UI lifecycle observer, secret,
or backend transport client is kept in the core. The host owns authentication,
backend persistence, foreground refresh and closing/recreating the instance on
account changes.

## Optional verification without a backend

We considered an unconditional client approval implementation versus checking the
signed Play receipt with the application's billing public key. The opt-in
`ClientSidePurchaseVerifier` uses signature verification and fails closed on
missing, malformed or mismatched data. The application still passes an explicit
verifier to `PurchaseKit.create`; backend behavior is unchanged.

The SDK adapter preserves the signed receipt in a small pure `SignedPurchaseData`
value. The client verifier is an outer adapter: it uses Java cryptography and the
existing Play mapper to bind every observed field to the verified payload. The
model and engine do not import cryptography, JSON or Billing SDK types. Receipt
and signature strings are redacted from `toString`; applications must also avoid
logging or insecurely persisting the exposed raw properties.

Crypto and receipt parsing run on Default. The allowed product IDs are copied at
construction. Only subscriptions and non-consumables are supported, because an
automatic consumable approval would finalize before durable quantity delivery.
Local signature verification cannot establish receipt freshness, current refund
or expiry status, account ownership, or replay prevention. It does not add an
offline entitlement cache. These remain reasons to prefer backend verification.
The additive `PurchaseRecord.signedData` field defaults to null for custom records;
such records continue to work with custom verifiers but fail RSA signature verification.

## Explicit trust mode without a public key

We considered silently accepting unsigned receipts in the RSA implementation
versus exposing a separate implementation for apps without a backend or key.
`PlayBillingPurchaseVerifier` is a separate, explicitly selected policy. Missing
or invalid signatures still fail in `ClientSidePurchaseVerifier`; they never
trigger fallback. The no-key policy does not read `signedData`, so it also does
not claim receipt authenticity when signature fields happen to be present.

Both local implementations use `LocalPurchasePolicy` to own product/package and
purchase-state eligibility once. This pure Kotlin policy snapshots product IDs,
validates configuration, rejects consumables and rejects pending/suspended or
unconfigured observations. RSA verification adds signature and signed-field
binding at the outer adapter. The no-key implementation returns `Granted(CLIENT)`
after eligibility, leaving acknowledgement and local entitlements to the engine.

Observation trust cannot distinguish a fabricated record from an authentic Play
response and cannot establish current account ownership, expiry/refund status,
replay protection or durable consumable delivery. It adds no persistence and
does not query Play independently. Foreground restore remains the host's duty.

## Optional durable analytics

We considered asking apps to collect transient Completed events versus adding an
optional application-owned outbox. UI events can be missed and consumed tokens
cannot be recovered from later ownership queries, so tracking uses a separate
`TrackingCoordinator` and `TrackingJournal` port. No analytics dependencies or
Android types enter the core. `PurchaseTracking` assembles a preference journal,
tracker and status projection at the outer boundary; all tracking is opt-in.

The checkout adapter records attribution only after acquiring its launch guard
and validating fresh product/offer details. Attempt identity protects cleanup;
token hashes known at preparation prevent existing observations taking new
attribution. Pending observations bind context but cannot trigger delivery. Play
does not expose an app checkout ID, so same-product/out-of-app attribution is
advisory and can remain ambiguous.

We also considered recording only after successful finalization versus persisting
after verification and before consumption. The latter preserves a replayable
consumable event if the process dies after consumption. Analytics therefore
describes verifier-approved purchases; it can be delivered before finalization
succeeds. Delivery never grants access and transport/storage failures never block
billing success. Storage failures can lose tracking and are surfaced separately.

A single journal snapshot atomically holds attribution, token hashes, ready
events and sent markers. No raw purchase tokens or receipts are persisted.
JSON and SharedPreferences stay in the outer adapter; reads and commits run on
IO. A coroutine worker serializes transport outside the engine mutation mutex.
Start/restore/manual retry trigger replay even if Play ownership is unavailable.
There is no background service or automatic timer. A stable journal name with one
live kit is required; entries persist until host/app data removal.

`HttpPurchaseTracker` uses the platform HTTPS client to avoid adding a networking
dependency. It preserves the five-field analytics schema and accepts only 2xx,
without following redirects. A custom `PurchaseTracker` can reuse the host HTTP
client and include the token hash as a server idempotency key. A lost response or
sent-marker write can produce duplicate sends; no exactly-once claim is made.

## Subscription replacement reconciliation

We considered extending every verifier response with retired purchase tokens versus
querying current ownership when a purchased subscription callback arrives. We use
the ownership query to preserve the existing verifier contract and also cover
changes initiated outside this instance. Reconciliation runs under the existing
mutation mutex. Tokens missing from a successful snapshot are removed before the
new grant is published. Callback records override matching snapshot records so a
lagging query cannot discard a just-delivered payment or pending update.

If the query fails, existing purchases are not removed based on that failure.
Instead, known subscriptions and incoming records are reverified; the backend can
reject a retired token. If the backend is also unavailable, verification becomes
retry-required rather than preserving an unverified local grant. The backend must
still handle linked tokens and deferred replacement entitlement timing.

## Pending subscription changes

We considered representing a pending top-up/replacement as a second full purchase
versus attaching a small pending-update model to the existing purchase. We use
`PurchaseRecord.pendingUpdate`: Play only provides the new token and product IDs
at this stage, so fabricating a quantity, purchase time or acknowledgement status
would be misleading. The existing purchase retains its own verification status.
Only a later top-level PURCHASED record can authorize delivery/finalization of
the new token. Restoring a parent without the update clears a cancelled change.

`PurchaseEvent.PendingChange` notifies consumers when an observed update changes;
duplicate observations do not emit another notification. Like other events, it is
transient. Consumers should render current pending changes from state. This adds
a case to the unreleased sealed event API, so exhaustive consumer `when` statements
must handle it.

## Checkout ownership

A single Boolean cannot distinguish preparation from a launched checkout or
identify the owner of a timeout. Each attempt now owns its request metadata,
phase and timer. Callbacks cannot release a preparing attempt. Once launched,
purchase updates only release it when their product IDs match the request,
including nested pending updates and the old product of a deferred replacement.
The replaced purchase's old token alone does not finish a new checkout. Cleanup
and timers check attempt identity before releasing the guard.

Google Play does not expose an application checkout identifier in its callbacks.
Unidentified error/cancellation callbacks are treated as terminal only after
launch; purchase callbacks for the same product cannot always be attributed to
a specific attempt. This guard limits overlapping launches, while verification
and ownership reconciliation determine purchase truth independently.

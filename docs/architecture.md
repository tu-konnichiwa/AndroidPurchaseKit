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

A required `PurchaseVerifier` validates a purchase with a trusted backend and
persists its grant idempotently before returning `Granted`. No permissive verifier
is provided. Consumable quantity must be applied exactly once by the backend,
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

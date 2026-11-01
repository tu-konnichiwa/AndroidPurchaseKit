# Release readiness

The repository contains the Android client library. Google Play configuration
belongs to the consuming product. Apps can explicitly use local signature
verification or no-key observation trust for subscriptions/non-consumables;
trusted authorization and durable
consumable delivery require a stronger custom/backend integration. Passing JVM
tests is not evidence that Play checkout works on a device.

## Review and verification

Verified on 2026-11-01 using the JDK 25 bundled with Android Studio:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :android-purchase-kit:build
```

Result: **BUILD SUCCESSFUL**. All 91 JVM tests passed (20 core policy, 18 store
adapter, 11 model/mapping, 7 purchase regression, 9 RSA client verification,
8 no-key verification/integration, 18 tracking). Debug and
release AARs were generated, and Maven local publication succeeded. Android lint
reported zero errors and six informational warnings: existing Gradle/AGP versions,
newer JSON/Mockito test dependencies, and optional KTX URI/preferences extensions. The
release AAR contains the compiled Kotlin API. No device purchase or real backend
verification was executed.

The implementation keeps Android/Billing imports at the edge, validates incoming
store data, uses a required verifier, and has no implicit local purchase approval.
Verification occurs before acknowledgement/consumption. Ownership queries publish
only after all configured store types respond successfully. Pending/suspended
purchases do not grant access. Tokens and account identifiers are redacted from
purchase/request string representations.

Regression checks cover a late pending callback after purchased, verifier-owned
cancellation versus caller cancellation, client retirement after setup timeout,
partial restores and duplicate callbacks. Additional regression checks cover
subscription replacement reconciliation (including failed and stale queries),
checkout ownership during unrelated callbacks, and pending subscription changes
through completion or cancellation. The library deliberately does not use
SDK debug messages or external exception messages as its error contract.

Client verification tests use generated RSA keys and signed receipts to cover
valid subscription/lifetime access, invalid signatures, tampered data, missing or
malformed receipts, package/product mismatches, pending/suspended purchases,
field binding, invalid configuration, redaction and defensive product copying.

No-key verification tests cover unsigned access, package/product mismatches,
pending/suspended records, configuration validation, defensive copying and explicit
signature bypass. Engine integration verifies acknowledgement deduplication,
no acknowledgement of a nested pending token, removal on successful empty restore
and rejection without finalization. RSA verification tests also pass after sharing
the local eligibility policy; there is no automatic no-key fallback.

Tracking tests cover persisted context through pending payment/restart, deferred
replacement attribution, known-token exclusion, failed delivery replay without
store ownership, sent-marker deduplication and write failure, worker cancellation,
timeout, engine/storage isolation, pre-consumption staging, period labels and the
five-field payload. Store integration covers BUSY and launch-error attribution
cleanup. The published AAR and source JAR contain the tracking API. No request was
sent to a real analytics endpoint; endpoint behavior and device/process lifecycle
still require host integration validation.

## Gaps and priorities

| Priority | Missing / improvement | Why and expected impact | Recommended action / timing | Complexity |
|---|---|---|---|---|
| P0 for trusted authorization/consumables | Deployed authenticated verifier and durable delivery ledger | Local signatures do not establish current trusted ownership or exactly-once balances | Implement in the consuming backend when trusted authorization or consumable delivery is needed; validate account/package/product/token/quantity, commit grants idempotently, handle linked subscriptions and finalization | High |
| P1 | Play license-tester/device validation | Mocks cannot reproduce Play account eligibility, pending-payment UX, real offer tokens or replacement restrictions | Before release, execute the device matrix below against the consuming app and backend | Medium |
| P1 | Backend lifecycle notifications and reconciliation | Refunds, expiry, hold and revocation can occur while the app is closed | Integrate Google notifications and periodic reconciliation before production subscription use | High |
| P1 | CI and public API compatibility baseline | Prevent regressions and accidental API changes across consumers | Add a CI build/test/lint job and binary API checks before publishing a stable release | Medium |
| P2 | Documentation/API publication and release checklist | Consumers need discoverable versioned contracts and migration notes | Add generated KDoc, release notes and dependency review when preparing distribution | Medium |
| P2 | Broader supported Play features | Rentals, preorders, installments, add-on checkout and in-app messages need distinct contracts | Defer until a consuming product needs them, then add corresponding mapping and integration tests | High |
| P3 | Host analytics integration and endpoint idempotency | Optional purchase tracking exists; funnel analytics and server deduplication remain host-specific | Configure the tracker/endpoint and avoid running two trackers; use a custom tracker with an idempotency key when required | Low |

No known unresolved P0 defect in the client implementation is asserted here; the
P0 row is a required external integration, not an implemented backend.

## Device validation matrix

Use a consuming application distributed through the appropriate Play test track.
No sample/application module is required in this repository.

- Non-consumable: buy, cancel, already owned, reinstall and restore.
- Consumable: repeated purchase, quantity > 1, duplicate callback, backend replay,
  process death before/after grant and before/after consumption.
- Subscription: free trial, intro price, recurring/prepaid plans, renewal, expiry,
  cancellation with remaining access, hold, suspension and grace period.
- Replacement: each supported mode permitted by the selected plans, same-product
  base-plan change and cross-product change, deferred replacement and linked tokens.
- Pending payment: complete/cancel outside the app, foreground reconciliation.
- Network: loss before checkout, during verifier call and during finalization.
- Lifecycle: rotate/leave checkout, kill/restart, sign out/switch user, close while
  an SDK request is pending, timeout followed by a late callback.
- Backend: wrong account/package/product, invalid token, duplicate token, quantity
  mismatch, revocation/refund, unavailable verifier and finalization retry.

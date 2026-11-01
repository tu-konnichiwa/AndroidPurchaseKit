package com.tukonnichiwa.purchasekit.internal.tracking

import com.tukonnichiwa.purchasekit.model.*
import com.tukonnichiwa.purchasekit.tracking.*
import java.security.MessageDigest
import java.time.Period
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes durable attribution/outbox writes; network work never holds a billing mutex. */
internal class TrackingCoordinator(
    private val journal: TrackingJournal,
    private val tracker: PurchaseTracker,
    scope: CoroutineScope,
    private val timeoutMillis: Long = 15_000,
    private val status: (PurchaseTrackingStatus) -> Unit = {},
) {
    private val mutex = Mutex()
    private val triggers = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in triggers) flush()
        }
    }

    suspend fun prepare(request: PurchaseRequest, product: Product): String? = storage {
        val snapshot = journal.read()
        val attempt = TrackingAttempt(UUID.randomUUID().toString(), request.productId,
            typeOf(product, request.offerToken), request.trackingContext,
            snapshot.entries.mapTo(mutableSetOf()) { it.purchase.id }.apply {
                request.replacement?.let { add(tokenKey(it.oldPurchaseToken)) }
            }, request.replacement?.oldProductId)
        val next = snapshot.copy(attempts = snapshot.attempts.filterNot { it.productId == request.productId } + attempt)
        journal.write(next)
        publish(next)
        attempt.id
    }

    suspend fun abandon(attemptId: String?) {
        if (attemptId == null) return
        storage {
            val snapshot = journal.read()
            journal.write(snapshot.copy(attempts = snapshot.attempts.filterNot { it.id == attemptId }))
        }
    }

    suspend fun observePending(purchase: PurchaseRecord, catalog: Catalog) {
        observe(purchase, catalog, ready = false)
    }

    /** Persist before finalization, so consumption cannot erase the only replayable observation. */
    suspend fun verified(purchase: PurchaseRecord, catalog: Catalog) {
        observe(purchase, catalog, ready = true)
        retry()
    }

    fun retry() { triggers.trySend(Unit) }

    private suspend fun observe(purchase: PurchaseRecord, catalog: Catalog, ready: Boolean) {
        storage {
            val snapshot = journal.read()
            val key = tokenKey(purchase.token)
            val existing = snapshot.entries.firstOrNull { it.purchase.id == key }
            val attempt = if (existing == null) snapshot.attempts.firstOrNull {
                (it.productId in purchase.productIds || it.replacementProductId in purchase.productIds) && key !in it.knownKeys
            } else null
            val event = existing?.purchase ?: TrackedPurchase(key, purchase.productIds.toList(),
                attempt?.type ?: catalog.products.singleOrNull { it.definition.id in purchase.productIds }
                    ?.let { typeOf(it, null) } ?: PurchaseTrackingType.UNKNOWN,
                attempt?.context)
            // Already acknowledged ownership first seen on a fresh install is a restore, not a sale.
            val sent = existing?.sent ?: (purchase.acknowledged && attempt == null)
            val entry = TrackingEntry(event, ready || existing?.ready == true, sent)
            val next = TrackingSnapshot(snapshot.attempts.filterNot { it.id == attempt?.id },
                snapshot.entries.filterNot { it.purchase.id == key } + entry)
            journal.write(next)
            publish(next)
        }
    }

    private suspend fun flush() {
        val snapshot = storage { journal.read().also { publish(it) } } ?: return
        for (entry in snapshot.entries.filter { it.ready && !it.sent }) {
            val delivered = try {
                withTimeoutOrNull(timeoutMillis) { tracker.track(entry.purchase) } == true
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                false
            } catch (_: Exception) { false }
            if (!delivered) {
                storage { publish(journal.read(), TrackingFailure.DELIVERY) }
                continue
            }
            storage {
                val current = journal.read()
                val next = current.copy(entries = current.entries.map {
                    if (it.purchase.id == entry.purchase.id) it.copy(sent = true) else it
                })
                journal.write(next)
                publish(next)
            }
        }
    }

    private suspend fun <T> storage(block: suspend () -> T): T? = try {
        mutex.withLock { block() }
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        status(PurchaseTrackingStatus(lastFailure = TrackingFailure.STORAGE))
        null
    } catch (_: Exception) {
        status(PurchaseTrackingStatus(lastFailure = TrackingFailure.STORAGE))
        null
    }

    private fun publish(snapshot: TrackingSnapshot, failure: TrackingFailure? = null) {
        status(PurchaseTrackingStatus(snapshot.entries.count { it.ready && !it.sent }, failure))
    }

    companion object {
        fun tokenKey(token: String): String = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun typeOf(product: Product, offerToken: String?): PurchaseTrackingType = when (product) {
            is OneTimeProduct -> if (product.definition.type == ProductType.CONSUMABLE)
                PurchaseTrackingType.CONSUMABLE else PurchaseTrackingType.ONE_TIME
            is SubscriptionProduct -> {
                val offers = if (offerToken == null) product.offers else product.offers.filter { it.token == offerToken }
                offers.map { periodType(it.pricingPhases.last().billingPeriod) }.distinct().singleOrNull()
                    ?: PurchaseTrackingType.UNKNOWN
            }
        }

        private fun periodType(value: String): PurchaseTrackingType {
            val period = Period.parse(value)
            return when {
                period.toTotalMonths() > 0 && period.toTotalMonths() % 12 == 0L && period.days == 0 -> PurchaseTrackingType.YEARLY
                period.toTotalMonths() > 0 && period.days == 0 -> PurchaseTrackingType.MONTHLY
                period.days > 0 && period.days % 7 == 0 && period.months == 0 && period.years == 0 -> PurchaseTrackingType.WEEKLY
                period.days > 0 && period.months == 0 && period.years == 0 -> PurchaseTrackingType.DAILY
                else -> PurchaseTrackingType.UNKNOWN
            }
        }
    }
}

package com.tukonnichiwa.purchasekit.internal.tracking

import android.content.Context
import com.tukonnichiwa.purchasekit.tracking.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A single atomic preference value contains attribution, replay data and sent token hashes. */
internal class PreferencesTrackingJournal(context: Context, private val name: String) : TrackingJournal {
    private val appContext = context.applicationContext

    override suspend fun read(): TrackingSnapshot = withContext(Dispatchers.IO) {
        val raw = appContext.getSharedPreferences(name, Context.MODE_PRIVATE).getString("journal", null)
        if (raw == null) TrackingSnapshot() else TrackingJournalCodec.decode(raw)
    }

    override suspend fun write(snapshot: TrackingSnapshot) = withContext(Dispatchers.IO) {
        check(appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .putString("journal", TrackingJournalCodec.encode(snapshot)).commit()) {
            "Tracking journal could not be saved."
        }
    }
}

internal object TrackingJournalCodec {
    fun encode(snapshot: TrackingSnapshot): String = JSONObject().apply {
        put("version", 1)
        put("attempts", JSONArray().apply {
            snapshot.attempts.forEach { attempt -> put(JSONObject().apply {
                put("id", attempt.id)
                put("product", attempt.productId)
                put("type", attempt.type.name)
                put("context", attempt.context ?: JSONObject.NULL)
                put("known", JSONArray(attempt.knownKeys.toList()))
                put("replaces", attempt.replacementProductId ?: JSONObject.NULL)
            }) }
        })
        put("entries", JSONArray().apply {
            snapshot.entries.forEach { entry -> put(JSONObject().apply {
                put("id", entry.purchase.id)
                put("products", JSONArray(entry.purchase.productIds))
                put("type", entry.purchase.type.name)
                put("context", entry.purchase.context ?: JSONObject.NULL)
                put("ready", entry.ready)
                put("sent", entry.sent)
            }) }
        })
    }.toString()

    fun decode(raw: String): TrackingSnapshot {
        val root = JSONObject(raw)
        require(root.getInt("version") == 1) { "Unsupported tracking journal version." }
        val attempts = root.getJSONArray("attempts")
        val entries = root.getJSONArray("entries")
        return TrackingSnapshot(
            (0 until attempts.length()).map { index -> attempts.getJSONObject(index).let {
                TrackingAttempt(it.getString("id"), it.getString("product"),
                    PurchaseTrackingType.valueOf(it.getString("type")),
                    if (it.isNull("context")) null else it.getString("context"),
                    strings(it.getJSONArray("known")).toSet(),
                    if (it.isNull("replaces")) null else it.getString("replaces"))
            } },
            (0 until entries.length()).map { index -> entries.getJSONObject(index).let {
                TrackingEntry(TrackedPurchase(it.getString("id"), strings(it.getJSONArray("products")),
                    PurchaseTrackingType.valueOf(it.getString("type")),
                    if (it.isNull("context")) null else it.getString("context")),
                    it.getBoolean("ready"), it.getBoolean("sent"))
            } },
        )
    }

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }
}

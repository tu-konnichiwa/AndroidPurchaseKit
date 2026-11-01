package com.tukonnichiwa.purchasekit.tracking

import android.content.Context
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * POSTs the five-field analytics payload: appID, purchase, device_name, action_case, app_version.
 * Requires HTTPS; never sends token hashes, raw tokens, receipts or account identifiers.
 * Only 2xx is accepted; redirects are not followed. Socket waits have bounded timeouts.
 * Implement PurchaseTracker for different payloads, authentication or an app-owned HTTP client.
 */
public class HttpPurchaseTracker(
    private val endpointUrl: String,
    private val appId: String,
    private val appVersion: String,
    private val deviceName: String,
    private val defaultActionCase: String = "unlock",
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 15_000,
) : PurchaseTracker {
    public constructor(
        context: Context,
        endpointUrl: String,
        defaultActionCase: String = "unlock",
    ) : this(endpointUrl, context.applicationContext.packageName,
        versionName(context.applicationContext), "${Build.MANUFACTURER} ${Build.MODEL}", defaultActionCase)

    init {
        require(URL(endpointUrl).protocol == "https") { "Tracking requires an HTTPS endpoint." }
        require(appId.isNotBlank() && defaultActionCase.isNotBlank())
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
    }

    override suspend fun track(purchase: TrackedPurchase): Boolean = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val connection = URL(endpointUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val bytes = payload(purchase).toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val success = connection.responseCode in 200..299
            currentCoroutineContext().ensureActive()
            success
        } finally {
            connection.disconnect()
        }
    }

    internal fun payload(purchase: TrackedPurchase): String = JSONObject().apply {
        put("appID", appId)
        put("purchase", purchase.type.wireValue)
        put("device_name", deviceName)
        put("action_case", purchase.context ?: defaultActionCase)
        put("app_version", appVersion)
    }.toString()

    private companion object {
        @Suppress("DEPRECATION")
        fun versionName(context: Context): String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) { "" }
    }
}

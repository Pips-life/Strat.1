package life.pips.strat1.data

import life.pips.strat1.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class LiveTradingApi(
    private val baseUrl: String = BuildConfig.BACKEND_BASE_URL.trimEnd('/'),
    private val http: OkHttpClient = OkHttpClient(),
) {
    suspend fun state(accountId: String, sessionToken: String? = null): Result<LiveTradingState> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBuilder = Request.Builder().url("$baseUrl/api/mt5/state?accountId=${java.net.URLEncoder.encode(accountId, "UTF-8")}").get()
            if (!sessionToken.isNullOrBlank()) requestBuilder.header("x-pipslife-session", sessionToken)
            http.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IllegalStateException(error(body, "Unable to read live MT5 state"))
                val json = JSONObject(body)
                val account = json.optJSONObject("account") ?: JSONObject()
                val rawPositions = json.optJSONArray("positions") ?: JSONArray()
                val positions = buildList {
                    for (i in 0 until rawPositions.length()) {
                        val p = rawPositions.getJSONObject(i); val type = p.optString("type", "")
                        add(LivePosition(p.optString("id"), p.optString("symbol", "—"), if (type.contains("SELL", true)) "SELL" else "BUY", p.optDouble("volume", 0.0), p.optDouble("openPrice", 0.0), p.optDouble("currentPrice", 0.0), p.optDouble("stopLoss", 0.0), p.optDouble("takeProfit", 0.0), p.optDouble("profit", p.optDouble("unrealizedProfit", 0.0))))
                    }
                }
                LiveTradingState(accountId, account.optString("login", "—"), account.optString("server", "—"), account.optString("state", "UNKNOWN"), account.optString("connectionStatus", "UNKNOWN"), account.optString("currency", "USD"), account.optDoubleOrNull("balance"), account.optDoubleOrNull("equity"), account.optDoubleOrNull("freeMargin"), if (account.has("tradeAllowed")) account.optBoolean("tradeAllowed") else null, positions, json.optString("fetchedAt", ""))
            }
        }
    }

    suspend fun connect(login: String, password: String, server: String, broker: String? = null): Mt5ConnectionResult = withContext(Dispatchers.IO) {
        val json = JSONObject().apply { put("login", login); put("password", password); put("server", server); put("name", "Pips-life MT5 $login"); if (!broker.isNullOrBlank()) put("broker", broker) }
        val request = Request.Builder().url("$baseUrl/api/mt5/connect").post(json.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(error(body, "MT5 connection failed"))
            val result = JSONObject(body)
            Mt5ConnectionResult(result.optString("accountId"), result.optString("state", "UNKNOWN"), result.optString("connectionStatus", "CONNECTING"), result.optString("server", server), result.optString("login", login), result.optString("sessionToken"))
        }
    }

    suspend fun botStatus(accountId: String, sessionToken: String): BotControlState = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/api/bot/control?accountId=${java.net.URLEncoder.encode(accountId, "UTF-8")}").header("x-pipslife-session", sessionToken).get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty(); if (!response.isSuccessful) throw IllegalStateException(error(body, "Bot status unavailable")); parseBot(JSONObject(body))
        }
    }

    suspend fun setBot(accountId: String, sessionToken: String, start: Boolean): BotControlState = withContext(Dispatchers.IO) {
        val json = JSONObject().apply { put("action", if (start) "start" else "stop"); put("strategy", "001"); put("accountId", accountId) }
        val request = Request.Builder().url("$baseUrl/api/bot/control").header("x-pipslife-session", sessionToken).post(json.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty(); if (!response.isSuccessful) throw IllegalStateException(error(body, "Bot command failed")); parseBot(JSONObject(body))
        }
    }

    private fun parseBot(json: JSONObject) = BotControlState(json.optBoolean("running"), json.optString("state", json.optString("status", "UNKNOWN")), json.optString("strategy", "001"), json.optString("activity", ""), json.optBoolean("configured", true))
    private fun error(body: String, fallback: String): String = runCatching { JSONObject(body).optString("error").ifBlank { fallback } }.getOrDefault(fallback)
}

data class LiveTradingState(val accountId: String, val login: String, val server: String, val state: String, val connectionStatus: String, val currency: String, val balance: Double?, val equity: Double?, val freeMargin: Double?, val tradeAllowed: Boolean?, val positions: List<LivePosition>, val fetchedAt: String)
data class LivePosition(val id: String, val symbol: String, val side: String, val volume: Double, val entry: Double, val current: Double, val stopLoss: Double, val takeProfit: Double, val profit: Double)
data class BotControlState(val running: Boolean, val state: String, val strategy: String, val activity: String, val configured: Boolean)
data class Mt5ConnectionResult(val accountId: String, val state: String, val connectionStatus: String, val server: String, val login: String, val sessionToken: String)
private fun JSONObject.optDoubleOrNull(key: String): Double? = if (!has(key) || isNull(key)) null else optDouble(key)

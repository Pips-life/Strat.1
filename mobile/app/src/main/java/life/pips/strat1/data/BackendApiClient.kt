package life.pips.strat1.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import life.pips.strat1.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class BackendApiClient(private val http: OkHttpClient = OkHttpClient()) {
    private val base = BuildConfig.BACKEND_BASE_URL.trimEnd('/')

    suspend fun findServers(query: String): Result<List<Mt5Server>> = runCatching { withContext(Dispatchers.IO) {
        val t = requestJson("GET", "/api/mt5/servers?q=${URLEncoder.encode(query, "UTF-8")}", null)
        val a = JSONObject(t).optJSONArray("brokers") ?: JSONArray()
        buildList { for (i in 0 until a.length()) { val s = a.getJSONObject(i); add(Mt5Server(s.optString("id"), s.optString("brokerName"), s.optString("serverName"), s.optString("environment", "real"))) } }
    } }

    suspend fun connect(login: String, password: String, server: String, broker: String): Result<BackendSession> = runCatching {
        val r = JSONObject(requestJson("POST", "/api/mt5/connect", JSONObject().apply { put("login", login); put("password", password); put("server", server); put("broker", broker); put("name", "Pips-life MT5 $login") }.toString()))
        BackendSession(r.getString("accountId"), r.getString("sessionToken"), r.optString("server", server))
    }

    suspend fun liveState(session: BackendSession): Result<LiveState> = runCatching {
        val r = JSONObject(requestJson("GET", "/api/mt5/state?accountId=${URLEncoder.encode(session.accountId, "UTF-8")}", null, session.token))
        val a = r.optJSONObject("account") ?: JSONObject()
        LiveState(session.accountId, a.optString("login"), a.optString("server"), a.optString("connectionStatus"), a.optString("state"), a.optDouble("balance", Double.NaN), a.optDouble("equity", Double.NaN), a.optDouble("freeMargin", Double.NaN), a.optString("currency", ""), parsePositions(r.optJSONArray("positions") ?: JSONArray()))
    }

    suspend fun botStatus(session: BackendSession): Result<BotState> = runCatching {
        val r = JSONObject(requestJson("GET", "/api/bot/control?accountId=${URLEncoder.encode(session.accountId, "UTF-8")}", null, session.token))
        BotState(r.optBoolean("configured", false), r.optString("state", "UNKNOWN"), r.optString("strategy", "001"), r.optString("activity", ""))
    }

    suspend fun botCommand(session: BackendSession, action: String): Result<BotState> = runCatching {
        val r = JSONObject(requestJson("POST", "/api/bot/control", JSONObject().apply { put("action", action); put("strategy", "001"); put("accountId", session.accountId) }.toString(), session.token))
        BotState(r.optBoolean("configured", true), r.optString("state", action.uppercase()), r.optString("strategy", "001"), r.optString("activity", "COMMAND ACCEPTED"))
    }

    private suspend fun requestJson(method: String, path: String, body: String?, token: String? = null): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(base + path).addHeader("Accept", "application/json")
        if (token != null) b.addHeader("x-pipslife-session", token)
        if (body != null) b.method(method, body.toRequestBody("application/json".toMediaType())) else b.method(method, null)
        http.newCall(b.build()).execute().use { r -> val t = r.body?.string().orEmpty(); if (!r.isSuccessful) throw IllegalStateException(error(t, "Backend request failed (${r.code})")); t }
    }

    private fun parsePositions(a: JSONArray): List<LivePosition> = buildList { for (i in 0 until a.length()) { val p = a.getJSONObject(i); add(LivePosition(p.optString("symbol"), p.optString("type").removePrefix("POSITION_TYPE_").ifBlank { p.optString("side") }, p.optDouble("volume", 0.0), p.optDouble("openPrice", Double.NaN), p.optDouble("currentPrice", Double.NaN), p.optDoubleOrNull("stopLoss"), p.optDoubleOrNull("takeProfit"), p.optDouble("profit", Double.NaN))) } }
    private fun error(b: String, f: String) = runCatching { JSONObject(b).optString("error").ifBlank { f } }.getOrDefault(f)
    private fun JSONObject.optDoubleOrNull(n: String): Double? = if (has(n) && !isNull(n)) optDouble(n).takeUnless { it.isNaN() } else null
}

data class BackendSession(val accountId: String, val token: String, val server: String)
data class LiveState(val accountId: String, val login: String, val server: String, val connectionStatus: String, val state: String, val balance: Double, val equity: Double, val freeMargin: Double, val currency: String, val positions: List<LivePosition>)
data class LivePosition(val symbol: String, val side: String, val volume: Double, val entry: Double, val current: Double, val stopLoss: Double?, val takeProfit: Double?, val profit: Double)
data class BotState(val configured: Boolean, val state: String, val strategy: String, val activity: String)

package life.pips.strat1.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import life.pips.strat1.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class Mt5ApiClient(
    private val baseUrl: String = BuildConfig.BACKEND_BASE_URL.trimEnd('/'),
    private val http: OkHttpClient = OkHttpClient(),
) {
    suspend fun findServers(query: String): List<Mt5Server> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/api/mt5/servers?q=${URLEncoder.encode(query, "UTF-8")}").get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(error(body, "Server discovery failed"))
            val items = JSONObject(body).optJSONArray("brokers") ?: JSONArray()
            buildList { for (i in 0 until items.length()) { val x = items.getJSONObject(i); add(Mt5Server(x.optString("id"), x.optString("brokerName"), x.optString("serverName"), x.optString("environment", "real"))) } }
        }
    }

    fun searchServers(query: String, onResult: (Result<List<Mt5Server>>) -> Unit) = CoroutineScope(Dispatchers.IO).launch { val r = runCatching { findServers(query) }; withContext(Dispatchers.Main) { onResult(r) } }

    suspend fun connect(login: String, password: String, server: String, broker: String): Mt5ConnectionResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply { put("login", login); put("password", password); put("server", server); put("broker", broker); put("name", "Pips-life MT5 $login") }
        val request = Request.Builder().url("$baseUrl/api/mt5/connect").post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(error(body, "MT5 connection failed"))
            val x = JSONObject(body)
            Mt5ConnectionResult(x.optString("accountId"), x.optString("sessionToken"), x.optString("state", "UNKNOWN"), x.optString("server", server), x.optString("connectionStatus", "CONNECTING"))
        }
    }

    fun connect(login: String, password: String, server: String, broker: String, onResult: (Result<Mt5ConnectionResult>) -> Unit) = CoroutineScope(Dispatchers.IO).launch { val r = runCatching { connect(login, password, server, broker) }; withContext(Dispatchers.Main) { onResult(r) } }

    suspend fun readLiveState(accountId: String, sessionToken: String): Result<LiveAccountState> = withContext(Dispatchers.IO) { runCatching {
        val request = Request.Builder().url("$baseUrl/api/mt5/servers?action=status&accountId=${URLEncoder.encode(accountId, "UTF-8")}").header("x-pipslife-session", sessionToken).get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty(); if (!response.isSuccessful) throw IllegalStateException(error(body, "Live account state unavailable")); parseState(JSONObject(body))
        }
    } }

    suspend fun setBotRunning(accountId: String, sessionToken: String, running: Boolean): Result<BotState> = withContext(Dispatchers.IO) { runCatching {
        val payload = JSONObject().apply { put("action", "bot"); put("accountId", accountId); put("running", running) }
        val request = Request.Builder().url("$baseUrl/api/mt5/servers").header("x-pipslife-session", sessionToken).post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty(); if (!response.isSuccessful) throw IllegalStateException(error(body, "Bot command failed")); parseBot(JSONObject(body))
        }
    } }

    private fun parseState(root: JSONObject): LiveAccountState {
        val a = root.optJSONObject("account") ?: JSONObject(); val i = root.optJSONObject("accountInformation") ?: JSONObject(); val ps = root.optJSONArray("positions") ?: JSONArray()
        return LiveAccountState(
            LiveAccount(a.optString("id"), a.optString("login"), a.optString("server"), a.optString("state"), a.optString("connectionStatus"), i.num("balance"), i.num("equity"), i.num("profit"), i.optString("currency")),
            buildList { for (n in 0 until ps.length()) { val p = ps.getJSONObject(n); add(LivePosition(p.optString("id"), p.optString("symbol"), p.optString("type"), p.optDouble("volume", 0.0), p.optDouble("openPrice", 0.0), p.optDouble("currentPrice", 0.0), p.optDouble("unrealizedProfit", p.optDouble("profit", 0.0)), p.num("stopLoss"), p.num("takeProfit"))) } },
            parseBot(root.optJSONObject("bot") ?: JSONObject())
        )
    }

    private fun parseBot(x: JSONObject) = BotState(x.optBoolean("running", false), x.optBoolean("controlAvailable", false), x.optString("strategy", "001"), x.optString("activity", "Backend connected"))
    private fun error(body: String, fallback: String) = runCatching { JSONObject(body).optString("error").ifBlank { fallback } }.getOrDefault(fallback)
}

private fun JSONObject.num(key: String): Double? = if (!has(key) || isNull(key)) null else optDouble(key)

data class Mt5ConnectionResult(val accountId: String, val sessionToken: String, val state: String, val server: String, val connectionStatus: String)
data class LiveAccount(val id: String, val login: String, val server: String, val state: String, val connectionStatus: String, val balance: Double?, val equity: Double?, val profit: Double?, val currency: String?)
data class LivePosition(val id: String, val symbol: String, val type: String, val volume: Double, val openPrice: Double, val currentPrice: Double, val unrealizedProfit: Double, val stopLoss: Double?, val takeProfit: Double?)
data class BotState(val running: Boolean, val controlAvailable: Boolean, val strategy: String, val activity: String)
data class LiveAccountState(val account: LiveAccount, val positions: List<LivePosition>, val bot: BotState)

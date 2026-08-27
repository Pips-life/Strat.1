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

class Mt5ApiClient(
    private val baseUrl: String = BuildConfig.BACKEND_BASE_URL.trimEnd('/'),
    private val http: OkHttpClient = OkHttpClient(),
) {
    suspend fun findServers(query: String): List<Mt5Server> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/mt5/servers?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(extractError(body, "Server discovery failed"))
            val brokers = JSONObject(body).optJSONArray("brokers") ?: JSONArray()
            buildList {
                for (i in 0 until brokers.length()) {
                    val broker = brokers.getJSONObject(i)
                    val brokerName = broker.optString("brokerName", "Unknown broker")
                    val serverName = broker.optString("serverName")
                    if (serverName.isNotBlank()) add(Mt5Server(broker.optString("id"), brokerName, serverName, broker.optString("environment", "real")))
                }
            }
        }
    }

    suspend fun connect(login: String, password: String, server: String, broker: String? = null): Mt5ConnectionResult = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("login", login); put("password", password); put("server", server); put("name", "Pips-life MT5 $login")
            if (!broker.isNullOrBlank()) put("broker", broker)
        }
        val request = Request.Builder().url("$baseUrl/api/mt5/servers")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(extractError(body, "MT5 connection failed"))
            val result = JSONObject(body)
            Mt5ConnectionResult(result.optString("accountId"), result.optString("state", "UNKNOWN"), result.optString("connectionStatus", "CONNECTING"), result.optString("server", server), result.optString("login", login))
        }
    }

    suspend fun dashboard(accountId: String): DashboardState = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/mt5/servers?action=status&accountId=${java.net.URLEncoder.encode(accountId, "UTF-8")}"
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(extractError(body, "Backend state unavailable"))
            parseDashboard(JSONObject(body), accountId)
        }
    }

    suspend fun positions(accountId: String): List<LivePosition> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/mt5/servers?action=positions&accountId=${java.net.URLEncoder.encode(accountId, "UTF-8")}"
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(extractError(body, "Positions unavailable"))
            parsePositions(JSONObject(body).optJSONArray("positions") ?: JSONArray())
        }
    }

    suspend fun setBotRunning(running: Boolean): BotState = withContext(Dispatchers.IO) {
        val json = JSONObject().apply { put("action", "bot"); put("running", running) }
        val request = Request.Builder().url("$baseUrl/api/mt5/servers")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(extractError(body, "Bot control unavailable"))
            parseBot(JSONObject(body))
        }
    }

    fun searchServers(query: String, onResult: (Result<List<Mt5Server>>) -> Unit) = CoroutineScope(Dispatchers.IO).launch { val result = runCatching { findServers(query) }; withContext(Dispatchers.Main) { onResult(result) } }
    fun connect(login: String, password: String, server: String, broker: String?, onResult: (Result<Mt5ConnectionResult>) -> Unit) = CoroutineScope(Dispatchers.IO).launch { val result = runCatching { connect(login, password, server, broker) }; withContext(Dispatchers.Main) { onResult(result) } }

    private fun parseDashboard(json: JSONObject, accountId: String): DashboardState {
        val account = json.optJSONObject("account") ?: JSONObject()
        val info = json.optJSONObject("accountInformation") ?: JSONObject()
        return DashboardState(
            connected = account.optString("connectionStatus").equals("CONNECTED", true),
            accountId = account.optString("id", accountId), accountLogin = account.optString("login"), server = account.optString("server"),
            connectionStatus = account.optString("connectionStatus", "UNKNOWN"),
            accountInformation = AccountInformation(info.optString("broker"), info.optString("currency"), info.optString("server"), info.optDouble("balance"), info.optDouble("equity"), info.optDouble("margin"), info.optDouble("freeMargin"), info.optInt("leverage"), info.optBoolean("tradeAllowed"), info.optLong("login")),
            positions = parsePositions(json.optJSONArray("positions") ?: JSONArray()),
            bot = parseBot(json.optJSONObject("bot") ?: JSONObject())
        )
    }

    private fun parseBot(json: JSONObject) = BotState(json.optBoolean("running"), json.optBoolean("controlAvailable"), json.optString("strategy", "001"), json.optString("activity", "UNKNOWN"))

    private fun parsePositions(array: JSONArray): List<LivePosition> = buildList {
        for (i in 0 until array.length()) {
            val p = array.getJSONObject(i); val type = p.optString("type")
            add(LivePosition(p.optString("id"), p.optString("symbol"), if (type.contains("BUY", true)) "BUY" else "SELL", p.optDouble("volume"), p.optDouble("openPrice"), p.optDouble("currentPrice"), if (p.has("stopLoss") && !p.isNull("stopLoss")) p.optDouble("stopLoss") else null, if (p.has("takeProfit") && !p.isNull("takeProfit")) p.optDouble("takeProfit") else null, p.optDouble("profit"), p.optDouble("unrealizedProfit", p.optDouble("profit"))))
        }
    }

    private fun extractError(body: String, fallback: String): String = runCatching { JSONObject(body).optString("error").ifBlank { fallback } }.getOrDefault(fallback)
}

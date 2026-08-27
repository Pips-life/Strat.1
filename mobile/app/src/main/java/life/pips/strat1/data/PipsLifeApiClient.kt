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

class PipsLifeApiClient(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL, private val http: OkHttpClient = OkHttpClient()) {
    suspend fun connect(login: String, password: String, server: String, broker: String): Result<Mt5LiveConnection> = withContext(Dispatchers.IO) { runCatching {
        val json = post("/api/mt5/servers", JSONObject().apply { put("login", login); put("password", password); put("server", server); put("name", "Pips-life MT5 $broker $login") })
        Mt5LiveConnection(json.optString("accountId").ifBlank { null }, json.optString("connectionStatus", "UNKNOWN"), json.optString("state", "UNKNOWN"), json.optString("server", server))
    } }
    suspend fun status(accountId: String): Result<BotSnapshot> = withContext(Dispatchers.IO) { runCatching {
        val json = get("/api/mt5/servers?action=status&accountId=${java.net.URLEncoder.encode(accountId, \"UTF-8\")}")
        BotSnapshot(json.getJSONObject(\"account\").optString(\"connectionStatus\") == \"CONNECTED\", json.getJSONObject(\"accountInformation\"), json.getJSONObject(\"bot\").optBoolean(\"running\"), json.getJSONObject(\"bot\").optBoolean(\"controlAvailable\"), json.getJSONObject(\"bot\").optString(\"activity\"))
    } }
    suspend fun positions(accountId: String): Result<List<LivePosition>> = withContext(Dispatchers.IO) { runCatching {
        val json = get("/api/mt5/servers?action=positions&accountId=${java.net.URLEncoder.encode(accountId, \"UTF-8\")}")
        val array = json.optJSONArray(\"positions\") ?: JSONArray()
        buildList { for (i in 0 until array.length()) { val p = array.getJSONObject(i); add(LivePosition(p.optString(\"symbol\", \"UNKNOWN\"), if (p.optString(\"type\").contains(\"BUY\")) \"BUY\" else \"SELL\", p.optDouble(\"volume\"), p.optDouble(\"openPrice\"), p.optDouble(\"currentPrice\"), p.optDouble(\"profit\"), p.optDouble(\"stopLoss\"), p.optDouble(\"takeProfit\"))) } }
    } }
    suspend fun setBotRunning(running: Boolean): Result<BotCommandResult> = withContext(Dispatchers.IO) { runCatching { val json = post(\"/api/mt5/servers\", JSONObject().apply { put(\"action\", \"bot\"); put(\"running\", running) }); BotCommandResult(json.optBoolean(\"running\", running), json.optString(\"activity\", \"COMMAND ACCEPTED\")) } }
    private fun get(path: String): JSONObject { val r = http.newCall(Request.Builder().url(baseUrl.trimEnd('/') + path).get().build()).execute(); r.use { val b = it.body?.string().orEmpty(); if (!it.isSuccessful) error(JSONObject(b).optString(\"error\", \"Backend request failed\")); return JSONObject(b) } }
    private fun post(path: String, payload: JSONObject): JSONObject { val r = http.newCall(Request.Builder().url(baseUrl.trimEnd('/') + path).post(payload.toString().toRequestBody(\"application/json\".toMediaType())).build()).execute(); r.use { val b = it.body?.string().orEmpty(); if (!it.isSuccessful) error(JSONObject(b).optString(\"error\", \"Backend command failed\")); return JSONObject(b) } }
}

data class Mt5LiveConnection(val accountId: String?, val connectionStatus: String, val state: String, val server: String)
data class BotSnapshot(val connected: Boolean, val account: JSONObject, val botRunning: Boolean, val controlAvailable: Boolean, val activity: String)
data class LivePosition(val symbol: String, val side: String, val volume: Double, val entry: Double, val current: Double, val profit: Double, val stopLoss: Double, val takeProfit: Double)
data class BotCommandResult(val running: Boolean, val activity: String)

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

class BackendApiClient(private val http: OkHttpClient = OkHttpClient()) {
    private val base = BuildConfig.BACKEND_BASE_URL.trimEnd('/')

    suspend fun connect(login: String, password: String, server: String, broker: String): Result<BackendSession> = runCatching {
        requestJson("POST", "/api/mt5/connect", JSONObject().apply { put("login", login); put("password", password); put("server", server); put("broker", broker); put("name", "Pips-life MT5 $login") }).toString()
            .let { json -> BackendSession(JSONObject(json).getString("accountId"), JSONObject(json).getString("sessionToken"), JSONObject(json).optString("server", server)) }
    }

    suspend fun liveState(session: BackendSession): Result<LiveState> = runCatching {
        val json = requestJson("GET", "/api/mt5/connect?accountId=${java.net.URLEncoder.encode(session.accountId, "UTF-8")}", null, session.token)
        val root = JSONObject(json); val info = root.optJSONObject("accountInformation") ?: JSONObject(); val positions = root.optJSONArray("positions") ?: JSONArray()
        LiveState(
            accountId = session.accountId,
            login = root.optJSONObject("account")?.optString("login").orEmpty(),
            server = root.optJSONObject("account")?.optString("server").orEmpty(),
            connectionStatus = root.optJSONObject("account")?.optString("connectionStatus").orEmpty(),
            state = root.optJSONObject("account")?.optString("state").orEmpty(),
            balance = info.optDouble("balance", Double.NaN), equity = info.optDouble("equity", Double.NaN), freeMargin = info.optDouble("freeMargin", Double.NaN), currency = info.optString("currency", ""), positions = parsePositions(positions)
        )
    }

    suspend fun botStatus(session: BackendSession): Result<BotState> = runCatching {
        val root = JSONObject(requestJson("GET", "/api/bot/control?accountId=${java.net.URLEncoder.encode(session.accountId, "UTF-8")}", null, session.token))
        BotState(root.optBoolean("configured", false), root.optString("state", "UNKNOWN"), root.optString("strategy", "001"))
    }

    suspend fun botCommand(session: BackendSession, action: String): Result<BotState> = runCatching {
        val root = JSONObject(requestJson("POST", "/api/bot/control", JSONObject().apply { put("action", action); put("strategy", "001"); put("accountId", session.accountId) }.toString(), session.token))
        BotState(root.optBoolean("configured", true), root.optString("state", action.uppercase()), root.optString("strategy", "001"))
    }

    private suspend fun requestJson(method: String, path: String, body: String?, sessionToken: String? = null): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(base + path).addHeader("Accept", "application/json")
        if (sessionToken != null) builder.addHeader("x-pipslife-session", sessionToken)
        if (body != null) builder.method(method, body.toRequestBody("application/json".toMediaType())) else builder.method(method, null)
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(JSONObject(text).optString("error").ifBlank { "Backend request failed (${response.code})" })
            text
        }
    }

    private fun parsePositions(array: JSONArray): List<LivePosition> = buildList {
        for (i in 0 until array.length()) { val p = array.getJSONObject(i); add(LivePosition(p.optString("symbol"), p.optString("type").removePrefix("POSITION_TYPE_"), p.optDouble("volume", 0.0), p.optDouble("openPrice", Double.NaN), p.optDouble("currentPrice", Double.NaN), p.optDouble("stopLoss", Double.NaN), p.optDouble("takeProfit", Double.NaN), p.optDouble("profit", Double.NaN))) }
    }
}

data class BackendSession(val accountId: String, val token: String, val server: String)
data class LiveState(val accountId: String, val login: String, val server: String, val connectionStatus: String, val state: String, val balance: Double, val equity: Double, val freeMargin: Double, val currency: String, val positions: List<LivePosition>)
data class LivePosition(val symbol: String, val side: String, val volume: Double, val entry: Double, val current: Double, val stopLoss: Double, val takeProfit: Double, val profit: Double)
data class BotState(val configured: Boolean, val state: String, val strategy: String)

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

class TradingApiClient(
    private val baseUrl: String = BuildConfig.BACKEND_BASE_URL,
    private val http: OkHttpClient = OkHttpClient(),
) {
    suspend fun snapshot(accountId: String): Result<TradingSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val response = http.newCall(Request.Builder().url("$baseUrl/api/mt5/accounts/${java.net.URLEncoder.encode(accountId, "UTF-8")}").get().build()).execute()
            response.use {
                val body = it.body?.string().orEmpty()
                if (!it.isSuccessful) error(extractError(body, "Account snapshot failed"))
                val root = JSONObject(body)
                val account = root.optJSONObject("account") ?: JSONObject()
                val info = root.optJSONObject("accountInformation")
                val positions = root.optJSONArray("positions") ?: JSONArray()
                TradingSnapshot(
                    accountId = account.optString("id", accountId),
                    login = account.optString("login", "—"),
                    server = account.optString("server", "—"),
                    state = account.optString("state", "UNKNOWN"),
                    connectionStatus = account.optString("connectionStatus", "UNKNOWN"),
                    broker = info?.optString("broker", "—") ?: "—",
                    currency = info?.optString("currency", "USD") ?: "USD",
                    balance = info?.optDouble("balance", Double.NaN) ?: Double.NaN,
                    equity = info?.optDouble("equity", Double.NaN) ?: Double.NaN,
                    floatingProfit = positionsSumProfit(positions),
                    positions = parsePositions(positions),
                )
            }
        }
    }

    suspend fun botStatus(): Result<BotStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val response = http.newCall(Request.Builder().url("$baseUrl/api/bot/control").get().build()).execute()
            response.use {
                val body = it.body?.string().orEmpty()
                val root = JSONObject(body)
                if (!it.isSuccessful && !root.optBoolean("configured", false)) error(root.optString("error", "Bot runner unavailable"))
                BotStatus(root.optBoolean("configured", false), root.optString("state", "UNKNOWN"), root.optString("strategy", "001"))
            }
        }
    }

    suspend fun setBot(action: String, accountId: String?): Result<BotStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject().apply { put("action", action); put("strategy", "001"); put("accountId", accountId ?: JSONObject.NULL) }
            val request = Request.Builder().url("$baseUrl/api/bot/control").post(json.toString().toRequestBody("application/json".toMediaType())).build()
            val response = http.newCall(request).execute()
            response.use {
                val body = it.body?.string().orEmpty()
                val root = JSONObject(body)
                if (!it.isSuccessful) error(root.optString("error", "Bot command rejected"))
                BotStatus(root.optBoolean("configured", true), root.optString("state", if (action == "start") "RUNNING" else "STOPPED"), root.optString("strategy", "001"))
            }
        }
    }

    private fun positionsSumProfit(items: JSONArray): Double = (0 until items.length()).sumOf { i -> items.optJSONObject(i)?.optDouble("profit", 0.0) ?: 0.0 }

    private fun parsePositions(items: JSONArray): List<TradingPosition> = buildList {
        for (i in 0 until items.length()) {
            val p = items.optJSONObject(i) ?: continue
            add(TradingPosition(
                symbol = p.optString("symbol", "—"),
                side = if (p.optString("type").contains("SELL")) "SELL" else "BUY",
                volume = p.optDouble("volume", 0.0),
                entry = p.optDouble("openPrice", Double.NaN),
                current = p.optDouble("currentPrice", Double.NaN),
                profit = p.optDouble("profit", 0.0),
                stopLoss = p.optDouble("stopLoss", Double.NaN),
                takeProfit = p.optDouble("takeProfit", Double.NaN),
            ))
        }
    }

    private fun extractError(body: String, fallback: String): String = runCatching { JSONObject(body).optString("error").ifBlank { fallback } }.getOrDefault(fallback)
}

data class TradingSnapshot(
    val accountId: String, val login: String, val server: String, val state: String,
    val connectionStatus: String, val broker: String, val currency: String,
    val balance: Double, val equity: Double, val floatingProfit: Double,
    val positions: List<TradingPosition>,
)

data class TradingPosition(
    val symbol: String, val side: String, val volume: Double, val entry: Double,
    val current: Double, val profit: Double, val stopLoss: Double, val takeProfit: Double,
)

data class BotStatus(val configured: Boolean, val state: String, val strategy: String)

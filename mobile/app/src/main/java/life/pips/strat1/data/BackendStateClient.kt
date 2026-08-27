package life.pips.strat1.data

import life.pips.strat1.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class BackendStateClient(
    private val baseUrl: String = BuildConfig.BACKEND_BASE_URL,
    private val http: OkHttpClient = OkHttpClient(),
) {
    suspend fun state(accountId: String): Result<BackendState> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$baseUrl/api/mt5/state?accountId=${java.net.URLEncoder.encode(accountId, "UTF-8")}").get().build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IllegalStateException(error(body, "Backend state request failed"))
                parseState(JSONObject(body))
            }
        }
    }

    private fun parseState(root: JSONObject): BackendState {
        val account = root.optJSONObject("account") ?: JSONObject()
        val positions = root.optJSONArray("positions") ?: JSONArray()
        val parsed = buildList {
            for (i in 0 until positions.length()) {
                val p = positions.getJSONObject(i)
                add(BackendPosition(
                    symbol = p.optString("symbol", "—"),
                    side = if (p.optString("type").contains("BUY", true)) "BUY" else "SELL",
                    volume = p.optDouble("volume", 0.0),
                    entry = p.optDouble("openPrice", Double.NaN),
                    current = p.optDouble("currentPrice", Double.NaN),
                    stopLoss = p.optDouble("stopLoss", Double.NaN),
                    takeProfit = p.optDouble("takeProfit", Double.NaN),
                    profit = p.optDouble("profit", 0.0),
                ))
            }
        }
        return BackendState(
            login = account.optString("login", "—"),
            server = account.optString("server", "—"),
            connectionStatus = account.optString("connectionStatus", "UNKNOWN"),
            state = account.optString("state", "UNKNOWN"),
            currency = account.optString("currency", "—"),
            balance = account.optDouble("balance", Double.NaN),
            equity = account.optDouble("equity", Double.NaN),
            tradeAllowed = account.optBoolean("tradeAllowed", false),
            positions = parsed,
            fetchedAt = root.optString("fetchedAt", ""),
        )
    }

    private fun error(body: String, fallback: String): String = runCatching {
        JSONObject(body).optString("error").ifBlank { fallback }
    }.getOrDefault(fallback)
}

data class BackendState(
    val login: String,
    val server: String,
    val connectionStatus: String,
    val state: String,
    val currency: String,
    val balance: Double,
    val equity: Double,
    val tradeAllowed: Boolean,
    val positions: List<BackendPosition>,
    val fetchedAt: String,
)

data class BackendPosition(
    val symbol: String,
    val side: String,
    val volume: Double,
    val entry: Double,
    val current: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val profit: Double,
)

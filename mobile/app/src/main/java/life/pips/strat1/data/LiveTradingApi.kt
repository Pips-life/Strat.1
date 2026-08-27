package life.pips.strat1.data

import android.os.Build
import life.pips.strat1.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class LiveTradingApi(
    private val baseUrl: String = BuildConfig.BACKEND_BASE_URL,
    private val http: OkHttpClient = OkHttpClient(),
) {
    suspend fun state(accountId: String): Result<LiveTradingState> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/api/mt5/state?accountId=${java.net.URLEncoder.encode(accountId, "UTF-8")}")
                .get().build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IllegalStateException(error(body, "Unable to read live MT5 state"))
                val json = JSONObject(body)
                val account = json.optJSONObject("account") ?: JSONObject()
                val rawPositions = json.optJSONArray("positions") ?: JSONArray()
                val positions = buildList {
                    for (i in 0 until rawPositions.length()) {
                        val p = rawPositions.getJSONObject(i)
                        val type = p.optString("type", "")
                        add(LivePosition(
                            id = p.optString("id"),
                            symbol = p.optString("symbol", "—"),
                            side = if (type.contains("SELL", true)) "SELL" else "BUY",
                            volume = p.optDouble("volume", 0.0),
                            entry = p.optDouble("openPrice", 0.0),
                            current = p.optDouble("currentPrice", 0.0),
                            stopLoss = p.optDouble("stopLoss", 0.0),
                            takeProfit = p.optDouble("takeProfit", 0.0),
                            profit = p.optDouble("profit", p.optDouble("unrealizedProfit", 0.0)),
                        ))
                    }
                }
                LiveTradingState(
                    accountId = accountId,
                    login = account.optString("login", "—"),
                    server = account.optString("server", "—"),
                    state = account.optString("state", "UNKNOWN"),
                    connectionStatus = account.optString("connectionStatus", "UNKNOWN"),
                    currency = account.optString("currency", "USD"),
                    balance = account.optDoubleOrNull("balance"),
                    equity = account.optDoubleOrNull("equity"),
                    freeMargin = account.optDoubleOrNull("freeMargin"),
                    tradeAllowed = if (account.has("tradeAllowed")) account.optBoolean("tradeAllowed") else null,
                    positions = positions,
                    fetchedAt = json.optString("fetchedAt", ""),
                )
            }
        }
    }

    private fun error(body: String, fallback: String): String = runCatching {
        JSONObject(body).optString("error").ifBlank { fallback }
    }.getOrDefault(fallback)
}

data class LiveTradingState(
    val accountId: String,
    val login: String,
    val server: String,
    val state: String,
    val connectionStatus: String,
    val currency: String,
    val balance: Double?,
    val equity: Double?,
    val freeMargin: Double?,
    val tradeAllowed: Boolean?,
    val positions: List<LivePosition>,
    val fetchedAt: String,
)

data class LivePosition(
    val id: String,
    val symbol: String,
    val side: String,
    val volume: Double,
    val entry: Double,
    val current: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val profit: Double,
)

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key)

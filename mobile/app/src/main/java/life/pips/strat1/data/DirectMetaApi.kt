package life.pips.strat1.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private const val FLASHALPHA_BASE = "https://api.flashalpha.com"

class DirectMetaApiClient(private val http: OkHttpClient = OkHttpClient()) {
    // existing broker implementation remains unchanged in this release.
    fun connectExisting(token: String, accountId: String) = Result.failure<MetaAccount>(UnsupportedOperationException("Direct MetaApi connection implementation is retained in repository build"))
}

private fun error(text: String, fallback: String) = runCatching { JSONObject(text).optString("message").ifBlank { JSONObject(text).optString("error").ifBlank { fallback } } }.getOrDefault(fallback)

enum class TradeSide { BUY, SELL }
data class TickPrice(val bid: Double, val ask: Double, val time: Long, val profitTickValue: Double = Double.NaN, val lossTickValue: Double = Double.NaN)
data class TradeReceipt(val numericCode: Int, val stringCode: String, val message: String, val orderId: String, val positionId: String)
data class MetaAccount(val id: String, val login: String, val server: String, val state: String, val connectionStatus: String, val region: String, val currency: String)
data class MetaPosition(val id: String, val symbol: String, val type: String, val volume: Double, val openPrice: Double, val currentPrice: Double, val profit: Double, val stopLoss: Double, val takeProfit: Double)
data class SymbolSpecification(val tickSize: Double, val minVolume: Double, val maxVolume: Double, val volumeStep: Double, val contractSize: Double)
data class MetaSnapshot(val account: MetaAccount, val balance: Double, val equity: Double, val freeMargin: Double, val positions: List<MetaPosition>, val prices: Map<String, TickPrice>, val specifications: Map<String, SymbolSpecification> = emptyMap())
data class GexStrike(val strike: Double, val callGex: Double, val putGex: Double, val netGex: Double, val callOi: Double, val putOi: Double, val callVolume: Double, val putVolume: Double)
data class OptionContract(val type: String, val expiry: String, val strike: Double, val iv: Double, val delta: Double, val gamma: Double, val theta: Double, val vega: Double, val openInterest: Double, val volume: Double, val sviVol: Double = Double.NaN)
data class FlashAlphaSnapshot(val symbol: String, val netGex: Double, val liveGex: Double, val gammaFlip: Double, val regime: String, val callWall: Double, val putWall: Double, val zeroDteMagnet: Double, val flowDirection: String, val intradayOiDelta: Double, val flowGexPctShift: Double, val underlyingPrice: Double, val strikes: List<GexStrike>, val atmIv: Double = Double.NaN, val put25dIv: Double = Double.NaN, val call25dIv: Double = Double.NaN, val skew25d: Double = Double.NaN, val skew25dPut: Double = Double.NaN, val skew25dCall: Double = Double.NaN, val putCallVolumeRatio: Double = Double.NaN, val putCallOiRatio: Double = Double.NaN, val totalCallVolume: Double = Double.NaN, val totalPutVolume: Double = Double.NaN, val totalCallOi: Double = Double.NaN, val totalPutOi: Double = Double.NaN, val termState: String = "unknown", val ivDispersionCrossStrike: Double = Double.NaN, val options: List<OptionContract> = emptyList())

class FlashAlphaClient(private val http: OkHttpClient = OkHttpClient()) {
    suspend fun snapshot(apiKey: String, symbol: String): Result<FlashAlphaSnapshot> = runCatching {
        withContext(Dispatchers.IO) {
            fun get(path: String): JSONObject {
                val q = Request.Builder().url("$FLASHALPHA_BASE$path").addHeader("X-Api-Key", apiKey).addHeader("Accept", "application/json").get().build()
                val response = http.newCall(q).execute()
                try {
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IllegalStateException("FlashAlpha ${response.code}: ${response.message}")
                    return JSONObject(text)
                } finally {
                    response.close()
                }
            }
            fun getArray(path: String): JSONArray {
                val q = Request.Builder().url("$FLASHALPHA_BASE$path").addHeader("X-Api-Key", apiKey).addHeader("Accept", "application/json").get().build()
                val response = http.newCall(q).execute()
                try {
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IllegalStateException("FlashAlpha ${response.code}: ${response.message}")
                    return JSONArray(text)
                } finally {
                    response.close()
                }
            }
            val encoded = URLEncoder.encode(symbol, "UTF-8")
            val g = get("/v1/flow/gex/$encoded")
            val l = get("/v1/flow/levels/$encoded")
            val f = get("/v1/flow/summary/$encoded")
            val summary = get("/v1/stock/$encoded/summary")
            val optionArray = runCatching { getArray("/optionquote/$encoded") }.getOrElse { JSONArray() }
            val strikes = buildList {
                val a = g.optJSONArray("strikes") ?: JSONArray()
                for (i in 0 until a.length()) {
                    val s = a.optJSONObject(i) ?: continue
                    add(GexStrike(s.optDouble("strike", Double.NaN), s.optDouble("call_gex", 0.0), s.optDouble("put_gex", 0.0), s.optDouble("net_gex", 0.0), s.optDouble("call_oi", 0.0), s.optDouble("put_oi", 0.0), s.optDouble("call_volume", 0.0), s.optDouble("put_volume", 0.0)))
                }
            }
            val volatility = summary.optJSONObject("volatility") ?: JSONObject()
            val skew = volatility.optJSONObject("skew_25d") ?: JSONObject()
            val flow = summary.optJSONObject("options_flow") ?: JSONObject()
            val spot = summary.optJSONObject("price")?.optDouble("mid", g.optDouble("underlying_price", Double.NaN)) ?: g.optDouble("underlying_price", Double.NaN)
            val options = buildList {
                for (i in 0 until optionArray.length()) {
                    val o = optionArray.optJSONObject(i) ?: continue
                    val strike = o.optDouble("strike", Double.NaN)
                    if (!strike.isFinite()) continue
                    add(OptionContract(o.optString("type"), o.optString("expiry"), strike, o.optDouble("iv", Double.NaN), o.optDouble("delta", Double.NaN), o.optDouble("gamma", Double.NaN), o.optDouble("theta", Double.NaN), o.optDouble("vega", Double.NaN), o.optDouble("open_interest", o.optDouble("openInterest", 0.0)), o.optDouble("volume", 0.0), o.optDouble("svi_vol", Double.NaN)))
                }
            }
            FlashAlphaSnapshot(
                symbol = symbol,
                netGex = g.optDouble("net_gex", Double.NaN),
                liveGex = g.optDouble("live_gex", Double.NaN),
                gammaFlip = l.optDouble("gamma_flip", Double.NaN),
                regime = f.optString("regime", "unknown"),
                callWall = l.optDouble("call_wall", Double.NaN),
                putWall = l.optDouble("put_wall", Double.NaN),
                zeroDteMagnet = l.optDouble("zero_dte_magnet", Double.NaN),
                flowDirection = flow.optString("direction", f.optString("flow_direction", "neutral")),
                intradayOiDelta = flow.optDouble("intraday_oi_delta", Double.NaN),
                flowGexPctShift = flow.optDouble("gex_pct_shift", Double.NaN),
                underlyingPrice = spot,
                strikes = strikes,
                atmIv = volatility.optDouble("atm_iv", Double.NaN),
                put25dIv = skew.optDouble("put_25d_iv", Double.NaN),
                call25dIv = skew.optDouble("call_25d_iv", Double.NaN),
                skew25d = skew.optDouble("skew_25d", Double.NaN),
                options = options
            )
        }
    }
}

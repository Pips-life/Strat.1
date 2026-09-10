package life.pips.strat1.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import kotlin.math.abs

private const val PROVISIONING_BASE = "https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai"
private const val FLASHALPHA_BASE = "https://lab.flashalpha.com"

class DirectMetaApiClient(private val http: OkHttpClient = OkHttpClient()) {
    private val specificationCache = mutableMapOf<String, SymbolSpecification>()

    suspend fun connectExisting(token: String, accountId: String): Result<MetaAccount> = runCatching {
        val a = request("GET", "$PROVISIONING_BASE/users/current/accounts/$accountId", null, token)
        MetaAccount(a.getString("_id"), a.optString("login"), a.optString("server"), a.optString("state"), a.optString("connectionStatus"), a.optString("region", "london"), a.optString("baseCurrency", ""))
    }

    suspend fun createAndDeploy(token: String, login: String, password: String, server: String, platform: String = "mt5"): Result<MetaAccount> = runCatching {
        require(token.isNotBlank() && login.isNotBlank() && password.isNotBlank() && server.isNotBlank()) { "MetaApi token, login, password and server are required" }
        val body = JSONObject().apply {
            put("login", login.trim()); put("password", password); put("name", "Pips-life $login"); put("server", server.trim())
            put("platform", platform); put("magic", 26091001); put("type", "cloud-g2"); put("manualTrades", true)
            put("quoteStreamingIntervalInSeconds", 0); put("reliability", "high")
        }
        val tx = UUID.randomUUID().toString().replace("-", "").take(32)
        val created = request("POST", "$PROVISIONING_BASE/users/current/accounts", body.toString(), token, mapOf("transaction-id" to tx))
        val id = created.optString("id").ifBlank { created.optString("_id") }.ifBlank { created.optString("accountId") }
        require(id.isNotBlank()) { "MetaApi did not return an account id" }
        request("POST", "$PROVISIONING_BASE/users/current/accounts/$id/deploy", null, token)
        connectExisting(token, id).getOrThrow()
    }

    suspend fun refresh(token: String, account: MetaAccount, symbols: List<String>): Result<MetaSnapshot> = runCatching {
        val current = connectExisting(token, account.id).getOrThrow()
        val root = "${clientBase(current.region)}/users/current/accounts/${current.id}"
        val info = request("GET", "$root/account-information", null, token)
        val positions = requestArray("GET", "$root/positions", null, token)
        val prices = mutableMapOf<String, TickPrice>()
        val specs = mutableMapOf<String, SymbolSpecification>()
        symbols.distinct().take(20).forEach { symbol ->
            if (symbol.isNotBlank()) runCatching {
                val encoded = URLEncoder.encode(symbol, "UTF-8")
                val p = request("GET", "$root/symbols/$encoded/current-price?keepSubscription=true", null, token)
                prices[symbol] = TickPrice(p.optDouble("bid", Double.NaN), p.optDouble("ask", Double.NaN), p.optLong("time", System.currentTimeMillis()), p.optDouble("profitTickValue", Double.NaN), p.optDouble("lossTickValue", Double.NaN))
                val cacheKey = "${current.id}:$symbol"
                val spec = specificationCache[cacheKey] ?: request("GET", "$root/symbols/$encoded/specification", null, token).let { s ->
                    SymbolSpecification(s.optDouble("tickSize", Double.NaN), s.optDouble("minVolume", 0.01), s.optDouble("maxVolume", 100.0), s.optDouble("volumeStep", 0.01), s.optDouble("contractSize", Double.NaN)).also { specificationCache[cacheKey] = it }
                }
                specs[symbol] = spec
            }
        }
        MetaSnapshot(current, info.optDouble("balance", Double.NaN), info.optDouble("equity", Double.NaN), info.optDouble("freeMargin", Double.NaN), buildList {
            for (i in 0 until positions.length()) {
                val p = positions.optJSONObject(i) ?: continue
                add(MetaPosition(p.optString("id").ifBlank { p.optString("positionId") }, p.optString("symbol"), p.optString("type"), p.optDouble("volume", 0.0), p.optDouble("openPrice", Double.NaN), p.optDouble("currentPrice", Double.NaN), p.optDouble("profit", Double.NaN), p.optDouble("stopLoss", Double.NaN), p.optDouble("takeProfit", Double.NaN)))
            }
        }, prices, specs)
    }

    suspend fun marketOrder(token: String, account: MetaAccount, side: TradeSide, symbol: String, volume: Double, stopLoss: Double? = null, takeProfit: Double? = null): Result<TradeReceipt> = trade(token, account, JSONObject().apply {
        put("actionType", if (side == TradeSide.BUY) "ORDER_TYPE_BUY" else "ORDER_TYPE_SELL"); put("symbol", symbol); put("volume", volume); put("clientId", clientId()); put("comment", "P1")
        stopLoss?.let { put("stopLoss", it) }; takeProfit?.let { put("takeProfit", it) }
    })

    suspend fun modifyPosition(token: String, account: MetaAccount, positionId: String, stopLoss: Double? = null, takeProfit: Double? = null): Result<TradeReceipt> = trade(token, account, JSONObject().apply {
        put("actionType", "POSITION_MODIFY"); put("positionId", positionId); put("clientId", clientId()); put("comment", "P1")
        stopLoss?.let { put("stopLoss", it) }; takeProfit?.let { put("takeProfit", it) }
    })

    suspend fun closePosition(token: String, account: MetaAccount, positionId: String): Result<TradeReceipt> = trade(token, account, JSONObject().apply {
        put("actionType", "POSITION_CLOSE_ID"); put("positionId", positionId); put("clientId", clientId()); put("comment", "P1")
    })

    private suspend fun trade(token: String, account: MetaAccount, body: JSONObject): Result<TradeReceipt> = runCatching {
        val r = request("POST", "${clientBase(account.region)}/users/current/accounts/${account.id}/trade", body.toString(), token)
        val code = r.optInt("numericCode", -1)
        val codeText = r.optString("stringCode")
        if (code >= 0 && code != 10009) throw IllegalStateException("MetaApi trade rejected: $codeText ${r.optString("message")}".trim())
        TradeReceipt(code, codeText, r.optString("message"), r.optString("orderId"), r.optString("positionId"))
    }

    private fun clientBase(region: String) = "https://mt-client-api-v1.${region.ifBlank { "london" }}.agiliumtrade.ai"
    private fun clientId() = "p1${UUID.randomUUID().toString().replace("-", "").take(10)}"

    private suspend fun request(method: String, url: String, body: String?, token: String, extra: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).addHeader("Accept", "application/json").addHeader("auth-token", token)
        extra.forEach { (k, v) -> b.addHeader(k, v) }
        if (body == null) b.method(method, null) else b.method(method, body.toRequestBody("application/json".toMediaType()))
        val response = http.newCall(b.build()).execute()
        try {
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful && response.code != 204) throw IllegalStateException(error(text, "MetaApi request failed (${response.code})"))
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            response.close()
        }
    }

    private suspend fun requestArray(method: String, url: String, body: String?, token: String): JSONArray = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).addHeader("Accept", "application/json").addHeader("auth-token", token)
        if (body == null) b.method(method, null) else b.method(method, body.toRequestBody("application/json".toMediaType()))
        val response = http.newCall(b.build()).execute()
        try {
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(error(text, "MetaApi request failed (${response.code})"))
            JSONArray(text)
        } finally {
            response.close()
        }
    }

    private fun error(text: String, fallback: String) = runCatching { JSONObject(text).optString("message").ifBlank { JSONObject(text).optString("error").ifBlank { fallback } } }.getOrDefault(fallback)
}

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
                    JSONObject(text)
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
                    JSONArray(text)
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
                    val type = o.optString("type", o.optString("option_type", ""))
                    val iv = o.optDouble("iv", o.optDouble("implied_vol", Double.NaN))
                    val oi = o.optDouble("oi", o.optDouble("open_interest", 0.0))
                    add(OptionContract(type, o.optString("expiry", ""), strike, iv, o.optDouble("delta", Double.NaN), o.optDouble("gamma", Double.NaN), o.optDouble("theta", Double.NaN), o.optDouble("vega", Double.NaN), oi, o.optDouble("volume", 0.0), o.optDouble("svi_vol", Double.NaN)))
                }
            }.sortedBy { abs(it.strike - spot) }.take(160)

            // FlashAlpha's documented convention: skew_25d = put_25d_iv - call_25d_iv.
            val put25 = skew.optDouble("put_25d_iv", Double.NaN)
            val call25 = skew.optDouble("call_25d_iv", Double.NaN)
            val skew25 = skew.optDouble("skew_25d", if (put25.isFinite() && call25.isFinite()) put25 - call25 else Double.NaN)
            val term = volatility.optJSONArray("iv_term_structure")?.optJSONObject(0)?.optString("state", "unknown") ?: "unknown"
            FlashAlphaSnapshot(
                symbol,
                g.optDouble("net_gex", Double.NaN),
                g.optDouble("live_net_gex", Double.NaN),
                l.optDouble("live_gamma_flip", Double.NaN),
                g.optString("live_net_gex_label", g.optString("regime", "unknown")),
                l.optDouble("live_call_wall", Double.NaN),
                l.optDouble("live_put_wall", Double.NaN),
                l.optDouble("live_max_pain", Double.NaN),
                f.optString("flow_direction", f.optString("direction", "")),
                f.optDouble("intraday_oi_delta", Double.NaN),
                f.optDouble("flow_gex_pct_shift", Double.NaN),
                spot,
                strikes,
                volatility.optDouble("atm_iv", Double.NaN),
                put25,
                call25,
                skew25,
                skew.optDouble("skew_25d_put", Double.NaN),
                skew.optDouble("skew_25d_call", Double.NaN),
                flow.optDouble("pc_ratio_volume", Double.NaN),
                flow.optDouble("pc_ratio_oi", Double.NaN),
                flow.optDouble("total_call_volume", Double.NaN),
                flow.optDouble("total_put_volume", Double.NaN),
                flow.optDouble("total_call_oi", Double.NaN),
                flow.optDouble("total_put_oi", Double.NaN),
                term,
                volatility.optDouble("iv_dispersion_cross_strike", Double.NaN),
                options
            )
        }
    }
}

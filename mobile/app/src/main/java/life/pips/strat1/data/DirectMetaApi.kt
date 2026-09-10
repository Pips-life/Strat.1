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

private const val PROVISIONING_BASE = "https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai"
private const val FLASHALPHA_BASE = "https://lab.flashalpha.com"

class DirectMetaApiClient(private val http: OkHttpClient = OkHttpClient()) {
    suspend fun connectExisting(token: String, accountId: String): Result<MetaAccount> = runCatching {
        val a = request("GET", "$PROVISIONING_BASE/users/current/accounts/$accountId", null, token)
        MetaAccount(a.getString("_id"), a.optString("login"), a.optString("server"), a.optString("state"), a.optString("connectionStatus"), a.optString("region", "london"), a.optString("baseCurrency", ""))
    }
    suspend fun createAndDeploy(token: String, login: String, password: String, server: String, platform: String = "mt5"): Result<MetaAccount> = runCatching {
        require(token.isNotBlank() && login.isNotBlank() && password.isNotBlank() && server.isNotBlank()) { "MetaApi token, login, password and server are required" }
        val body = JSONObject().apply { put("login", login.trim()); put("password", password); put("name", "Pips-life $login"); put("server", server.trim()); put("platform", platform); put("magic", 26091001); put("type", "cloud-g2"); put("manualTrades", true) }
        val tx = UUID.randomUUID().toString().replace("-", "").take(32)
        val created = request("POST", "$PROVISIONING_BASE/users/current/accounts", body.toString(), token, mapOf("transaction-id" to tx))
        val id = created.optString("id").ifBlank { created.optString("_id") }.ifBlank { created.optString("accountId") }
        require(id.isNotBlank()) { "MetaApi did not return an account id" }
        request("POST", "$PROVISIONING_BASE/users/current/accounts/$id/deploy", null, token)
        connectExisting(token, id).getOrThrow()
    }
    suspend fun refresh(token: String, account: MetaAccount, symbols: List<String>): Result<MetaSnapshot> = runCatching {
        val current = connectExisting(token, account.id).getOrThrow(); val root = "${clientBase(current.region)}/users/current/accounts/${current.id}"
        val info = request("GET", "$root/account-information", null, token); val positions = requestArray("GET", "$root/positions", null, token); val prices = mutableMapOf<String, TickPrice>()
        symbols.distinct().take(20).forEach { symbol -> if (symbol.isNotBlank()) runCatching { val p = request("GET", "$root/symbols/${URLEncoder.encode(symbol, "UTF-8")}/current-price", null, token); prices[symbol] = TickPrice(p.optDouble("bid", Double.NaN), p.optDouble("ask", Double.NaN), p.optLong("time", 0L)) } }
        MetaSnapshot(current, info.optDouble("balance", Double.NaN), info.optDouble("equity", Double.NaN), info.optDouble("freeMargin", Double.NaN), buildList { for (i in 0 until positions.length()) { val p = positions.optJSONObject(i) ?: continue; add(MetaPosition(p.optString("id").ifBlank { p.optString("positionId") }, p.optString("symbol"), p.optString("type"), p.optDouble("volume", 0.0), p.optDouble("openPrice", Double.NaN), p.optDouble("currentPrice", Double.NaN), p.optDouble("profit", Double.NaN), p.optDouble("stopLoss", Double.NaN), p.optDouble("takeProfit", Double.NaN))) } }, prices)
    }
    suspend fun candles(token: String, account: MetaAccount, symbol: String, timeframe: String = "1m", limit: Int = 20): Result<List<PriceCandle>> = runCatching {
        val url = "${clientBase(account.region)}/users/current/accounts/${account.id}/historical-market-data/symbols/${URLEncoder.encode(symbol, "UTF-8")}/timeframes/$timeframe/candles?limit=${limit.coerceIn(1, 100)}"; val a = requestArray("GET", url, null, token)
        buildList { for (i in 0 until a.length()) { val c = a.optJSONObject(i) ?: continue; add(PriceCandle(c.optLong("time"), c.optDouble("open"), c.optDouble("high"), c.optDouble("low"), c.optDouble("close"))) } }
    }
    suspend fun marketOrder(token: String, account: MetaAccount, side: TradeSide, symbol: String, volume: Double, stopLoss: Double? = null, takeProfit: Double? = null): Result<TradeReceipt> = trade(token, account, JSONObject().apply { put("actionType", if (side == TradeSide.BUY) "ORDER_TYPE_BUY" else "ORDER_TYPE_SELL"); put("symbol", symbol); put("volume", volume); put("clientId", clientId()); put("comment", "P1"); stopLoss?.let { put("stopLoss", it) }; takeProfit?.let { put("takeProfit", it) } })
    suspend fun modifyPosition(token: String, account: MetaAccount, positionId: String, stopLoss: Double? = null, takeProfit: Double? = null): Result<TradeReceipt> = trade(token, account, JSONObject().apply { put("actionType", "POSITION_MODIFY"); put("positionId", positionId); put("clientId", clientId()); put("comment", "P1"); stopLoss?.let { put("stopLoss", it) }; takeProfit?.let { put("takeProfit", it) } })
    suspend fun closePosition(token: String, account: MetaAccount, positionId: String): Result<TradeReceipt> = trade(token, account, JSONObject().apply { put("actionType", "POSITION_CLOSE_ID"); put("positionId", positionId); put("clientId", clientId()); put("comment", "P1") })
    private suspend fun trade(token: String, account: MetaAccount, body: JSONObject): Result<TradeReceipt> = runCatching { val r = request("POST", "${clientBase(account.region)}/users/current/accounts/${account.id}/trade", body.toString(), token); val code = r.optInt("numericCode", -1); val codeText = r.optString("stringCode"); if (code >= 0 && code != 10009) throw IllegalStateException("MetaApi trade rejected: $codeText ${r.optString("message")}".trim()); TradeReceipt(code, codeText, r.optString("message"), r.optString("orderId"), r.optString("positionId")) }
    private fun clientBase(region: String) = "https://mt-client-api-v1.${region.ifBlank { "london" }}.agiliumtrade.ai"
    private fun clientId() = "p1${UUID.randomUUID().toString().replace("-", "").take(10)}"
    private suspend fun request(method: String, url: String, body: String?, token: String, extra: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) { val b = Request.Builder().url(url).addHeader("Accept", "application/json").addHeader("auth-token", token); extra.forEach { (k, v) -> b.addHeader(k, v) }; if (body == null) b.method(method, null) else b.method(method, body.toRequestBody("application/json".toMediaType())); http.newCall(b.build()).execute().use { r -> val text = r.body?.string().orEmpty(); if (!r.isSuccessful && r.code != 204) throw IllegalStateException(error(text, "MetaApi request failed (${r.code})")); if (text.isBlank()) JSONObject() else JSONObject(text) } }
    private suspend fun requestArray(method: String, url: String, body: String?, token: String): JSONArray = withContext(Dispatchers.IO) { val b = Request.Builder().url(url).addHeader("Accept", "application/json").addHeader("auth-token", token); if (body == null) b.method(method, null) else b.method(method, body.toRequestBody("application/json".toMediaType())); http.newCall(b.build()).execute().use { r -> val text = r.body?.string().orEmpty(); if (!r.isSuccessful) throw IllegalStateException(error(text, "MetaApi request failed (${r.code})")); JSONArray(text) } }
    private fun error(text: String, fallback: String) = runCatching { JSONObject(text).optString("message").ifBlank { JSONObject(text).optString("error").ifBlank { fallback } } }.getOrDefault(fallback)
}

enum class TradeSide { BUY, SELL }
data class TickPrice(val bid: Double, val ask: Double, val time: Long)
data class PriceCandle(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double)
data class TradeReceipt(val numericCode: Int, val stringCode: String, val message: String, val orderId: String, val positionId: String)
data class MetaAccount(val id: String, val login: String, val server: String, val state: String, val connectionStatus: String, val region: String, val currency: String)
data class MetaPosition(val id: String, val symbol: String, val type: String, val volume: Double, val openPrice: Double, val currentPrice: Double, val profit: Double, val stopLoss: Double, val takeProfit: Double)
data class MetaSnapshot(val account: MetaAccount, val balance: Double, val equity: Double, val freeMargin: Double, val positions: List<MetaPosition>, val prices: Map<String, TickPrice>)
data class FlashAlphaSnapshot(val symbol: String, val netGex: Double, val liveGex: Double, val gammaFlip: Double, val regime: String, val callWall: Double, val putWall: Double, val zeroDteMagnet: Double, val flowDirection: String, val intradayOiDelta: Double, val flowGexPctShift: Double)

class FlashAlphaClient(private val http: OkHttpClient = OkHttpClient()) {
    suspend fun snapshot(apiKey: String, symbol: String): Result<FlashAlphaSnapshot> = runCatching { withContext(Dispatchers.IO) {
        fun get(path: String): JSONObject { val q = Request.Builder().url("$FLASHALPHA_BASE$path").addHeader("X-Api-Key", apiKey).addHeader("Accept", "application/json").get().build(); http.newCall(q).execute().use { r -> val text = r.body?.string().orEmpty(); if (!r.isSuccessful) throw IllegalStateException("FlashAlpha ${r.code}: ${r.message}"); return@use JSONObject(text) } }
        val g = get("/v1/flow/gex/$symbol"); val l = get("/v1/flow/levels/$symbol"); val f = get("/v1/flow/summary/$symbol")
        FlashAlphaSnapshot(symbol, g.optDouble("net_gex", Double.NaN), g.optDouble("live_net_gex", Double.NaN), l.optDouble("live_gamma_flip", Double.NaN), g.optString("live_net_gex_label", g.optString("regime", "unknown")), l.optDouble("live_call_wall", Double.NaN), l.optDouble("live_put_wall", Double.NaN), l.optDouble("live_max_pain", Double.NaN), f.optString("flow_direction", f.optString("direction", "")), f.optDouble("intraday_oi_delta", Double.NaN), f.optDouble("flow_gex_pct_shift", Double.NaN))
    } }
}

package life.pips.strat1.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val PROVISIONING_BASE = "https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai"
private const val FLASHALPHA_BASE = "https://lab.flashalpha.com"

class DirectMetaApiClient(private val http: OkHttpClient = OkHttpClient()) {
    suspend fun connectExisting(token: String, accountId: String): Result<MetaAccount> = runCatching {
        val account = request("GET", "$PROVISIONING_BASE/users/current/accounts/$accountId", null, token)
        MetaAccount(
            id = account.getString("_id"), login = account.optString("login"), server = account.optString("server"),
            state = account.optString("state"), connectionStatus = account.optString("connectionStatus"),
            region = account.optString("region", "london"), currency = account.optString("baseCurrency", "")
        )
    }

    suspend fun createAndDeploy(token: String, login: String, password: String, server: String, platform: String = "mt5"): Result<MetaAccount> = runCatching {
        val body = JSONObject().apply {
            put("login", login.trim())
            put("password", password)
            put("name", "Pips-life $login")
            put("server", server.trim())
            put("platform", platform)
            put("magic", 26091001)
            put("type", "cloud-g2")
            put("manualTrades", true)
        }
        val created = request("POST", "$PROVISIONING_BASE/users/current/accounts", body.toString(), token, mapOf("transaction-id" to UUID.randomUUID().toString().replace("-", "")))
        val id = created.getString("id").ifBlank { created.getString("_id") }
        request("POST", "$PROVISIONING_BASE/users/current/accounts/$id/deploy", null, token)
        connectExisting(token, id).getOrThrow()
    }

    suspend fun refresh(token: String, account: MetaAccount): Result<MetaSnapshot> = runCatching {
        val current = connectExisting(token, account.id).getOrThrow()
        val clientBase = "https://mt-client-api-v1.${current.region.ifBlank { "london" }}.agiliumtrade.ai"
        val accountInfo = request("GET", "$clientBase/users/current/accounts/${current.id}/account-information", null, token)
        val positions = requestArray("GET", "$clientBase/users/current/accounts/${current.id}/positions", null, token)
        val symbols = listOf("XAUUSD", "NAS100", "EURUSD", "GBPUSD", "US30")
        val prices = mutableMapOf<String, Double>()
        for (symbol in symbols) {
            runCatching {
                val p = request("GET", "$clientBase/users/current/accounts/${current.id}/symbols/${java.net.URLEncoder.encode(symbol, "UTF-8")}/current-price", null, token)
                val price = p.optDouble("ask", Double.NaN).takeIf { !it.isNaN() } ?: p.optDouble("bid", Double.NaN)
                if (!price.isNaN()) prices[symbol] = price
            }
        }
        MetaSnapshot(
            account = current.copy(connectionStatus = current.connectionStatus),
            balance = accountInfo.optDouble("balance", Double.NaN),
            equity = accountInfo.optDouble("equity", Double.NaN),
            freeMargin = accountInfo.optDouble("freeMargin", Double.NaN),
            positions = buildList {
                for (i in 0 until positions.length()) {
                    val p = positions.optJSONObject(i) ?: continue
                    add(MetaPosition(p.optString("symbol"), p.optString("type"), p.optDouble("volume", 0.0), p.optDouble("openPrice", Double.NaN), p.optDouble("currentPrice", Double.NaN), p.optDouble("profit", Double.NaN)))
                }
            },
            prices = prices
        )
    }

    private suspend fun request(method: String, url: String, body: String?, token: String, extra: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).addHeader("Accept", "application/json").addHeader("auth-token", token)
        extra.forEach { (k, v) -> b.addHeader(k, v) }
        if (body == null) b.method(method, null) else b.method(method, body.toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful && r.code != 204) throw IllegalStateException(error(text, "MetaApi request failed (${r.code})"))
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private suspend fun requestArray(method: String, url: String, body: String?, token: String): JSONArray = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).addHeader("Accept", "application/json").addHeader("auth-token", token)
        if (body == null) b.method(method, null) else b.method(method, body.toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException(error(text, "MetaApi request failed (${r.code})"))
            JSONArray(text)
        }
    }

    private fun error(text: String, fallback: String): String = runCatching { JSONObject(text).optString("message").ifBlank { JSONObject(text).optString("error").ifBlank { fallback } } }.getOrDefault(fallback)
}

class FlashAlphaClient(private val http: OkHttpClient = OkHttpClient()) {
    suspend fun snapshot(apiKey: String, symbol: String): Result<FlashAlphaSnapshot> = runCatching {
        withContext(Dispatchers.IO) {
            fun get(path: String): JSONObject {
                val request = Request.Builder().url("$FLASHALPHA_BASE$path").addHeader("X-Api-Key", apiKey).addHeader("Accept", "application/json").get().build()
                http.newCall(request).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (!r.isSuccessful) throw IllegalStateException("FlashAlpha ${r.code}: ${r.message}")
                    JSONObject(text)
                }
            }
            val gex = get("/v1/exposure/gex/$symbol")
            val levels = get("/v1/exposure/levels/$symbol")
            val flow = get("/v1/flow/summary/$symbol")
            FlashAlphaSnapshot(
                symbol = symbol,
                netGex = gex.optDouble("net_gex", Double.NaN),
                liveGex = gex.optDouble("live_gex", Double.NaN),
                gammaFlip = gex.optDouble("gamma_flip", Double.NaN),
                regime = gex.optString("regime", "unknown"),
                callWall = levels.optDouble("call_wall", Double.NaN),
                putWall = levels.optDouble("put_wall", Double.NaN),
                zeroDteMagnet = levels.optDouble("zero_dte_magnet", Double.NaN),
                flowDirection = flow.optString("direction", flow.optString("flow_direction", "")),
                intradayOiDelta = flow.optDouble("intraday_oi_delta", Double.NaN),
                flowGexPctShift = flow.optDouble("flow_gex_pct_shift", Double.NaN)
            )
        }
    }
}

data class MetaAccount(val id: String, val login: String, val server: String, val state: String, val connectionStatus: String, val region: String, val currency: String)
data class MetaPosition(val symbol: String, val type: String, val volume: Double, val openPrice: Double, val currentPrice: Double, val profit: Double)
data class MetaSnapshot(val account: MetaAccount, val balance: Double, val equity: Double, val freeMargin: Double, val positions: List<MetaPosition>, val prices: Map<String, Double>)
data class FlashAlphaSnapshot(val symbol: String, val netGex: Double, val liveGex: Double, val gammaFlip: Double, val regime: String, val callWall: Double, val putWall: Double, val zeroDteMagnet: Double, val flowDirection: String, val intradayOiDelta: Double, val flowGexPctShift: Double)

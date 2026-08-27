package life.pips.strat1.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class Mt5ApiClient(
    private val baseUrl: String = BuildConfig.BACKEND_BASE_URL,
    private val http: OkHttpClient = OkHttpClient(),
) {
    suspend fun findServers(query: String): List<Mt5Server> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/mt5/servers?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw IllegalStateException(extractError(body, "Server discovery failed"))
            val brokers = JSONObject(body).optJSONArray("brokers") ?: JSONArray()
            buildList {
                for (i in 0 until brokers.length()) {
                    val broker = brokers.getJSONObject(i)
                    val brokerName = broker.optString("broker")
                    val servers = broker.optJSONArray("servers") ?: JSONArray()
                    for (j in 0 until servers.length()) {
                        val serverName = servers.optString(j)
                        add(Mt5Server("$brokerName:$serverName", brokerName, serverName, if (serverName.contains("demo", true)) "demo" else "real"))
                    }
                }
            }
        }
    }

    suspend fun connect(login: String, password: String, server: String): Mt5ConnectionResult = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("login", login)
            put("password", password)
            put("server", server)
            put("name", "Strat.1 MT5 $login")
        }
        val request = Request.Builder().url("$baseUrl/api/mt5/connect")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw IllegalStateException(extractError(body, "MT5 connection failed"))
            val result = JSONObject(body)
            Mt5ConnectionResult(result.optString("accountId").ifBlank { null }, result.optString("state", "UNKNOWN"), result.optString("server", server))
        }
    }

    private fun extractError(body: String, fallback: String): String = runCatching {
        JSONObject(body).optString("error").ifBlank { fallback }
    }.getOrDefault(fallback)
}

data class Mt5ConnectionResult(val accountId: String?, val state: String, val server: String)

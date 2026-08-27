package life.pips.strat1.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class Mt5Api(private val baseUrl: String = "https://strat-1.vercel.app", private val client: OkHttpClient = OkHttpClient()) {
    suspend fun searchServers(query: String): List<Mt5Server> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.toString())
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/mt5/servers?q=$encoded").get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException("Server discovery failed (${response.code})", body)
            val brokers = JSONObject(body).optJSONArray("brokers") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until brokers.length()) {
                    val broker = brokers.getJSONObject(i)
                    val brokerName = broker.optString("broker")
                    val names = broker.optJSONArray("servers") ?: continue
                    for (j in 0 until names.length()) {
                        val serverName = names.optString(j)
                        if (serverName.isNotBlank()) add(Mt5Server("$brokerName:$serverName", brokerName, serverName, inferEnvironment(serverName)))
                    }
                }
            }
        }
    }

    suspend fun connect(data: Mt5ConnectionRequest): Mt5ConnectionResult = withContext(Dispatchers.IO) {
        val json = JSONObject().put("login", data.accountNumber).put("password", data.password).put("server", data.serverId).put("name", "Strat.1 MT5 account")
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/mt5/connect")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val parsed = runCatching { JSONObject(body) }.getOrNull()
            if (!response.isSuccessful) {
                val message = parsed?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: parsed?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: "MT5 connection failed (${response.code})"
                throw ApiException(message, body)
            }
            Mt5ConnectionResult(parsed?.optString("accountId")?.takeIf { it.isNotBlank() && it != "null" }, parsed?.optString("state") ?: "UNKNOWN", parsed?.optString("server") ?: data.serverId)
        }
    }

    private fun inferEnvironment(server: String) = if (server.contains("demo", true)) "Demo" else "Live/Other"
}

data class Mt5ConnectionResult(val accountId: String?, val state: String, val server: String)
class ApiException(message: String, val responseBody: String = "") : Exception(message)

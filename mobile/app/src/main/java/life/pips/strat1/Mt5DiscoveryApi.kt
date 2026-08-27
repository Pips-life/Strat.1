package life.pips.strat1

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class Mt5DiscoveryApi(
    private val baseUrl: String = BuildConfig.BACKEND_BASE_URL,
    private val client: OkHttpClient = OkHttpClient()
) {
    data class ServerGroup(val broker: String, val servers: List<String>)
    data class ConnectionResult(val accountId: String?, val state: String, val server: String)

    suspend fun search(brokerQuery: String): List<ServerGroup> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/mt5/servers".toHttpUrl().newBuilder()
            .addQueryParameter("q", brokerQuery.trim()).build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Server discovery failed (${response.code})")
            val brokers = JSONObject(body).optJSONArray("brokers") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until brokers.length()) {
                    val item = brokers.getJSONObject(i)
                    val servers = item.optJSONArray("servers") ?: continue
                    add(ServerGroup(item.optString("broker"), buildList {
                        for (j in 0 until servers.length()) add(servers.getString(j))
                    }))
                }
            }
        }
    }

    suspend fun connect(login: String, password: String, server: String): ConnectionResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("login", login); put("password", password); put("server", server)
        }.toString()
        val request = Request.Builder()
            .url("$baseUrl/api/mt5/connect")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (!response.isSuccessful) {
                val message = json?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: json?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: "MT5 connection failed (${response.code})"
                throw IllegalStateException(message)
            }
            ConnectionResult(
                accountId = json?.optString("accountId")?.takeIf { it.isNotBlank() && it != "null" },
                state = json?.optString("state") ?: "UNKNOWN",
                server = json?.optString("server") ?: server
            )
        }
    }
}

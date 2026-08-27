package life.pips.strat1.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class Mt5ServerRepository(
    private val baseUrl: String = BuildConfig.BACKEND_BASE_URL,
    private val client: OkHttpClient = OkHttpClient()
) {
    suspend fun search(query: String): List<Mt5Server> = withContext(Dispatchers.IO) {
        if (query.trim().length < 2) return@withContext emptyList()
        val url = baseUrl.trimEnd('/').plus("/api/mt5/servers").toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim()).build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Server discovery failed: HTTP ${response.code}")
            val root = JSONObject(response.body?.string().orEmpty())
            val brokers = root.optJSONArray("brokers") ?: JSONArray()
            buildList {
                for (i in 0 until brokers.length()) {
                    val item = brokers.getJSONObject(i)
                    val broker = item.optString("broker")
                    val servers = item.optJSONArray("servers") ?: JSONArray()
                    for (j in 0 until servers.length()) add(Mt5Server(broker, servers.getString(j)))
                }
            }
        }
    }

    suspend fun connect(login: String, password: String, server: String, broker: String): Mt5Connection = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/').plus("/api/mt5/connect").toHttpUrl()
        val payload = JSONObject().apply {
            put("login", login)
            put("password", password)
            put("server", server)
            put("broker", broker)
        }
        val request = Request.Builder().url(url)
            .post(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), payload.toString()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
            if (!response.isSuccessful && response.code != 202) error(body.optString("error", "MT5 connection failed"))
            Mt5Connection(
                accountId = body.optString("accountId").takeIf { it.isNotBlank() },
                server = body.optString("server", server),
                state = body.optString("state", "PROCESSING")
            )
        }
    }
}

data class Mt5Connection(val accountId: String?, val server: String, val state: String)

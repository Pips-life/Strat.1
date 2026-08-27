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
                    for (j in 0 until servers.length()) {
                        add(Mt5Server(broker = broker, name = servers.getString(j)))
                    }
                }
            }
        }
    }
}

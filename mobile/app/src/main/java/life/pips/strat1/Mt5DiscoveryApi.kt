package life.pips.strat1

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class Mt5DiscoveryApi(
    private val baseUrl: String = BuildConfig.BACKEND_BASE_URL,
    private val client: OkHttpClient = OkHttpClient()
) {
    data class ServerGroup(val broker: String, val servers: List<String>)

    fun search(brokerQuery: String): List<ServerGroup> {
        val url = "$baseUrl/api/mt5/servers".toHttpUrl().newBuilder()
            .addQueryParameter("query", brokerQuery.trim())
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Server discovery failed (${response.code})")
            val json = JSONObject(response.body?.string().orEmpty())
            val brokers = json.optJSONArray("brokers") ?: return emptyList()
            return buildList {
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
}

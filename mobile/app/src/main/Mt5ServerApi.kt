package life.pips.strat1.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class Mt5ServerApi(
    private val baseUrl: String = "https://strat-1.vercel.app"
) {
    private val client = OkHttpClient()

    suspend fun search(query: String): List<Mt5Server> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/mt5-servers".toHttpUrl().newBuilder()
            .addQueryParameter("query", query.trim())
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Server discovery failed (${response.code})")
            val root = JSONObject(response.body?.string() ?: "{}")
            val rows = root.optJSONArray("servers") ?: JSONArray()
            buildList {
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    add(
                        Mt5Server(
                            id = row.getString("id"),
                            brokerName = row.getString("brokerName"),
                            serverName = row.getString("serverName"),
                            environment = row.getString("environment")
                        )
                    )
                }
            }
        }
    }
}

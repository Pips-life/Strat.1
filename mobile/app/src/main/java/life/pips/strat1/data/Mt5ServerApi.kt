package life.pips.strat1.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class Mt5ServerApi(private val baseUrl: String, private val client: OkHttpClient = OkHttpClient()) {
    suspend fun search(broker: String): Result<List<Mt5Server>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$baseUrl/api/mt5".toHttpUrl().newBuilder()
                .addQueryParameter("query", broker.trim()).build()
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Server discovery failed: HTTP ${response.code}")
                val root = JSONObject(response.body?.string().orEmpty())
                val results = mutableListOf<Mt5Server>()
                root.keys().forEach { brokerName ->
                    val servers = root.optJSONArray(brokerName) ?: return@forEach
                    for (i in 0 until servers.length()) {
                        val name = servers.optString(i)
                        if (name.isNotBlank()) results += Mt5Server("$brokerName:$name", brokerName, name)
                    }
                }
                results
            }
        }
    }
}

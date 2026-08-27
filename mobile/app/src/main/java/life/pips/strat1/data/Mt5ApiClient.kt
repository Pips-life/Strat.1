package life.pips.strat1.data

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class Mt5ApiClient(
    private val baseUrl: String = "https://strat-1.vercel.app",
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    fun searchServers(query: String, callback: (Result<List<Mt5Server>>) -> Unit) {
        val q = query.trim()
        if (q.length < 2) {
            callback(Result.success(emptyList()))
            return
        }

        val url = baseUrl.trimEnd('/') + "/api/mt5/servers?q=" + java.net.URLEncoder.encode(q, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .build()

        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        callback(Result.failure(IOException("Server discovery failed (${it.code})")))
                        return
                    }
                    try {
                        val root = JSONObject(body)
                        val brokers = root.optJSONArray("brokers") ?: JSONArray()
                        val result = buildList {
                            for (i in 0 until brokers.length()) {
                                val broker = brokers.getJSONObject(i)
                                val brokerName = broker.optString("broker")
                                val servers = broker.optJSONArray("servers") ?: JSONArray()
                                for (j in 0 until servers.length()) {
                                    val server = servers.optString(j)
                                    if (server.isNotBlank()) {
                                        add(Mt5Server(
                                            id = "$brokerName:$server",
                                            broker = brokerName,
                                            name = server
                                        ))
                                    }
                                }
                            }
                        }
                        callback(Result.success(result))
                    } catch (e: Exception) {
                        callback(Result.failure(IOException("Invalid server discovery response", e)))
                    }
                }
            }
        })
    }
}

package life.pips.strat1.data

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Mt5ConnectionResult(val accountId: String?, val state: String, val server: String)

class Mt5ApiClient(
    private val baseUrl: String = "https://strat-1.vercel.app",
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    fun searchServers(query: String, callback: (Result<List<Mt5Server>>) -> Unit) {
        val q = query.trim()
        if (q.length < 2) { callback(Result.success(emptyList())); return }
        val url = baseUrl.trimEnd('/') + "/api/mt5/servers?q=" + URLEncoder.encode(q, "UTF-8")
        val request = Request.Builder().url(url).get().header("Accept", "application/json").build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    if (!it.isSuccessful) { callback(Result.failure(IOException("Server discovery failed (${it.code})"))); return }
                    try {
                        val brokers = JSONObject(raw).optJSONArray("brokers") ?: JSONArray()
                        callback(Result.success(buildList {
                            for (i in 0 until brokers.length()) {
                                val brokerObject = brokers.getJSONObject(i)
                                val broker = brokerObject.optString("broker")
                                val servers = brokerObject.optJSONArray("servers") ?: JSONArray()
                                for (j in 0 until servers.length()) {
                                    val server = servers.optString(j)
                                    if (server.isNotBlank()) add(
                                        Mt5Server(
                                            id = "$broker:$server",
                                            brokerName = broker,
                                            serverName = server,
                                            environment = if (server.contains("demo", true)) "demo" else "live"
                                        )
                                    )
                                }
                            }
                        }))
                    } catch (e: Exception) { callback(Result.failure(IOException("Invalid server discovery response", e))) }
                }
            }
        })
    }

    fun connect(login: String, password: String, server: String, callback: (Result<Mt5ConnectionResult>) -> Unit) {
        val payload = JSONObject().apply {
            put("login", login.trim())
            put("password", password)
            put("server", server.trim())
            put("name", "Strat.1 MT5 account")
        }
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/mt5/connect")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    try {
                        val json = JSONObject(raw)
                        if (!it.isSuccessful) {
                            val details = json.optJSONObject("details")
                            val message = details?.optString("message")?.takeIf { value -> value.isNotBlank() }
                                ?: json.optString("error").ifBlank { "MT5 connection failed" }
                            callback(Result.failure(IOException(message))); return
                        }
                        callback(Result.success(
                            Mt5ConnectionResult(
                                json.optString("accountId").ifBlank { null },
                                json.optString("state", "UNKNOWN"),
                                json.optString("server", server)
                            )
                        ))
                    } catch (e: Exception) { callback(Result.failure(IOException("Invalid MT5 connection response", e))) }
                }
            }
        })
    }
}

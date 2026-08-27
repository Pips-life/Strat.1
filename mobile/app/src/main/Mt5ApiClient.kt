package life.pips.strat1.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Mobile client for Strat.1 backend. The MetaApi token never ships in the APK. */
class Mt5ApiClient(
    private val baseUrl: String = "https://strat-1.vercel.app",
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    fun searchServers(query: String, callback: (Result<List<Mt5Server>>) -> Unit) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val request = Request.Builder().url("$baseUrl/api/mt5/servers?q=$encoded").get().build()
        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        val message = runCatching { JSONObject(text).optString("error") }
                            .getOrDefault("").ifBlank { "Server discovery failed (${it.code})" }
                        return callback(Result.failure(IOException(message)))
                    }
                    try {
                        val brokers = JSONObject(text).optJSONArray("brokers") ?: return callback(Result.success(emptyList()))
                        val result = mutableListOf<Mt5Server>()
                        for (i in 0 until brokers.length()) {
                            val brokerObject = brokers.getJSONObject(i)
                            val broker = brokerObject.getString("broker")
                            val servers = brokerObject.optJSONArray("servers") ?: continue
                            for (j in 0 until servers.length()) {
                                val server = servers.getString(j)
                                result += Mt5Server("$broker:$server", broker, server, inferEnvironment(server))
                            }
                        }
                        callback(Result.success(result))
                    } catch (e: Exception) { callback(Result.failure(e)) }
                }
            }
        })
    }

    fun connect(login: String, password: String, broker: String, server: String,
                callback: (Result<Mt5ConnectionResult>) -> Unit) {
        val payload = JSONObject().apply {
            put("login", login.trim())
            put("password", password)
            put("broker", broker.trim())
            put("server", server.trim())
            put("name", "Strat.1 MT5 account")
        }
        val request = Request.Builder()
            .url("$baseUrl/api/mt5/connect")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    try {
                        val root = JSONObject(text)
                        if (it.code == 202) {
                            callback(Result.success(Mt5ConnectionResult(
                                accountId = root.optString("accountId", ""),
                                state = root.optString("state", "PROCESSING"),
                                server = root.optString("server", server)
                            )))
                        } else if (!it.isSuccessful) {
                            callback(Result.failure(IOException(root.optString("error", "MT5 connection failed"))))
                        } else {
                            callback(Result.success(Mt5ConnectionResult(
                                accountId = root.optString("accountId", ""),
                                state = root.optString("state", "UNKNOWN"),
                                server = root.optString("server", server)
                            )))
                        }
                    } catch (e: Exception) { callback(Result.failure(e)) }
                }
            }
        })
    }

    private fun inferEnvironment(server: String): String = when {
        server.contains("demo", ignoreCase = true) -> "DEMO"
        server.contains("test", ignoreCase = true) -> "TEST"
        else -> "LIVE/UNKNOWN"
    }
}

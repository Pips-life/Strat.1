package life.pips.strat1.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Mobile client for Strat.1 backend. MetaApi token never ships in the APK. */
class Mt5ApiClient(
    private val baseUrl: String = "https://strat-1.vercel.app",
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    fun searchServers(query: String, callback: (Result<List<Mt5Server>>) -> Unit) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val request = Request.Builder().url("$baseUrl/api/mt5/servers?q=$encoded").build()
        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    if (!it.isSuccessful) return callback(Result.failure(IOException("Server discovery failed (${it.code})")))
                    try {
                        val root = JSONObject(text)
                        val brokers = root.optJSONArray("brokers") ?: return callback(Result.success(emptyList()))
                        val result = mutableListOf<Mt5Server>()
                        for (i in 0 until brokers.length()) {
                            val broker = brokers.getJSONObject(i).getString("broker")
                            val servers = brokers.getJSONObject(i).getJSONArray("servers")
                            for (j in 0 until servers.length()) {
                                val server = servers.getString(j)
                                result += Mt5Server("$broker:$server", broker, server)
                            }
                        }
                        callback(Result.success(result))
                    } catch (e: Exception) { callback(Result.failure(e)) }
                }
            }
        })
    }

    fun connect(login: String, password: String, server: String, callback: (Result<Mt5ConnectionResult>) -> Unit) {
        val json = JSONObject().apply {
            put("login", login.trim())
            put("password", password)
            put("server", server.trim())
            put("name", "Strat.1 MT5 account")
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/api/mt5/connect").post(body).build()
        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    try {
                        val root = JSONObject(text)
                        if (!it.isSuccessful) {
                            callback(Result.failure(IOException(root.optString("error", "MT5 connection failed"))))
                        } else {
                            callback(Result.success(Mt5ConnectionResult(
                                root.optString("accountId", ""),
                                root.optString("state", "UNKNOWN"),
                                root.optString("server", server)
                            )))
                        }
                    } catch (e: Exception) { callback(Result.failure(e)) }
                }
            }
        })
    }
}

data class Mt5ConnectionResult(val accountId: String, val state: String, val server: String)

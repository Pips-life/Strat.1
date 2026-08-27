package life.pips.strat1.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class Mt5PreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("mt5_preferences", Context.MODE_PRIVATE)
    val broker: String get() = prefs.getString("broker", "") ?: ""
    val server: String get() = prefs.getString("server", "") ?: ""
    val account: String get() = prefs.getString("account", "") ?: ""
    fun save(broker: String, server: String, account: String) = prefs.edit().putString("broker", broker.trim()).putString("server", server.trim()).putString("account", account.trim()).apply()
    fun clear() = prefs.edit().clear().apply()
}

data class Mt5Server(val id: String, val broker: String, val name: String, val environment: String)
data class Mt5ConnectionResult(val accountId: String, val state: String, val server: String)

class Mt5ApiClient(private val baseUrl: String = life.pips.strat1.BuildConfig.BACKEND_BASE_URL, private val client: OkHttpClient = OkHttpClient()) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    fun searchServers(broker: String, callback: (Result<List<Mt5Server>>) -> Unit) {
        scope.launch {
            val result = runCatching {
                val url = "$baseUrl/api/mt5".toHttpUrl().newBuilder().addQueryParameter("query", broker.trim()).build()
                client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (!response.isSuccessful) error("Server discovery failed: HTTP ${response.code}")
                    val root = JSONObject(response.body?.string().orEmpty())
                    val out = mutableListOf<Mt5Server>()
                    root.keys().forEach { brokerName ->
                        val servers = root.optJSONArray(brokerName) ?: return@forEach
                        for (i in 0 until servers.length()) {
                            val name = servers.optString(i)
                            if (name.isNotBlank()) out += Mt5Server("$brokerName:$name", brokerName, name, if (name.contains("demo", true)) "demo" else "live")
                        }
                    }
                    out
                }
            }
            main.post { callback(result) }
        }
    }

    fun connect(login: String, password: String, broker: String, server: String, callback: (Result<Mt5ConnectionResult>) -> Unit) {
        scope.launch {
            val result = runCatching {
                val body = JSONObject().put("login", login).put("password", password).put("server", server).put("broker", broker).toString().toRequestBody()
                client.newCall(Request.Builder().url("$baseUrl/api/mt5").post(body).header("content-type", "application/json").build()).execute().use { response ->
                    val json = JSONObject(response.body?.string().orEmpty())
                    if (!response.isSuccessful) error(json.optString("message", json.optString("error", "MT5 connection failed")))
                    Mt5ConnectionResult(json.optString("id"), json.optString("state"), server)
                }
            }
            main.post { callback(result) }
        }
    }
}

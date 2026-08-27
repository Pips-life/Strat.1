package life.pips.strat1.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class Mt5Server(val id: String, val brokerName: String, val serverName: String, val environment: String) {
    val broker: String get() = brokerName
    val name: String get() = serverName
}

data class Mt5AccountPreference(val brokerName: String, val serverId: String, val accountNumber: String)
data class Mt5ConnectionRequest(val serverId: String, val accountNumber: String, val password: String)
data class ReleaseManifest(val versionName: String, val versionCode: Long, val minimumSupportedVersionCode: Long, val downloadUrl: String, val sha256: String, val releaseNotes: List<String> = emptyList())

class Mt5ApiClient(private val baseUrl: String = life.pips.strat1.BuildConfig.BACKEND_BASE_URL, private val client: OkHttpClient = OkHttpClient()) {
    suspend fun searchServers(broker: String): Result<List<Mt5Server>> = withContext(Dispatchers.IO) { runCatching {
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
    } }

    suspend fun connect(login: String, password: String, server: String, broker: String): Result<JSONObject> = withContext(Dispatchers.IO) { runCatching {
        val body = JSONObject().put("login", login).put("password", password).put("server", server).put("broker", broker).toString().toRequestBody()
        client.newCall(Request.Builder().url("$baseUrl/api/mt5").post(body).header("content-type", "application/json").build()).execute().use { response ->
            val json = JSONObject(response.body?.string().orEmpty())
            if (!response.isSuccessful) error(json.optString("message", json.optString("error", "MT5 connection failed")))
            json
        }
    } }
}

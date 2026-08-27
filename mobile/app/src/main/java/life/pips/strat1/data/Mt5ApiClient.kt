package life.pips.strat1.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import life.pips.strat1.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class Mt5ApiClient(
    private val baseUrl: String = BuildConfig.BACKEND_BASE_URL.trimEnd('/'),
    private val http: OkHttpClient = OkHttpClient(),
) {
    suspend fun findServers(query: String): List<Mt5Server> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/api/mt5/servers?q=${URLEncoder.encode(query, "UTF-8")}").get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(error(body, "Server discovery failed"))
            val items = JSONObject(body).optJSONArray("brokers") ?: JSONArray()
            buildList { for (i in 0 until items.length()) { val x = items.getJSONObject(i); add(Mt5Server(x.optString("id"), x.optString("brokerName"), x.optString("serverName"), x.optString("environment", "real"))) } }
        }
    }

    fun searchServers(query: String, onResult: (Result<List<Mt5Server>>) -> Unit) = CoroutineScope(Dispatchers.IO).launch { val r = runCatching { findServers(query) }; withContext(Dispatchers.Main) { onResult(r) } }

    suspend fun connect(login: String, password: String, server: String, broker: String = ""): Mt5ConnectionResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply { put("login", login); put("password", password); put("server", server); put("name", "Pips-life MT5 $login"); if (broker.isNotBlank()) put("broker", broker) }
        val request = Request.Builder().url("$baseUrl/api/mt5/connect").post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(error(body, "MT5 connection failed"))
            val x = JSONObject(body)
            Mt5ConnectionResult(x.optString("accountId"), x.optString("state", "UNKNOWN"), x.optString("connectionStatus", "CONNECTING"), x.optString("server", server), x.optString("login", login), x.optString("sessionToken"))
        }
    }

    fun connect(login: String, password: String, server: String, onResult: (Result<Mt5ConnectionResult>) -> Unit) = connect(login, password, server, "", onResult)
    fun connect(login: String, password: String, server: String, broker: String, onResult: (Result<Mt5ConnectionResult>) -> Unit) = CoroutineScope(Dispatchers.IO).launch { val r = runCatching { connect(login, password, server, broker) }; withContext(Dispatchers.Main) { onResult(r) } }

    private fun error(body: String, fallback: String) = runCatching { JSONObject(body).optString("error").ifBlank { fallback } }.getOrDefault(fallback)
}

package life.pips.strat1.data

import life.pips.strat1.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object SessionStore { @Volatile var token: String = "" }

class BotControlClient(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL.trimEnd('/'), private val http: OkHttpClient = OkHttpClient()) {
    suspend fun status(): Result<BotControlState> = withContext(Dispatchers.IO) { runCatching { parse(request("/api/bot/control", null)) } }
    suspend fun command(action: String, accountId: String): Result<BotControlState> = withContext(Dispatchers.IO) { runCatching { parse(request("/api/bot/control", JSONObject().apply { put("action", action); put("strategy", "001"); put("accountId", accountId) })) } }
    private fun request(path: String, body: JSONObject?): JSONObject { val b = Request.Builder().url(baseUrl + path); if (SessionStore.token.isNotBlank()) b.header("x-pipslife-session", SessionStore.token); if (body == null) b.get() else b.post(body.toString().toRequestBody("application/json".toMediaType())); http.newCall(b.build()).execute().use { r -> val t = r.body?.string().orEmpty(); if (!r.isSuccessful) throw IllegalStateException(JSONObject(t).optString("error", "Bot control request failed")); return JSONObject(t) } }
    private fun parse(j: JSONObject) = BotControlState(j.optBoolean("configured", false), j.optString("state", "UNKNOWN"), j.optString("strategy", "001"), j.optString("error", ""))
}

data class BotControlState(val configured: Boolean, val state: String, val strategy: String, val error: String)

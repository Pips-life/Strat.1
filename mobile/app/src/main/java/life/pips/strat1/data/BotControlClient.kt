package life.pips.strat1.data

import life.pips.strat1.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class BotControlClient(
    private val baseUrl: String = BuildConfig.BACKEND_BASE_URL,
    private val http: OkHttpClient = OkHttpClient(),
) {
    suspend fun status(): Result<BotControlState> = withContext(Dispatchers.IO) {
        runCatching {
            val response = http.newCall(Request.Builder().url("$baseUrl/api/bot/control").get().build()).execute()
            response.use { parse(it.body?.string().orEmpty(), it.isSuccessful) }
        }
    }

    suspend fun command(action: String, accountId: String): Result<BotControlState> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply { put("action", action); put("strategy", "001"); put("accountId", accountId) }.toString()
            val request = Request.Builder().url("$baseUrl/api/bot/control").post(body.toRequestBody("application/json".toMediaType())).build()
            val response = http.newCall(request).execute()
            response.use { parse(it.body?.string().orEmpty(), it.isSuccessful) }
        }
    }

    private fun parse(body: String, success: Boolean): BotControlState {
        val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
        if (!success) error(json.optString("error", "Bot control request failed"))
        return BotControlState(json.optBoolean("configured", false), json.optString("state", "UNKNOWN"), json.optString("strategy", "001"))
    }
}

data class BotControlState(val configured: Boolean, val state: String, val strategy: String)

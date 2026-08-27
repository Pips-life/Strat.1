package life.pips.strat1.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/** Backend discovery/connection layer. It deliberately knows nothing about Strategy 001. */
class BackendDiscovery(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun discover(candidates: List<String>): BackendConnection? = withContext(Dispatchers.IO) {
        if (!hasInternet()) return@withContext null
        candidates.asSequence().mapNotNull { normalize(it) }.distinct().mapNotNull { probe(it) }.firstOrNull()
    }

    suspend fun connect(endpoint: String): BackendConnection? = withContext(Dispatchers.IO) {
        if (!hasInternet()) return@withContext null
        normalize(endpoint)?.let { probe(it) }
    }

    private fun probe(baseUrl: String): BackendConnection? {
        try {
            val descriptor = Request.Builder().url("$baseUrl/.well-known/strat1-backend.json").header("Accept", "application/json").get().build()
            client.newCall(descriptor).execute().use { response ->
                if (response.isSuccessful) {
                    val raw = response.body?.string().orEmpty()
                    val discovered = Regex("\\\"baseUrl\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(raw)?.groupValues?.getOrNull(1)
                    val resolved = normalize(discovered) ?: baseUrl
                    if (health(resolved)) return BackendConnection(resolved, 200, true)
                }
            }
        } catch (_: Exception) { }
        return if (health(baseUrl)) BackendConnection(baseUrl, 200, false) else null
    }

    private fun health(baseUrl: String): Boolean {
        return try {
            val request = Request.Builder().url("$baseUrl/api/health").get().build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    private fun normalize(value: String?): String? {
        val trimmed = value?.trim()?.removeSuffix('/') ?: return null
        if (trimmed.isBlank() || !trimmed.startsWith("https://")) return null
        return try { if (URI(trimmed).host.isNullOrBlank()) null else trimmed } catch (_: Exception) { null }
    }

    private fun hasInternet(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

data class BackendConnection(val baseUrl: String, val healthStatus: Int, val discoveredFromDescriptor: Boolean = false)

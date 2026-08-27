package life.pips.strat1.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Backend discovery/connection layer. It deliberately knows nothing about Strategy 001.
 * Discovery order: persisted endpoint -> configured candidates -> explicit user endpoint.
 */
class BackendDiscovery(private val context: Context) {
    private val client = OkHttpClient.Builder().build()

    suspend fun discover(candidates: List<String>): BackendConnection? = withContext(Dispatchers.IO) {
        if (!hasInternet()) return@withContext null
        candidates.asSequence().map { normalize(it) }.distinct().firstNotNullOfOrNull { probe(it) }
    }

    suspend fun connect(endpoint: String): BackendConnection? = withContext(Dispatchers.IO) {
        if (!hasInternet()) return@withContext null
        probe(normalize(endpoint))
    }

    private fun probe(baseUrl: String): BackendConnection? {
        return try {
            val request = Request.Builder().url("$baseUrl/api/health").get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) BackendConnection(baseUrl, response.code) else null
            }
        } catch (_: Exception) { null }
    }

    private fun normalize(value: String): String = value.trim().removeSuffix("/")

    private fun hasInternet(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

data class BackendConnection(val baseUrl: String, val healthStatus: Int)

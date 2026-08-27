package life.pips.strat1.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/** Discovers a compatible Strat.1 backend without embedding a secret in the APK. */
class BackendDiscovery(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
) {
    suspend fun discover(candidates: List<String>): BackendEndpoint? = withContext(Dispatchers.IO) {
        candidates.asSequence()
            .mapNotNull { normalize(it) }
            .firstOrNull { endpoint -> checkHealth(endpoint) }
    }

    suspend fun check(endpoint: BackendEndpoint): Boolean = withContext(Dispatchers.IO) {
        checkHealth(endpoint)
    }

    private fun checkHealth(endpoint: BackendEndpoint): Boolean {
        return runCatching {
            val request = Request.Builder()
                .url(endpoint.baseUrl.trimEnd('/') + "/api/health")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        }.getOrDefault(false)
    }

    private fun normalize(raw: String): BackendEndpoint? = runCatching {
        val value = raw.trim().trimEnd('/')
        if (value.isBlank()) return null
        val uri = URI(value)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
        BackendEndpoint(value)
    }.getOrNull()
}

data class BackendEndpoint(val baseUrl: String)

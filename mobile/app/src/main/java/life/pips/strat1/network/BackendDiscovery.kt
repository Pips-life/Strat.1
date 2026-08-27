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
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    suspend fun discover(candidates: List<String>): BackendEndpoint? = withContext(Dispatchers.IO) {
        candidates.asSequence()
            .mapNotNull(::normalize)
            .distinct()
            .mapNotNull(::probe)
            .firstOrNull()
    }

    suspend fun check(endpoint: BackendEndpoint): Boolean = withContext(Dispatchers.IO) {
        probe(endpoint) != null
    }

    private fun probe(candidate: BackendEndpoint): BackendEndpoint? {
        // First allow a future backend to advertise its canonical API URL.
        runCatching {
            val request = Request.Builder()
                .url(candidate.baseUrl + "/.well-known/strat1-backend.json")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val raw = response.body?.string().orEmpty()
                    val advertised = Regex("\\\"baseUrl\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                        .find(raw)?.groupValues?.getOrNull(1)
                    val resolved = normalize(advertised)?.let(::health)
                    if (resolved != null) return resolved
                }
            }
        }
        return health(candidate)
    }

    private fun health(endpoint: BackendEndpoint): BackendEndpoint? = runCatching {
        val request = Request.Builder()
            .url(endpoint.baseUrl + "/api/health")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) endpoint else null
        }
    }.getOrNull()

    private fun normalize(raw: String?): BackendEndpoint? = runCatching {
        val value = raw?.trim()?.removeSuffix("/") ?: return null
        if (value.isBlank()) return null
        val uri = URI(value)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
        BackendEndpoint(value)
    }.getOrNull()
}

data class BackendEndpoint(val baseUrl: String)

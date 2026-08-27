package life.pips.strat1.network

data class BackendConnection(
    val baseUrl: String,
    val healthy: Boolean,
    val statusCode: Int? = null,
    val message: String = ""
)

data class BackendDiscoveryResult(
    val connection: BackendConnection?,
    val attemptedUrls: List<String>
)

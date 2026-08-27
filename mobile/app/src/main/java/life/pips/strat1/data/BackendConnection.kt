package life.pips.strat1.data

data class BackendConnection(
    val baseUrl: String,
    val healthy: Boolean,
    val status: String
)

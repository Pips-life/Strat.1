package life.pips.strat1.domain

data class Mt5Server(
    val id: String,
    val brokerName: String,
    val displayName: String,
    val environment: Environment,
)

enum class Environment { DEMO, LIVE }

data class Mt5AccountPreference(
    val brokerName: String,
    val serverId: String,
    val accountNumber: String,
)

data class Mt5Credentials(
    val serverId: String,
    val accountNumber: String,
    val password: CharArray,
)

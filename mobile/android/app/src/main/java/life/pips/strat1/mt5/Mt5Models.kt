package life.pips.strat1.mt5

data class Mt5Broker(val id: String, val name: String)
data class Mt5Server(val id: String, val brokerId: String, val name: String, val environment: String)
data class Mt5AccountPreference(
    val brokerId: String,
    val brokerName: String,
    val serverId: String,
    val serverName: String,
    val accountNumber: String
)

data class Mt5ConnectionState(
    val connected: Boolean,
    val brokerName: String? = null,
    val serverName: String? = null,
    val accountNumberMasked: String? = null,
    val message: String? = null
)

package life.pips.strat1.data

data class Mt5ServerGroup(val broker: String, val servers: List<String>)
data class Mt5DiscoveryResponse(val brokers: List<Mt5ServerGroup>)
data class Mt5ConnectResponse(val accountId: String?, val state: String, val server: String)
data class Mt5Server(val name: String, val broker: String)

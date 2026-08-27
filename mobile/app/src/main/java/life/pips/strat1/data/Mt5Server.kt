package life.pips.strat1.data

data class Mt5Server(
    val broker: String,
    val name: String,
    val platform: String = "mt5"
)

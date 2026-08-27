package life.pips.strat1.data

data class Mt5ConnectionResult(
    val accountId: String,
    val state: String,
    val connectionStatus: String,
    val server: String,
    val login: String,
)

data class AccountInformation(
    val broker: String = "",
    val currency: String = "",
    val server: String = "",
    val balance: Double = 0.0,
    val equity: Double = 0.0,
    val margin: Double = 0.0,
    val freeMargin: Double = 0.0,
    val leverage: Int = 0,
    val tradeAllowed: Boolean = false,
    val login: Long = 0L,
)

data class LivePosition(
    val id: String,
    val symbol: String,
    val side: String,
    val volume: Double,
    val openPrice: Double,
    val currentPrice: Double,
    val stopLoss: Double?,
    val takeProfit: Double?,
    val profit: Double,
    val unrealizedProfit: Double,
)

data class BotState(
    val running: Boolean,
    val controlAvailable: Boolean,
    val strategy: String,
    val activity: String,
)

data class DashboardState(
    val connected: Boolean = false,
    val accountId: String? = null,
    val accountLogin: String? = null,
    val server: String? = null,
    val connectionStatus: String = "DISCONNECTED",
    val accountInformation: AccountInformation? = null,
    val positions: List<LivePosition> = emptyList(),
    val bot: BotState = BotState(false, false, "001", "BACKEND OFFLINE"),
    val error: String? = null,
)

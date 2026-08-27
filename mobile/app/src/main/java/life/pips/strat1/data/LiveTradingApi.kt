package life.pips.strat1.data

import life.pips.strat1.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Compatibility facade for the live dashboard. The canonical transport is BackendApiClient. */
class LiveTradingApi(private val http: OkHttpClient = OkHttpClient()) {
    private val backend = BackendApiClient(http)

    suspend fun state(accountId: String): Result<LiveTradingState> = withContext(Dispatchers.IO) {
        val token = SessionStore.token
        if (token.isBlank()) return@withContext Result.failure(IllegalStateException("No authenticated backend session"))
        backend.liveState(BackendSession(accountId, token, "")).map { live ->
            LiveTradingState(live.accountId, live.login, live.server, live.connectionStatus, live.state, live.balance, live.equity, live.freeMargin, live.currency, live.positions)
        }
    }
}

data class LiveTradingState(
    val accountId: String,
    val login: String,
    val server: String,
    val connectionStatus: String,
    val state: String,
    val balance: Double,
    val equity: Double,
    val freeMargin: Double,
    val currency: String,
    val positions: List<LivePosition>,
    val tradeAllowed: Boolean? = null,
)

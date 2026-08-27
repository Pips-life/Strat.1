package life.pips.strat1.data

import android.content.Context

/** Non-secret MT5 preferences. Passwords are intentionally never persisted here. */
class Mt5PreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("mt5_preferences", Context.MODE_PRIVATE)
    val broker: String get() = prefs.getString("broker", "") ?: ""
    val server: String get() = prefs.getString("server", "") ?: ""
    val account: String get() = prefs.getString("account", "") ?: ""

    fun save(broker: String, server: String, account: String) {
        prefs.edit().putString("broker", broker.trim()).putString("server", server.trim()).putString("account", account.trim()).apply()
    }

    fun clear() = prefs.edit().clear().apply()
}

data class Mt5Server(
    val id: String,
    val brokerName: String,
    val serverName: String,
    val environment: String
)

data class Mt5ConnectionResult(
    val accountId: String?,
    val state: String,
    val server: String,
    val message: String? = null,
    val reused: Boolean = false
)

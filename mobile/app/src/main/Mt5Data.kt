package life.pips.strat1.data

import android.content.Context

/** Non-secret MT5 preferences. Passwords are intentionally never persisted here. */
class Mt5PreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("mt5_preferences", Context.MODE_PRIVATE)
    val broker: String get() = prefs.getString("broker", "") ?: ""
    val server: String get() = prefs.getString("server", "") ?: ""
    val account: String get() = prefs.getString("account", "") ?: ""

    fun save(broker: String, server: String, account: String) {
        prefs.edit()
            .putString("broker", broker.trim())
            .putString("server", server.trim())
            .putString("account", account.trim())
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()
}

data class Mt5Server(val id: String, val broker: String, val name: String)

/** Backend-backed broker/server discovery. */
class Mt5ServerRepository(private val api: Mt5ApiClient = Mt5ApiClient()) {
    fun search(broker: String, callback: (Result<List<Mt5Server>>) -> Unit) {
        api.searchServers(broker, callback)
    }
}

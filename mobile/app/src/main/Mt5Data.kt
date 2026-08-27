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

data class Mt5Server(val id: String, val broker: String, val name: String)

/** Replace the local adapter with the backend broker-directory API without changing the UI. */
class Mt5ServerRepository {
    private val known = listOf(
        Mt5Server("demo-1", "Exness", "Exness-MT5Trial"),
        Mt5Server("real-1", "Exness", "Exness-MT5Real"),
        Mt5Server("demo-2", "IC Markets", "ICMarketsSC-Demo"),
        Mt5Server("real-2", "IC Markets", "ICMarketsSC-Live"),
        Mt5Server("demo-3", "XM", "XMGlobal-MT5 4"),
        Mt5Server("real-3", "XM", "XMGlobal-MT5 5")
    )

    fun search(broker: String): List<Mt5Server> = if (broker.isBlank()) emptyList() else known.filter { it.broker.contains(broker.trim(), ignoreCase = true) }
}

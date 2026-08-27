package life.pips.strat1

data class Mt5Preference(
    val broker: String = "",
    val server: String = "",
    val accountNumber: String = ""
)

class Mt5PreferenceStore(private val secure: SecurePreferences) {
    fun load(): Mt5Preference = Mt5Preference(
        broker = secure.get("mt5_broker") ?: "",
        server = secure.get("mt5_server") ?: "",
        accountNumber = secure.get("mt5_account") ?: ""
    )

    fun save(preference: Mt5Preference) {
        secure.put("mt5_broker", preference.broker)
        secure.put("mt5_server", preference.server)
        secure.put("mt5_account", preference.accountNumber)
    }

    fun clear() {
        secure.clearAll()
    }
}

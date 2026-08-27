package life.pips.strat1.network

import android.content.Context

class BackendPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("backend_connection", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "") ?: ""
        private set(value) { prefs.edit().putString(KEY_BASE_URL, value).apply() }

    fun save(endpoint: BackendEndpoint) {
        baseUrl = endpoint.baseUrl
    }

    fun clear() {
        prefs.edit().remove(KEY_BASE_URL).apply()
    }

    companion object {
        private const val KEY_BASE_URL = "base_url"
    }
}

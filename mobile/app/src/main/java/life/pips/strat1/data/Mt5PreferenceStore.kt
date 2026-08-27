package life.pips.strat1.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.mt5DataStore by preferencesDataStore(name = "mt5_preferences")

class Mt5PreferenceStore(private val context: Context) {
    private val brokerKey = stringPreferencesKey("broker")
    private val serverKey = stringPreferencesKey("server")
    private val accountKey = stringPreferencesKey("account")

    val broker: String get() = runBlocking { context.mt5DataStore.data.first()[brokerKey].orEmpty() }
    val server: String get() = runBlocking { context.mt5DataStore.data.first()[serverKey].orEmpty() }
    val account: String get() = runBlocking { context.mt5DataStore.data.first()[accountKey].orEmpty() }

    fun save(broker: String, server: String, account: String) = runBlocking {
        context.mt5DataStore.edit {
            it[brokerKey] = broker.trim()
            it[serverKey] = server.trim()
            it[accountKey] = account.trim()
        }
    }
}

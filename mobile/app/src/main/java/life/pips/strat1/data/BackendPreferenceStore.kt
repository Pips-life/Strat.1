package life.pips.strat1.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.backendDataStore by preferencesDataStore(name = "backend_preferences")

class BackendPreferenceStore(private val context: Context) {
    private val endpointKey = stringPreferencesKey("endpoint")

    suspend fun getEndpoint(): String = context.backendDataStore.data.first()[endpointKey].orEmpty()

    suspend fun saveEndpoint(endpoint: String) {
        context.backendDataStore.edit { it[endpointKey] = endpoint.trim().removeSuffix("/") }
    }

    suspend fun clear() { context.backendDataStore.edit { it.remove(endpointKey) } }
}

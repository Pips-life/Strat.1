package life.pips.strat1.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Backward-compatible facade used by the original MT5 screen. */
class Mt5ApiClient {
    private val backend = BackendApiClient()
    suspend fun findServers(query: String): List<Mt5Server> = backend.findServers(query).getOrThrow()
    fun searchServers(query: String, onResult: (Result<List<Mt5Server>>) -> Unit) = CoroutineScope(Dispatchers.IO).launch { val result = runCatching { findServers(query) }; withContext(Dispatchers.Main) { onResult(result) } }
    suspend fun connect(login: String, password: String, server: String, broker: String = ""): Mt5ConnectionResult { val session = backend.connect(login, password, server, broker).getOrThrow(); return Mt5ConnectionResult(session.accountId, "CONNECTED", "CONNECTED", session.server, login, session.token) }
    fun connect(login: String, password: String, server: String, onResult: (Result<Mt5ConnectionResult>) -> Unit) = connect(login, password, server, "", onResult)
    fun connect(login: String, password: String, server: String, broker: String, onResult: (Result<Mt5ConnectionResult>) -> Unit) = CoroutineScope(Dispatchers.IO).launch { val result = runCatching { connect(login, password, server, broker) }; withContext(Dispatchers.Main) { onResult(result) } }
}

data class Mt5ConnectionResult(val accountId: String, val state: String, val connectionStatus: String, val server: String, val login: String, val sessionToken: String)

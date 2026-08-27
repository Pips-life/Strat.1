package life.pips.strat1

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import life.pips.strat1.data.Mt5ApiClient
import life.pips.strat1.data.Mt5Server
import life.pips.strat1.network.BackendDefaults
import life.pips.strat1.network.BackendDiscovery
import life.pips.strat1.network.BackendEndpoint
import life.pips.strat1.network.BackendPreferenceStore
import life.pips.strat1.ui.theme.Strat1Theme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.preferencesDataStore by preferencesDataStore("strat1_preferences")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Strat1Theme { Strat1App(applicationContext) } }
    }
}

@Composable
private fun Strat1App(context: Context) {
    var tab by remember { mutableStateOf(0) }
    val labels = listOf("Dashboard", "MT5", "Backend", "Settings")
    val backendPreferences = remember { BackendPreferenceStore(context) }
    var backendUrl by remember { mutableStateOf(backendPreferences.baseUrl) }
    val scope = rememberCoroutineScope()

    suspend fun discoverBackend() {
        val discovery = BackendDiscovery()
        val candidates = buildList {
            backendUrl.takeIf { it.isNotBlank() }?.let(::add)
            addAll(BackendDefaults.candidates)
        }.distinct()
        val found = discovery.discover(candidates)
        if (found != null) {
            backendUrl = found.baseUrl
            backendPreferences.save(found)
        }
    }

    LaunchedEffect(Unit) { discoverBackend() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Strat.1") }, actions = {
            Text(if (backendUrl.isBlank()) "Offline" else "Backend ●", modifier = Modifier.padding(end = 16.dp))
        })
    }, bottomBar = {
        NavigationBar { labels.forEachIndexed { index, label ->
            NavigationBarItem(index == tab, { tab = index }, icon = { Text(if (index == 0) "⌂" else if (index == 1) "M" else if (index == 2) "B" else "⚙") }, label = { Text(label) })
        } }
    }) { padding ->
        when (tab) {
            0 -> DashboardScreen(Modifier.padding(padding), backendUrl) { scope.launch { discoverBackend() } }
            1 -> Mt5Screen(context, backendUrl, Modifier.padding(padding))
            2 -> BackendScreen(context, backendUrl, Modifier.padding(padding)) { found -> backendUrl = found ?: "" }
            else -> SettingsScreen(Modifier.padding(padding))
        }
    }
}

@Composable
private fun DashboardScreen(modifier: Modifier, backendUrl: String, onReconnect: () -> Unit) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Strat.1", style = MaterialTheme.typography.headlineMedium)
        Text("Strategy 001 control center")
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Backend", style = MaterialTheme.typography.titleMedium)
            Text(if (backendUrl.isBlank()) "Not connected" else "Connected: $backendUrl")
            Button(onClick = onReconnect) { Text("Discover / reconnect") }
        } }
    }
}

@Composable
private fun BackendScreen(context: Context, currentUrl: String, modifier: Modifier, onChanged: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val discovery = remember { BackendDiscovery() }
    val preferences = remember { BackendPreferenceStore(context) }
    var endpoint by remember(currentUrl) { mutableStateOf(currentUrl) }
    var manualEndpoint by remember(currentUrl) { mutableStateOf(currentUrl) }
    var status by remember(currentUrl) { mutableStateOf(if (currentUrl.isBlank()) "Not connected" else "Saved backend: $currentUrl") }
    var busy by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Backend", style = MaterialTheme.typography.headlineSmall)
        Text("The app probes /api/health and remembers the first healthy trusted endpoint.")
        OutlinedTextField(manualEndpoint, { manualEndpoint = it }, Modifier.fillMaxWidth(), label = { Text("Backend URL") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = !busy, onClick = { scope.launch {
                busy = true; status = "Discovering backend…"
                val found = discovery.discover(BackendDefaults.candidates)
                busy = false
                if (found != null) { endpoint = found.baseUrl; manualEndpoint = found.baseUrl; preferences.save(found); onChanged(found.baseUrl); status = "Connected: ${found.baseUrl}" }
                else status = "No compatible backend discovered."
            } }) { Text(if (busy) "Checking…" else "Discover") }
            Button(enabled = !busy && manualEndpoint.isNotBlank(), onClick = { scope.launch {
                busy = true; status = "Testing backend…"
                val candidate = BackendEndpoint(manualEndpoint.trim().removeSuffix("/"))
                val healthy = discovery.check(candidate)
                busy = false
                if (healthy) { endpoint = candidate.baseUrl; preferences.save(candidate); onChanged(candidate.baseUrl); status = "Connected: ${candidate.baseUrl}" }
                else status = "Backend health check failed."
            } }) { Text("Connect") }
        }
        OutlinedButton(enabled = endpoint.isNotBlank(), onClick = { preferences.clear(); endpoint = ""; manualEndpoint = ""; onChanged(null); status = "Backend disconnected." }) { Text("Disconnect") }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Connection status", style = MaterialTheme.typography.titleMedium); Text(status) } }
    }
}

@Composable
private fun Mt5Screen(context: Context, backendUrl: String, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val api = remember(backendUrl) { backendUrl.takeIf { it.isNotBlank() }?.let { Mt5ApiClient(it) } }
    var broker by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var serverList by remember { mutableStateOf(emptyList<Mt5Server>()) }
    var selectedServer by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Loading saved MT5 preference…") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prefs = context.preferencesDataStore.data.first()
        broker = prefs[PreferenceKeys.BROKER].orEmpty(); account = prefs[PreferenceKeys.ACCOUNT].orEmpty(); selectedServer = prefs[PreferenceKeys.SERVER].orEmpty()
        status = if (broker.isBlank()) "Enter your broker to find MT5 servers." else "Saved MT5 preference loaded."
    }

    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("MT5 Account", style = MaterialTheme.typography.headlineSmall); Text(if (api == null) "Connect the Strat.1 backend first." else "Search the connected backend for MT5 servers, select the exact server, then connect.") }
        item { OutlinedTextField(broker, { broker = it }, Modifier.fillMaxWidth(), label = { Text("Broker name") }, singleLine = true) }
        item { Button(enabled = api != null && broker.trim().length >= 2 && !busy, onClick = { busy = true; status = "Searching backend for broker servers…"; api?.searchServers(broker) { result -> scope.launch { busy = false; result.onSuccess { matches -> serverList = matches; status = if (matches.isEmpty()) "No known servers found." else "Select the server matching your MT5 account." }.onFailure { status = "Server discovery failed: ${it.message ?: "network error"}" } } } }) { Text(if (busy) "Searching…" else "Find servers") } }
        items(serverList, key = { it.id }) { server -> Card(onClick = { selectedServer = server.id }, Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(server.brokerName, style = MaterialTheme.typography.labelMedium); Text(server.serverName, style = MaterialTheme.typography.titleMedium); Text(server.environment.uppercase(), style = MaterialTheme.typography.labelSmall); if (server.id == selectedServer) Text("Selected") } } }
        item { OutlinedTextField(serverList.firstOrNull { it.id == selectedServer }?.serverName.orEmpty(), {}, Modifier.fillMaxWidth(), label = { Text("Selected server") }, readOnly = true, singleLine = true) }
        item { OutlinedTextField(account, { account = it }, Modifier.fillMaxWidth(), label = { Text("MT5 account number") }, singleLine = true) }
        item { OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("MT5 password") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) }
        item {
            val selected = serverList.firstOrNull { it.id == selectedServer }
            Button(enabled = api != null && account.matches(Regex("\\d+")) && password.isNotEmpty() && selected != null && !busy, onClick = { busy = true; status = "Validating MT5 account securely…"; api?.connect(selected!!.brokerName, account, password, selected.serverName) { result -> scope.launch { busy = false; result.onSuccess { connection -> password = ""; status = "MT5 account: ${connection.state}"; if (connection.state != "PROCESSING") context.preferencesDataStore.edit { prefs -> prefs[PreferenceKeys.BROKER] = selected.brokerName; prefs[PreferenceKeys.SERVER] = selected.id; prefs[PreferenceKeys.ACCOUNT] = account.trim() } }.onFailure { password = ""; status = "MT5 connection failed: ${it.message ?: "unknown error"}" } } } }) { Text(if (busy) "Connecting…" else "Connect MT5") }
        }
        item { Text(status) }
        item { Text("The password is never saved in app preferences.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("Updates will be checked against the Strat.1 release service.")
        Text("Release channel: Stable")
        Text("Current release: 0.1.0 (1)")
        Text("Update policy: notify → download → Android install confirmation")
    }
}

private object PreferenceKeys {
    val BROKER: Preferences.Key<String> = stringPreferencesKey("mt5_broker")
    val SERVER: Preferences.Key<String> = stringPreferencesKey("mt5_server")
    val ACCOUNT: Preferences.Key<String> = stringPreferencesKey("mt5_account")
}

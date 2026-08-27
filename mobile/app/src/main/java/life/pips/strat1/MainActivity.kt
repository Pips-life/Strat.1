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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.preferencesDataStore by preferencesDataStore("strat1_preferences")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Strat1App(applicationContext) }
    }
}

@Composable
private fun Strat1App(context: Context) {
    var tab by remember { mutableStateOf(0) }
    val labels = listOf("Dashboard", "MT5", "Settings")
    Scaffold(bottomBar = {
        NavigationBar { labels.forEachIndexed { index, label ->
            NavigationBarItem(
                selected = tab == index,
                onClick = { tab = index },
                icon = { Text(if (index == 0) "⌂" else if (index == 1) "M" else "⚙") },
                label = { Text(label) }
            )
        } }
    }) { padding ->
        when (tab) {
            0 -> DashboardScreen(Modifier.padding(padding))
            1 -> Mt5Screen(context, Modifier.padding(padding))
            else -> SettingsScreen(Modifier.padding(padding))
        }
    }
}

@Composable
private fun DashboardScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Text("Strat.1", style = MaterialTheme.typography.headlineMedium)
        Text("Strategy 001 control center", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(20.dp))
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Backend", style = MaterialTheme.typography.titleMedium)
            Text("MT5 connection is managed securely by the backend.")
        } }
    }
}

@Composable
private fun Mt5Screen(context: Context, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val api = remember { Mt5ApiClient() }
    var broker by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var serverList by remember { mutableStateOf(emptyList<Mt5Server>()) }
    var selectedServer by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Loading saved MT5 preference…") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prefs = context.preferencesDataStore.data.first()
        broker = prefs[PreferenceKeys.BROKER].orEmpty()
        account = prefs[PreferenceKeys.ACCOUNT].orEmpty()
        selectedServer = prefs[PreferenceKeys.SERVER].orEmpty()
        status = if (broker.isBlank()) "Enter your broker to find MT5 servers." else "Saved MT5 preference loaded."
    }

    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("MT5 Account", style = MaterialTheme.typography.headlineSmall)
            Text("Search the live MetaApi server directory, select the exact server, then connect.")
        }
        item {
            OutlinedTextField(broker, { broker = it }, Modifier.fillMaxWidth(), label = { Text("Broker name") }, singleLine = true)
        }
        item {
            Button(enabled = broker.trim().length >= 2 && !busy, onClick = {
                busy = true
                status = "Searching MetaApi for broker servers…"
                api.searchServers(broker) { result ->
                    scope.launch {
                        busy = false
                        result.onSuccess { matches ->
                            serverList = matches
                            status = if (matches.isEmpty()) "No known servers found. Try the broker's exact name." else "Select the server matching your MT5 account."
                        }.onFailure { status = "Server discovery failed: ${it.message ?: "network error"}" }
                    }
                }
            }) { Text(if (busy) "Searching…" else "Find servers") }
        }
        items(serverList, key = { it.id }) { server ->
            Card(onClick = { selectedServer = server.id }, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(server.brokerName, style = MaterialTheme.typography.labelMedium)
                    Text(server.serverName, style = MaterialTheme.typography.titleMedium)
                    Text(server.environment.uppercase(), style = MaterialTheme.typography.labelSmall)
                    if (server.id == selectedServer) Text("Selected", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item {
            val selectedName = serverList.firstOrNull { it.id == selectedServer }?.serverName.orEmpty()
            OutlinedTextField(selectedName, {}, Modifier.fillMaxWidth(), label = { Text("Selected server") }, readOnly = true, singleLine = true)
        }
        item { OutlinedTextField(account, { account = it }, Modifier.fillMaxWidth(), label = { Text("MT5 account number") }, singleLine = true) }
        item { OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("MT5 password") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) }
        item {
            val selected = serverList.firstOrNull { it.id == selectedServer }
            Button(enabled = account.matches(Regex("\\d+")) && password.isNotEmpty() && selected != null && !busy, onClick = {
                busy = true
                status = "Validating MT5 account securely…"
                api.connect(selected!!.brokerName, account, password, selected.serverName) { result ->
                    scope.launch {
                        busy = false
                        result.onSuccess { connection ->
                            if (connection.state == "PROCESSING") {
                                status = connection.message ?: "MetaApi is still validating the account. Please try again shortly."
                                password = ""
                            } else {
                                context.preferencesDataStore.edit { prefs ->
                                    prefs[PreferenceKeys.BROKER] = selected.brokerName
                                    prefs[PreferenceKeys.SERVER] = selected.id
                                    prefs[PreferenceKeys.ACCOUNT] = account.trim()
                                }
                                password = ""
                                status = "MT5 account: ${connection.state}"
                            }
                        }.onFailure { status = "MT5 connection failed: ${it.message ?: "unknown error"}" }
                    }
                }
            }) { Text(if (busy) "Connecting…" else "Connect MT5") }
        }
        item { Text(status) }
        item { Text("The password is not saved in the app preferences.", style = MaterialTheme.typography.bodySmall) }
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

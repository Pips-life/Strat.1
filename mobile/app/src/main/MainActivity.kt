package life.pips.strat1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import life.pips.strat1.data.Mt5PreferenceStore
import life.pips.strat1.data.Mt5Server
import life.pips.strat1.data.Mt5ServerRepository
import life.pips.strat1.network.BackendDefaults
import life.pips.strat1.network.BackendDiscovery
import life.pips.strat1.network.BackendEndpoint
import life.pips.strat1.network.BackendPreferenceStore
import life.pips.strat1.ui.theme.Strat1Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mt5Store = Mt5PreferenceStore(this)
        val backendStore = BackendPreferenceStore(this)
        val servers = Mt5ServerRepository()
        setContent { Strat1Theme { Strat1App(mt5Store, backendStore, BackendDiscovery(), servers) } }
    }
}

@Composable
private fun Strat1App(
    mt5Store: Mt5PreferenceStore,
    backendStore: BackendPreferenceStore,
    discovery: BackendDiscovery,
    servers: Mt5ServerRepository
) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Dashboard", "MT5", "Strategy 001", "Settings")
    Scaffold(
        topBar = { TopAppBar(title = { Text("Strat.1") }) },
        bottomBar = { NavigationBar { tabs.forEachIndexed { i, title ->
            NavigationBarItem(tab == i, { tab = i }, icon = { Text(title.take(1)) }, label = { Text(title) })
        } } }
    ) { p -> Box(Modifier.padding(p).fillMaxSize()) {
        when (tab) {
            0 -> DashboardScreen(backendStore)
            1 -> Mt5Screen(mt5Store, servers)
            2 -> SimpleScreen("Strategy 001", "Presentation layer only; strategy-engine logic remains outside mobile.")
            else -> BackendScreen(backendStore, discovery)
        }
    } }
}

@Composable
private fun DashboardScreen(store: BackendPreferenceStore) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Trading dashboard", style = MaterialTheme.typography.headlineSmall)
        Text(if (store.baseUrl.isBlank()) "Backend: discovering / not connected" else "Backend: connected to ${store.baseUrl}")
        Text("The mobile app is a client. Strategy 001 remains on the backend.")
    }
}

@Composable
private fun BackendScreen(store: BackendPreferenceStore, discovery: BackendDiscovery) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(if (store.baseUrl.isBlank()) "Not connected" else "Connected: ${store.baseUrl}") }
    var manualUrl by remember { mutableStateOf(store.baseUrl) }

    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Backend", style = MaterialTheme.typography.headlineSmall)
        Text("The app can discover a compatible Strat.1 backend and remember the endpoint.")
        Button(onClick = {
            scope.launch {
                status = "Discovering..."
                val endpoint = discovery.discover(BackendDefaults.candidates)
                if (endpoint == null) status = "No compatible backend found"
                else {
                    store.save(endpoint)
                    manualUrl = endpoint.baseUrl
                    status = "Connected: ${endpoint.baseUrl}"
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Discover backend") }
        OutlinedTextField(
            value = manualUrl,
            onValueChange = { manualUrl = it },
            label = { Text("Backend URL (HTTPS)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            scope.launch {
                val endpoint = runCatching { BackendEndpoint(manualUrl.trim().trimEnd('/')) }.getOrNull()
                if (endpoint == null || !manualUrl.startsWith("https://")) {
                    status = "Use an HTTPS backend URL"
                } else if (discovery.check(endpoint)) {
                    store.save(endpoint)
                    status = "Connected: ${endpoint.baseUrl}"
                } else status = "Backend health check failed"
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
        OutlinedButton(onClick = { store.clear(); manualUrl = ""; status = "Not connected" }, modifier = Modifier.fillMaxWidth()) { Text("Forget backend") }
        Text("Status: $status")
    }
}

@Composable
private fun SimpleScreen(title: String, body: String) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body)
    }
}

@Composable
private fun Mt5Screen(store: Mt5PreferenceStore, servers: Mt5ServerRepository) {
    var broker by remember { mutableStateOf(store.broker) }
    var serverName by remember { mutableStateOf(store.server) }
    var account by remember { mutableStateOf(store.account) }
    var password by remember { mutableStateOf("") }
    var serverList by remember { mutableStateOf<List<Mt5Server>>(emptyList()) }
    var status by remember { mutableStateOf("Not connected") }
    LazyColumn(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("MT5", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Enter your broker name, choose the exact MT5 server, then enter your credentials.") }
        item { OutlinedTextField(broker, { broker = it; serverList = servers.search(it) }, label = { Text("MT5 broker") }, modifier = Modifier.fillMaxWidth()) }
        item { Text("Servers", style = MaterialTheme.typography.titleMedium) }
        items(serverList) { server -> FilterChip(serverName == server.name, { serverName = server.name }, label = { Text(server.name) }) }
        item { OutlinedTextField(serverName, { serverName = it }, label = { Text("Selected server") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(account, { account = it }, label = { Text("MT5 account number") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(password, { password = it }, label = { Text("MT5 password") }, modifier = Modifier.fillMaxWidth()) }
        item { Button({ store.save(broker, serverName, account); password = ""; status = "Preferences saved; secure backend connection pending." }, Modifier.fillMaxWidth()) { Text("Save & Connect") } }
        item { Text("Status: $status") }
    }
}

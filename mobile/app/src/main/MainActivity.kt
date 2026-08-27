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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import life.pips.strat1.data.Mt5PreferenceStore
import life.pips.strat1.data.Mt5Server
import life.pips.strat1.data.Mt5ServerRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Mt5PreferenceStore(this)
        setContent { MaterialTheme { Strat1App(store, Mt5ServerRepository()) } }
    }
}

@Composable
private fun Strat1App(store: Mt5PreferenceStore, servers: Mt5ServerRepository) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Dashboard", "MT5", "Strategy 001", "Settings")
    Scaffold(
        topBar = { TopAppBar(title = { Text("Strat.1") }) },
        bottomBar = { NavigationBar { tabs.forEachIndexed { i, title ->
            NavigationBarItem(tab == i, { tab = i }, icon = { Text(title.take(1)) }, label = { Text(title) })
        } } }
    ) { p -> Box(Modifier.padding(p).fillMaxSize()) {
        when (tab) {
            0 -> SimpleScreen("Trading dashboard", "Backend connection and account status will appear here.")
            1 -> Mt5Screen(store, servers)
            2 -> SimpleScreen("Strategy 001", "Presentation layer only; strategy-engine logic remains outside mobile.")
            else -> SimpleScreen("Settings", "Updates, security and connection settings will live here.")
        }
    } }
}

@Composable private fun SimpleScreen(title: String, body: String) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body)
    }
}

@Composable
private fun Mt5Screen(store: Mt5PreferenceStore, servers: Mt5ServerRepository) {
    val scope = rememberCoroutineScope()
    var broker by remember { mutableStateOf(store.broker) }
    var serverName by remember { mutableStateOf(store.server) }
    var account by remember { mutableStateOf(store.account) }
    var password by remember { mutableStateOf("") }
    var serverList by remember { mutableStateOf<List<Mt5Server>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Not connected") }

    LazyColumn(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("MT5", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Search the live MetaApi server directory, select your exact server, then connect the account securely through the backend.") }
        item {
            OutlinedTextField(broker, { broker = it }, label = { Text("MT5 broker") }, modifier = Modifier.fillMaxWidth())
        }
        item {
            Button(
                onClick = {
                    loading = true; status = "Searching MetaApi…"
                    scope.launch {
                        runCatching { servers.search(broker) }.onSuccess { serverList = it; status = "Found ${it.size} server(s)" }
                            .onFailure { status = "Discovery failed: ${it.message ?: "network error"}" }
                        loading = false
                    }
                }, enabled = !loading && broker.trim().length >= 2, modifier = Modifier.fillMaxWidth()
            ) { Text(if (loading) "Searching…" else "Find MT5 servers") }
        }
        item { Text("Servers", style = MaterialTheme.typography.titleMedium) }
        items(serverList) { server ->
            FilterChip(
                selected = serverName == server.name,
                onClick = { serverName = server.name; broker = server.broker },
                label = { Text("${server.broker} — ${server.name}") }
            )
        }
        item { OutlinedTextField(serverName, { serverName = it }, label = { Text("Selected server") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(account, { account = it.filter(Char::isDigit) }, label = { Text("MT5 account number") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(password, { password = it }, label = { Text("MT5 password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()) }
        item {
            Button(
                onClick = {
                    connecting = true; status = "Validating MT5 credentials…"
                    scope.launch {
                        runCatching { servers.connect(account, password, serverName, broker) }
                            .onSuccess { store.save(broker, serverName, account); password = ""; status = "MetaApi: ${it.state}" }
                            .onFailure { status = it.message ?: "MT5 connection failed" }
                        connecting = false
                    }
                }, enabled = !connecting && broker.isNotBlank() && serverName.isNotBlank() && account.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth()
            ) { Text(if (connecting) "Connecting…" else "Save & Connect") }
        }
        item { Text("Status: $status") }
    }
}

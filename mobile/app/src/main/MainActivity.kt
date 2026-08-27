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
import life.pips.strat1.data.Mt5PreferenceStore
import life.pips.strat1.data.Mt5Server
import life.pips.strat1.data.Mt5ServerRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Mt5PreferenceStore(this)
        val servers = Mt5ServerRepository()
        setContent { MaterialTheme { Strat1App(store, servers) } }
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

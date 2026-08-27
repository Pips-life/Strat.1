package life.pips.strat1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Strat1App() }
    }
}

@Composable
private fun Strat1App() {
    var selected by remember { mutableIntStateOf(0) }
    val tabs = listOf("Dashboard", "MT5", "Strategy", "Settings")

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Text(label.take(1)) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        when (selected) {
            0 -> PlaceholderScreen("Strat.1 Dashboard", Modifier.padding(padding))
            1 -> Mt5Screen(Modifier.padding(padding))
            2 -> PlaceholderScreen("Strategy 001", Modifier.padding(padding))
            else -> PlaceholderScreen("Settings", Modifier.padding(padding))
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String, modifier: Modifier = Modifier) {
    Surface(modifier) { Text(title, modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall) }
}

@Composable
private fun Mt5Screen(modifier: Modifier = Modifier) {
    var broker by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }

    Surface(modifier) {
        androidx.compose.foundation.layout.Column(Modifier.padding(20.dp)) {
            Text("MT5 Account", style = MaterialTheme.typography.headlineSmall)
            Text("Broker → server → account → password", modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            OutlinedTextField(broker, { broker = it }, label = { Text("Broker name") }, modifier = Modifier.padding(bottom = 10.dp))
            OutlinedTextField(server, { server = it }, label = { Text("MT5 server") }, modifier = Modifier.padding(bottom = 10.dp))
            OutlinedTextField(account, { account = it }, label = { Text("Account number") }, modifier = Modifier.padding(bottom = 10.dp))
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, modifier = Modifier.padding(bottom = 16.dp))
            Button(onClick = { /* connection service will be wired in next milestone */ }) { Text("Connect") }
        }
    }
}

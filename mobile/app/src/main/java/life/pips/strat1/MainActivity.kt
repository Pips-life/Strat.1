package life.pips.strat1

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    Scaffold(
        bottomBar = {
            NavigationBar {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Text(if (index == 0) "⌂" else if (index == 1) "M" else "⚙") },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
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
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Backend", style = MaterialTheme.typography.titleMedium)
                Text("Not connected", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Trading mode", style = MaterialTheme.typography.titleMedium)
                Text("Demo", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Mt5Screen(context: Context, modifier: Modifier = Modifier) {
    var broker by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var servers by remember { mutableStateOf(listOf<String>()) }
    var selectedServer by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Enter your broker to find MT5 servers.") }

    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("MT5 Account", style = MaterialTheme.typography.headlineSmall)
            Text("Select the exact broker server used by your MT5 account.")
        }
        item {
            OutlinedTextField(
                value = broker,
                onValueChange = { broker = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Broker name") },
                singleLine = true
            )
        }
        item {
            Button(onClick = {
                // Backend discovery will replace this demo adapter.
                servers = if (broker.isBlank()) emptyList() else listOf("$broker-MT5Real", "$broker-MT5Demo")
                status = "Select the server that matches your MT5 account."
            }) { Text("Find servers") }
        }
        items(servers) { server ->
            Card(onClick = { selectedServer = server }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(server)
                    if (server == selectedServer) Text("Selected")
                }
            }
        }
        item {
            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("MT5 account number") },
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("MT5 password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
        }
        item {
            Button(onClick = {
                CoroutineScope(Dispatchers.IO).launch {
                    context.preferencesDataStore.edit { prefs ->
                        prefs[PreferenceKeys.BROKER] = broker
                        prefs[PreferenceKeys.SERVER] = selectedServer
                        prefs[PreferenceKeys.ACCOUNT] = account
                    }
                    // Password is intentionally not persisted in DataStore.
                }
                status = "Credentials ready for secure backend verification."
            }, enabled = broker.isNotBlank() && selectedServer.isNotBlank() && account.isNotBlank() && password.isNotBlank()) {
                Text("Connect MT5")
            }
        }
        item { Text(status) }
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

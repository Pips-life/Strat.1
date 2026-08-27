package life.pips.strat1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Strat1App() }
    }
}

@Composable
private fun Strat1App() {
    var selectedTab by remember { mutableIntStateOf(0) }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text("Strat.1", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(20.dp))
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Dashboard") })
                Tab(selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("MT5") })
            }
            if (selectedTab == 0) DashboardTab() else Mt5Tab()
        }
    }
}

@Composable
private fun DashboardTab() {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Strategy 001", style = MaterialTheme.typography.titleLarge)
        Text("Backend connection will appear here.")
        Text("Demo / Live execution controls will be enabled only after backend verification.")
    }
}

@Composable
private fun Mt5Tab() {
    var broker by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Not connected") }
    var servers by remember { mutableStateOf(listOf<String>()) }

    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("MT5 account", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(broker, { broker = it; servers = emptyList() }, Modifier.fillMaxWidth(), label = { Text("Broker name") })
        Button(onClick = { servers = listOf("Search results will come from the backend") }) { Text("Find broker servers") }
        servers.forEach { Text(it, Modifier.padding(vertical = 4.dp)) }
        OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("MT5 server") })
        OutlinedTextField(account, { account = it }, Modifier.fillMaxWidth(), label = { Text("MT5 account number") })
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("MT5 password") }, visualTransformation = PasswordVisualTransformation())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { status = "Connection request ready for backend validation" }) { Text("Connect") }
            Button(onClick = { broker = ""; server = ""; account = ""; password = ""; status = "Credentials cleared" }) { Text("Forget") }
        }
        Text(status)
    }
}

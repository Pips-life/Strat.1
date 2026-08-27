package life.pips.strat1

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import life.pips.strat1.data.Mt5ApiClient
import life.pips.strat1.data.Mt5Server
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.preferencesDataStore by preferencesDataStore("strat1_preferences")

private val Ink = Color(0xFF071018)
private val Panel = Color(0xFF0D1822)
private val PanelRaised = Color(0xFF12212D)
private val Line = Color(0xFF223541)
private val TextPrimary = Color(0xFFF4F8FA)
private val TextMuted = Color(0xFF91A4AF)
private val Positive = Color(0xFF35D07F)
private val Negative = Color(0xFFFF5E68)
private val Accent = Color(0xFF28B7D9)

private val PipsColors = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    secondary = Positive,
    background = Ink,
    surface = Panel,
    surfaceVariant = PanelRaised,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    outline = Line,
    error = Negative,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PipsLifeApp(applicationContext) }
    }
}

@Composable
private fun PipsLifeApp(context: Context) {
    var tab by remember { mutableStateOf(0) }
    val labels = listOf("Home", "Strategies", "Positions", "MT5", "Settings")
    val icons = listOf(Icons.Default.Home, Icons.Default.Tune, Icons.Default.ShowChart, Icons.Default.AccountBalance, Icons.Default.Settings)

    MaterialTheme(colorScheme = PipsColors) {
        Scaffold(
            containerColor = Ink,
            bottomBar = {
                NavigationBar(containerColor = Panel) {
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(icons[index], contentDescription = label) },
                            label = { Text(label, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Accent,
                                selectedTextColor = Accent,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                                indicatorColor = PanelRaised,
                            )
                        )
                    }
                }
            }
        ) { padding ->
            when (tab) {
                0 -> DashboardScreen(Modifier.padding(padding))
                1 -> StrategiesScreen(Modifier.padding(padding))
                2 -> PositionsScreen(Modifier.padding(padding))
                3 -> Mt5Screen(context, Modifier.padding(padding))
                else -> SettingsScreen(Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun DashboardScreen(modifier: Modifier = Modifier) {
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("READY — BOT STOPPED") }
    val pulse = rememberInfiniteTransition(label = "bot-pulse").animateFloat(
        initialValue = 1f,
        targetValue = if (running) 1.08f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse"
    )

    LazyColumn(
        modifier = modifier.fillMaxSize().background(Ink).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("PIPS-LIFE", color = TextPrimary, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text("TRADING CONTROL CENTER", color = TextMuted, fontSize = 11.sp, letterSpacing = 1.4.sp)
                }
                ConnectionBadge(connected = true)
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("BOT STATUS", color = TextMuted, fontSize = 11.sp, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(12.dp))
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(92.dp).scale(pulse.value)) {
                        Box(Modifier.fillMaxSize().border(2.dp, if (running) Positive else Line, CircleShape))
                        Box(Modifier.size(62.dp).background(if (running) Positive.copy(alpha = .12f) else PanelRaised, CircleShape))
                        Box(Modifier.size(13.dp).background(if (running) Positive else TextMuted, CircleShape))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(if (running) "RUNNING" else "STOPPED", color = if (running) Positive else TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Strategy 001 • QOF", color = TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = { running = !running; status = if (running) "SCANNING MARKET — QOF ACTIVE" else "READY — BOT STOPPED" },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (running) Negative else Positive, contentColor = Ink)
                    ) { Text(if (running) "STOP BOT" else "START BOT", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
                }
            }
        }

        item { SectionTitle("LIVE ACTIVITY", "Dynamic engine state") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    ActivityRow("●", status, running)
                    ActivityRow("→", if (running) "QOF market map online" else "Awaiting start command", running)
                    ActivityRow("→", if (running) "Monitoring approved instruments" else "No orders will be sent", running)
                }
            }
        }

        item { SectionTitle("ACTIVE POSITIONS", "1 open") }
        item { PositionCard(symbol = "XAUUSD", side = "BUY", size = "0.01", entry = "3,345.20", current = "3,347.10", pnl = "+$1.90") }

        item { SectionTitle("STRATEGIES", "Expandable") }
        item {
            StrategyMiniCard("001", "QOF", "ACTIVE / READY", true)
            Spacer(Modifier.height(8.dp))
            StrategyMiniCard("002", "Reserved", "COMING SOON", false)
            Spacer(Modifier.height(8.dp))
            StrategyMiniCard("003", "Reserved", "COMING SOON", false)
        }
    }
}

@Composable private fun ConnectionBadge(connected: Boolean) {
    Row(Modifier.background(Panel, RoundedCornerShape(50)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(start = 10.dp).size(8.dp).background(if (connected) Positive else Negative, CircleShape))
        Text(if (connected) " BACKEND" else " OFFLINE", color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(end = 10.dp))
    }
}

@Composable private fun SectionTitle(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
        Text(subtitle, color = TextMuted, fontSize = 10.sp)
    }
}

@Composable private fun ActivityRow(icon: String, text: String, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, color = if (active) Accent else TextMuted, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
        Text(text, color = if (active) TextPrimary else TextMuted, fontSize = 12.sp)
    }
}

@Composable private fun PositionCard(symbol: String, side: String, size: String, entry: String, current: String, pnl: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(symbol, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("$side  •  $size lot", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Text(pnl, color = Positive, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("ENTRY", entry)
                Metric("CURRENT", current)
                Metric("STATUS", "ACTIVE")
            }
        }
    }
}

@Composable private fun Metric(label: String, value: String) {
    Column {
        Text(label, color = TextMuted, fontSize = 9.sp)
        Text(value, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun StrategyMiniCard(number: String, name: String, state: String, active: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(number, color = if (active) Accent else TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = TextPrimary, fontWeight = FontWeight.Bold)
                Text(state, color = if (active) Positive else TextMuted, fontSize = 10.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
        }
    }
}

@Composable private fun StrategiesScreen(modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().background(Ink).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("STRATEGIES", color = TextPrimary, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Modular strategy slots — Strategy 001 is canonical QOF.", color = TextMuted, fontSize = 12.sp) }
        item { StrategyMiniCard("001", "QOF", "PRIMARY • READY", true) }
        item { StrategyMiniCard("002", "Reserved", "AVAILABLE FOR FUTURE STRATEGY", false) }
        item { StrategyMiniCard("003", "Reserved", "AVAILABLE FOR FUTURE STRATEGY", false) }
        item { OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("ADD STRATEGY SLOT") } }
    }
}

@Composable private fun PositionsScreen(modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().background(Ink).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("POSITIONS", color = TextPrimary, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Live positions reported by the backend.", color = TextMuted, fontSize = 12.sp) }
        item { PositionCard("XAUUSD", "BUY", "0.01", "3,345.20", "3,347.10", "+$1.90") }
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
    var status by remember { mutableStateOf("Enter your broker to find MT5 servers.") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prefs = context.preferencesDataStore.data.first()
        broker = prefs[PreferenceKeys.BROKER].orEmpty()
        account = prefs[PreferenceKeys.ACCOUNT].orEmpty()
        selectedServer = prefs[PreferenceKeys.SERVER].orEmpty()
        status = if (broker.isBlank()) "Enter your broker to find MT5 servers." else "Saved MT5 preference loaded."
    }

    LazyColumn(modifier.fillMaxSize().background(Ink).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Text("MT5 ACCOUNT", color = TextPrimary, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Broker → server → account → password", color = TextMuted, fontSize = 12.sp) }
        item { OutlinedTextField(broker, { broker = it }, Modifier.fillMaxWidth(), label = { Text("Broker name") }, singleLine = true) }
        item { Button(enabled = broker.trim().length >= 2 && !busy, onClick = { busy = true; status = "Searching MetaApi for broker servers…"; api.searchServers(broker) { result -> scope.launch { busy = false; result.onSuccess { matches -> serverList = matches; status = if (matches.isEmpty()) "No known servers found." else "Select the server matching your MT5 account." }.onFailure { status = "Server discovery failed: ${it.message ?: "network error"}" } } } }) { Text(if (busy) "SEARCHING…" else "FIND SERVERS") } }
        items(serverList) { server -> Card(onClick = { selectedServer = server.serverName }, colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(server.brokerName, color = TextMuted, fontSize = 10.sp); Text(server.serverName, color = TextPrimary, fontWeight = FontWeight.Bold); if (server.serverName == selectedServer) Text("SELECTED", color = Positive, fontSize = 10.sp) } } }
        item { OutlinedTextField(selectedServer, { selectedServer = it }, Modifier.fillMaxWidth(), label = { Text("Selected server") }, singleLine = true) }
        item { OutlinedTextField(account, { account = it }, Modifier.fillMaxWidth(), label = { Text("MT5 account number") }, singleLine = true) }
        item { OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("MT5 password") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) }
        item { Button(enabled = account.isNotBlank() && password.isNotBlank() && selectedServer.isNotBlank() && !busy, onClick = { busy = true; status = "Validating MT5 account securely…"; api.connect(account, password, selectedServer) { result -> scope.launch { busy = false; result.onSuccess { connection -> context.preferencesDataStore.edit { prefs -> prefs[PreferenceKeys.BROKER] = broker; prefs[PreferenceKeys.SERVER] = selectedServer; prefs[PreferenceKeys.ACCOUNT] = account }; password = ""; status = "Connected: ${connection.state} • ${connection.server}" }.onFailure { status = "MT5 connection failed: ${it.message ?: "unknown error"}" } } } }) { Text(if (busy) "CONNECTING…" else "CONNECT MT5") } }
        item { Text(status, color = TextMuted, fontSize = 12.sp) }
    }
}

@Composable private fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(Ink).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SETTINGS", color = TextPrimary, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pips-life release", color = TextMuted, fontSize = 11.sp)
                Text("0.2.0 (2)", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("UI foundation • Strategy 001 preserved • Strategy slots reserved", color = TextMuted, fontSize = 11.sp)
                Text("Update channel: Stable", color = TextMuted, fontSize = 11.sp)
            }
        }
    }
}

private object PreferenceKeys {
    val BROKER: Preferences.Key<String> = stringPreferencesKey("mt5_broker")
    val SERVER: Preferences.Key<String> = stringPreferencesKey("mt5_server")
    val ACCOUNT: Preferences.Key<String> = stringPreferencesKey("mt5_account")
}

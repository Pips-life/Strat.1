package life.pips.strat1

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import life.pips.strat1.data.*

private val Context.preferencesDataStore by preferencesDataStore("pips_life_preferences")
private val Ink = Color(0xFF071018)
private val Panel = Color(0xFF0D1822)
private val Raised = Color(0xFF12212D)
private val Line = Color(0xFF223541)
private val PrimaryText = Color(0xFFF4F8FA)
private val Muted = Color(0xFF91A4AF)
private val Green = Color(0xFF35D07F)
private val Red = Color(0xFFFF5E68)
private val Cyan = Color(0xFF28B7D9)
private val DarkScheme = darkColorScheme(primary = Cyan, secondary = Green, background = Ink, surface = Panel, surfaceVariant = Raised, onBackground = PrimaryText, onSurface = PrimaryText, outline = Line, error = Red)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { PipsLifeApp(applicationContext) } }
}

@Composable private fun PipsLifeApp(context: Context) {
    var tab by remember { mutableStateOf(0) }
    MaterialTheme(colorScheme = DarkScheme) {
        Scaffold(containerColor = Ink, bottomBar = {
            NavigationBar(containerColor = Panel) {
                listOf("Home" to Icons.Default.Home, "Strategies" to Icons.Default.Tune, "Positions" to Icons.Default.ShowChart, "MT5" to Icons.Default.AccountBalance, "Settings" to Icons.Default.Settings).forEachIndexed { i, item ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(item.second, item.first) }, label = { Text(item.first, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Cyan, selectedTextColor = Cyan, unselectedIconColor = Muted, unselectedTextColor = Muted, indicatorColor = Raised))
                }
            }
        }) { pad ->
            when (tab) {
                0 -> DashboardScreen(context, Modifier.padding(pad))
                1 -> StrategiesScreen(Modifier.padding(pad))
                2 -> PositionsScreen(context, Modifier.padding(pad))
                3 -> Mt5Screen(context, Modifier.padding(pad))
                else -> SettingsScreen(Modifier.padding(pad))
            }
        }
    }
}

@Composable private fun DashboardScreen(context: Context, modifier: Modifier = Modifier) {
    val stateClient = remember { BackendStateClient() }
    var accountId by remember { mutableStateOf<String?>(null) }
    var live by remember { mutableStateOf<BackendState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(1f, 1.06f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")

    LaunchedEffect(Unit) {
        accountId = context.preferencesDataStore.data.first()[Keys.ACCOUNT_ID]
        while (true) {
            val id = accountId
            if (!id.isNullOrBlank()) {
                refreshing = true
                stateClient.state(id).onSuccess { live = it; error = null }.onFailure { error = it.message }
                refreshing = false
            }
            delay(5000)
        }
    }

    LazyColumn(modifier.fillMaxSize().background(Ink).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("PIPS-LIFE", color = PrimaryText, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("TRADING CONTROL CENTER", color = Muted, fontSize = 11.sp, letterSpacing = 1.4.sp) }
                StatusPill(if (live?.connectionStatus?.equals("CONNECTED", true) == true) "MT5 LIVE" else if (accountId != null) "MT5 CONNECTING" else "NO MT5")
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("BOT STATUS", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.size(88.dp).scale(pulse.value), contentAlignment = Alignment.Center) { Box(Modifier.fillMaxSize().border(2.dp, if (accountId != null) Green else Line, CircleShape)); Box(Modifier.size(58.dp).background(if (accountId != null) Green.copy(alpha = .12f) else Raised, CircleShape)); Box(Modifier.size(13.dp).background(if (accountId != null) Green else Muted, CircleShape)) }
                    Spacer(Modifier.height(10.dp))
                    Text(if (accountId != null) "BACKEND LINKED" else "AWAITING MT5", color = if (accountId != null) Green else PrimaryText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Strategy 001 • QOF", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(enabled = false, onClick = {}, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp)) { Text("BOT CONTROL AWAITING EXECUTION API") }
                }
            }
        }
        if (live != null) {
            item { Section("ACCOUNT", live!!.login) }
            item { AccountCard(live!!) }
            item { Section("ACTIVE POSITIONS", "${live!!.positions.size} open") }
            items(live!!.positions) { PositionCard(it) }
        } else {
            item { Section("LIVE DATA", "No account linked") }
            item { Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) { Text(error ?: "Connect an MT5 account from the MT5 tab. Live balance, equity and positions will appear here automatically.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp)) } }
        }
        item { Section("STRATEGIES", "Expandable") }
        item { StrategyCard("001", "QOF", "PRIMARY • READY", true) }
        item { StrategyCard("002", "Reserved", "FUTURE STRATEGY SLOT", false) }
        item { StrategyCard("003", "Reserved", "FUTURE STRATEGY SLOT", false) }
        if (refreshing) item { Text("Refreshing live MT5 data…", color = Muted, fontSize = 10.sp) }
    }
}

@Composable private fun AccountCard(s: BackendState) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(s.server, color = PrimaryText, fontWeight = FontWeight.Bold); Text(s.connectionStatus, color = if (s.connectionStatus.equals("CONNECTED", true)) Green else Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("BALANCE", money(s.balance, s.currency)); Metric("EQUITY", money(s.equity, s.currency)); Metric("TRADE", if (s.tradeAllowed) "ALLOWED" else "BLOCKED") }
    } }
}

@Composable private fun PositionCard(p: BackendPosition) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text(p.symbol, color = PrimaryText, fontSize = 17.sp, fontWeight = FontWeight.Bold); Text("${p.side} • ${p.volume} lot", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold) }; Text(money(p.profit, "USD"), color = if (p.profit >= 0) Green else Red, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(12.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("ENTRY", number(p.entry)); Metric("CURRENT", number(p.current)); Metric("SL", number(p.stopLoss)) }
    } }
}

@Composable private fun PositionsScreen(context: Context, modifier: Modifier = Modifier) {
    val client = remember { BackendStateClient() }; var state by remember { mutableStateOf<BackendState?>(null) }
    LaunchedEffect(Unit) { val id = context.preferencesDataStore.data.first()[Keys.ACCOUNT_ID]; if (!id.isNullOrBlank()) client.state(id).onSuccess { state = it } }
    LazyColumn(modifier.fillMaxSize().background(Ink).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { item { Text("POSITIONS", color = PrimaryText, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Live positions from the connected MT5 account.", color = Muted, fontSize = 12.sp) }; if (state == null) item { Text("No live account connected.", color = Muted) } else items(state!!.positions) { PositionCard(it) } }
}

@Composable private fun Mt5Screen(context: Context, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope(); val api = remember { Mt5ApiClient() }; var broker by remember { mutableStateOf("") }; var account by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var servers by remember { mutableStateOf(emptyList<Mt5Server>()) }; var selected by remember { mutableStateOf("") }; var status by remember { mutableStateOf("Connect an MT5 account to feed the dashboard.") }; var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { val p = context.preferencesDataStore.data.first(); broker = p[Keys.BROKER].orEmpty(); account = p[Keys.ACCOUNT].orEmpty(); selected = p[Keys.SERVER].orEmpty(); if (p[Keys.ACCOUNT_ID] != null) status = "MT5 account linked to backend." }
    LazyColumn(modifier.fillMaxSize().background(Ink).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Text("MT5 ACCOUNT", color = PrimaryText, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Credentials go to the Pips-life backend over HTTPS; the MetaApi token stays server-side.", color = Muted, fontSize = 12.sp) }
        item { Field(broker, { broker = it }, "Broker name") }
        item { Button(enabled = broker.trim().length >= 2 && !busy, onClick = { busy = true; status = "Finding broker servers…"; api.searchServers(broker) { r -> scope.launch { busy = false; r.onSuccess { servers = it; status = "Select the exact MT5 server." }.onFailure { status = it.message ?: "Server discovery failed" } } } }) { Text(if (busy) "SEARCHING…" else "FIND SERVERS") } }
        items(servers) { s -> Card(onClick = { selected = s.serverName }, colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(s.brokerName, color = Muted, fontSize = 10.sp); Text(s.serverName, color = PrimaryText, fontWeight = FontWeight.Bold); if (selected == s.serverName) Text("SELECTED", color = Green, fontSize = 10.sp) } } }
        item { Field(selected, { selected = it }, "MT5 server") }
        item { Field(account, { account = it }, "MT5 account number") }
        item { OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("MT5 password") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) }
        item { Button(enabled = account.isNotBlank() && password.isNotBlank() && selected.isNotBlank() && !busy, onClick = { busy = true; status = "Connecting MT5 through backend…"; api.connect(account, password, selected, broker) { r -> scope.launch { busy = false; r.onSuccess { c -> if (c.accountId.isNullOrBlank()) status = "Backend did not return an account id." else { context.preferencesDataStore.edit { it[Keys.ACCOUNT_ID] = c.accountId; it[Keys.BROKER] = broker; it[Keys.ACCOUNT] = account; it[Keys.SERVER] = selected }; password = ""; status = "Backend linked • ${c.connectionStatus}" } }.onFailure { status = it.message ?: "MT5 connection failed" } } } }) { Text(if (busy) "CONNECTING…" else "CONNECT MT5 TO BACKEND") } }
        item { Text(status, color = Muted, fontSize = 12.sp) }
    }
}

@Composable private fun StrategiesScreen(modifier: Modifier = Modifier) { LazyColumn(modifier.fillMaxSize().background(Ink).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { item { Text("STRATEGIES", color = PrimaryText, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Modular strategy slots — Strategy 001 remains primary.", color = Muted, fontSize = 12.sp) }; item { StrategyCard("001", "QOF", "PRIMARY • READY", true) }; item { StrategyCard("002", "Reserved", "FUTURE STRATEGY SLOT", false) }; item { StrategyCard("003", "Reserved", "FUTURE STRATEGY SLOT", false) }; item { OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("ADD STRATEGY SLOT") } } } }
@Composable private fun SettingsScreen(modifier: Modifier = Modifier) { Column(modifier.fillMaxSize().background(Ink).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("SETTINGS", color = PrimaryText, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Pips-life 0.2.1 (3)", color = Muted); Text("Live MT5 backend integration layer enabled.", color = Muted, fontSize = 12.sp) } }
@Composable private fun StrategyCard(n: String, name: String, state: String, active: Boolean) { Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Text(n, color = if (active) Cyan else Muted, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp)); Column(Modifier.weight(1f)) { Text(name, color = PrimaryText, fontWeight = FontWeight.Bold); Text(state, color = if (active) Green else Muted, fontSize = 10.sp) }; Icon(Icons.Default.ChevronRight, null, tint = Muted) } } }
@Composable private fun Section(title: String, sub: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) { Text(title, color = PrimaryText, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text(sub, color = Muted, fontSize = 10.sp) } }
@Composable private fun StatusPill(text: String) { Row(Modifier.background(Panel, RoundedCornerShape(50)), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.padding(start = 10.dp).size(8.dp).background(if (text == "MT5 LIVE") Green else Muted, CircleShape)); Text(" $text", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(end = 10.dp)) } }
@Composable private fun Metric(label: String, value: String) { Column { Text(label, color = Muted, fontSize = 9.sp); Text(value, color = PrimaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) } }
@Composable private fun Field(value: String, set: (String) -> Unit, label: String) { OutlinedTextField(value, set, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true) }
private fun number(v: Double) = if (v.isNaN()) "—" else "%.5f".format(v)
private fun money(v: Double, currency: String) = if (v.isNaN()) "—" else "%s %.2f".format(currency.ifBlank { "USD" }, v)
private object Keys { val BROKER = stringPreferencesKey("mt5_broker"); val SERVER = stringPreferencesKey("mt5_server"); val ACCOUNT = stringPreferencesKey("mt5_account"); val ACCOUNT_ID = stringPreferencesKey("mt5_account_id") }

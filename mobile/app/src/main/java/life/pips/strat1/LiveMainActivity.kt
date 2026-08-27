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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import life.pips.strat1.data.LivePosition
import life.pips.strat1.data.LiveTradingApi
import life.pips.strat1.data.LiveTradingState
import life.pips.strat1.data.Mt5ApiClient
import life.pips.strat1.data.Mt5Server
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

private val Context.livePreferences by preferencesDataStore("pips_life_live")
private val InkLive = Color(0xFF071018)
private val PanelLive = Color(0xFF0D1822)
private val RaisedLive = Color(0xFF12212D)
private val LineLive = Color(0xFF223541)
private val TextLive = Color(0xFFF4F8FA)
private val MutedLive = Color(0xFF91A4AF)
private val GreenLive = Color(0xFF35D07F)
private val RedLive = Color(0xFFFF5E68)
private val CyanLive = Color(0xFF28B7D9)

class LiveMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { LivePipsLifeApp(applicationContext) } }
}

@Composable private fun LivePipsLifeApp(context: Context) {
    var tab by remember { mutableStateOf(0) }
    MaterialTheme(colorScheme = darkColorScheme(primary = CyanLive, secondary = GreenLive, background = InkLive, surface = PanelLive, onSurface = TextLive, onBackground = TextLive, error = RedLive, outline = LineLive)) {
        Scaffold(containerColor = InkLive, bottomBar = {
            NavigationBar(containerColor = PanelLive) {
                listOf("Home", "Positions", "MT5", "Settings").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(listOf(Icons.Default.Home, Icons.Default.ShowChart, Icons.Default.AccountBalance, Icons.Default.Settings)[index], label) }, label = { Text(label, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = CyanLive, selectedTextColor = CyanLive, unselectedIconColor = MutedLive, unselectedTextColor = MutedLive, indicatorColor = RaisedLive))
                }
            }
        }) { padding -> when (tab) { 0 -> LiveDashboard(context, Modifier.padding(padding)); 1 -> LivePositions(context, Modifier.padding(padding)); 2 -> LiveMt5(context, Modifier.padding(padding)); else -> LiveSettings(Modifier.padding(padding)) } }
    }
}

@Composable private fun LiveDashboard(context: Context, modifier: Modifier) {
    val api = remember { LiveTradingApi() }
    var accountId by remember { mutableStateOf<String?>(null) }; var state by remember { mutableStateOf<LiveTradingState?>(null) }; var error by remember { mutableStateOf<String?>(null) }; var loading by remember { mutableStateOf(false) }
    val pulse = rememberInfiniteTransition(label = "live-pulse").animateFloat(1f, 1.06f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "live-pulse-value")
    LaunchedEffect(Unit) { accountId = context.livePreferences.data.first()[LiveKeys.ACCOUNT_ID] }
    LaunchedEffect(accountId) { val id = accountId ?: return@LaunchedEffect; while (true) { loading = true; api.state(id).onSuccess { state = it; error = null }.onFailure { error = it.message }; loading = false; delay(5000) } }
    LazyColumn(modifier.fillMaxSize().background(InkLive).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("PIPS-LIFE", color = TextLive, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("LIVE TRADING CONTROL CENTER", color = MutedLive, fontSize = 10.sp, letterSpacing = 1.2.sp) }; StatusBadge(state?.connectionStatus ?: "NO MT5") } }
        item { Card(colors = CardDefaults.cardColors(containerColor = PanelLive), shape = RoundedCornerShape(22.dp)) { Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("LIVE ACCOUNT STATUS", color = MutedLive, fontSize = 11.sp, letterSpacing = 1.4.sp); Spacer(Modifier.height(12.dp)); Box(contentAlignment = Alignment.Center, modifier = Modifier.size(88.dp).scale(if (state?.connectionStatus == "CONNECTED") pulse.value else 1f)) { Box(Modifier.fillMaxSize().border(2.dp, if (state?.connectionStatus == "CONNECTED") GreenLive else LineLive, CircleShape)); Box(Modifier.size(58.dp).background(if (state?.connectionStatus == "CONNECTED") GreenLive.copy(alpha = .12f) else RaisedLive, CircleShape)); Box(Modifier.size(13.dp).background(if (state?.connectionStatus == "CONNECTED") GreenLive else MutedLive, CircleShape)) }; Spacer(Modifier.height(10.dp)); Text(state?.connectionStatus ?: "NOT CONNECTED", color = if (state?.connectionStatus == "CONNECTED") GreenLive else TextLive, fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(if (state != null) "MT5 ${state!!.login} • ${state!!.server}" else "Connect an MT5 account from the MT5 tab", color = MutedLive, fontSize = 11.sp) } } }
        item { SectionLive("ACCOUNT", if (loading) "REFRESHING" else "LIVE") }
        item { Card(colors = CardDefaults.cardColors(containerColor = PanelLive), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { MoneyMetric("BALANCE", state?.balance, state?.currency); MoneyMetric("EQUITY", state?.equity, state?.currency); MoneyMetric("FREE MARGIN", state?.freeMargin, state?.currency) }; Text(if (state?.tradeAllowed == true) "TRADING PERMITTED" else if (state?.tradeAllowed == false) "TRADING NOT PERMITTED" else "TRADING STATUS UNKNOWN", color = if (state?.tradeAllowed == true) GreenLive else MutedLive, fontSize = 10.sp, fontWeight = FontWeight.Bold) } } }
        item { SectionLive("STRATEGY 001", "BACKEND TELEMETRY") }
        item { Card(colors = CardDefaults.cardColors(containerColor = PanelLive), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("QOF Confluence", color = TextLive, fontSize = 16.sp, fontWeight = FontWeight.Bold); Text("Live MT5 account telemetry is connected. Strategy execution remains controlled by the backend runtime; this app never simulates an order state.", color = MutedLive, fontSize = 11.sp) } } }
        item { SectionLive("ACTIVE POSITIONS", "${state?.positions?.size ?: 0} open") }
        if (state?.positions.isNullOrEmpty()) item { EmptyCard("No open MT5 positions") }
        items(state?.positions ?: emptyList()) { LivePositionCard(it) }
        if (error != null) item { Text("LIVE DATA ERROR: $error", color = RedLive, fontSize = 11.sp) }
    }
}

@Composable private fun LivePositions(context: Context, modifier: Modifier) {
    val api = remember { LiveTradingApi() }; var state by remember { mutableStateOf<LiveTradingState?>(null) }
    LaunchedEffect(Unit) { val id = context.livePreferences.data.first()[LiveKeys.ACCOUNT_ID] ?: return@LaunchedEffect; api.state(id).onSuccess { state = it } }
    LazyColumn(modifier.fillMaxSize().background(InkLive).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) { item { Text("POSITIONS", color = TextLive, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Live data from the connected MT5 account.", color = MutedLive, fontSize = 12.sp) }; if (state?.positions.isNullOrEmpty()) item { EmptyCard("No open MT5 positions") }; items(state?.positions ?: emptyList()) { LivePositionCard(it) } }
}

@Composable private fun LiveMt5(context: Context, modifier: Modifier) {
    val scope = rememberCoroutineScope(); val api = remember { Mt5ApiClient() }; var broker by remember { mutableStateOf("") }; var account by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var server by remember { mutableStateOf("") }; var servers by remember { mutableStateOf(emptyList<Mt5Server>()) }; var status by remember { mutableStateOf("Enter broker, find server, then connect the MT5 account.") }; var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { val p = context.livePreferences.data.first(); broker = p[LiveKeys.BROKER].orEmpty(); account = p[LiveKeys.LOGIN].orEmpty(); server = p[LiveKeys.SERVER].orEmpty() }
    LazyColumn(modifier.fillMaxSize().background(InkLive).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text("MT5 CONNECTION", color = TextLive, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Credentials are sent to the backend; the app does not persist the password.", color = MutedLive, fontSize = 11.sp) }
        item { OutlinedTextField(broker, { broker = it }, Modifier.fillMaxWidth(), label = { Text("Broker") }, singleLine = true) }
        item { Button(enabled = broker.length >= 2 && !busy, onClick = { busy = true; status = "Searching broker servers…"; api.searchServers(broker) { result -> scope.launch { busy = false; result.onSuccess { servers = it; status = if (it.isEmpty()) "No matching servers found." else "Select the MT5 server." }.onFailure { status = it.message ?: "Server search failed" } } } }) { Text(if (busy) "SEARCHING…" else "FIND SERVERS") } }
        items(servers) { item -> Card(onClick = { server = item.serverName }, colors = CardDefaults.cardColors(containerColor = if (server == item.serverName) RaisedLive else PanelLive), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(13.dp)) { Text(item.brokerName, color = MutedLive, fontSize = 10.sp); Text(item.serverName, color = TextLive, fontWeight = FontWeight.Bold); if (server == item.serverName) Text("SELECTED", color = GreenLive, fontSize = 9.sp) } } }
        item { OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("MT5 server") }, singleLine = true) }
        item { OutlinedTextField(account, { account = it }, Modifier.fillMaxWidth(), label = { Text("MT5 account/login") }, singleLine = true) }
        item { OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("MT5 password") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) }
        item { Button(enabled = !busy && account.isNotBlank() && password.isNotBlank() && server.isNotBlank(), onClick = { busy = true; status = "Connecting MT5 through backend…"; api.connect(account, password, server, broker) { result -> scope.launch { busy = false; result.onSuccess { connection -> context.livePreferences.edit { p -> p[LiveKeys.ACCOUNT_ID] = connection.accountId.orEmpty(); p[LiveKeys.LOGIN] = account; p[LiveKeys.SERVER] = server; p[LiveKeys.BROKER] = broker }; password = ""; status = "Account registered: ${connection.accountId ?: "pending"} • ${connection.state} • ${connection.connectionStatus}" }.onFailure { status = it.message ?: "MT5 connection failed" } } } }) { Text(if (busy) "CONNECTING…" else "CONNECT MT5 ACCOUNT") } }
        item { Text(status, color = MutedLive, fontSize = 11.sp) }
    }
}

@Composable private fun LiveSettings(modifier: Modifier) { Column(modifier.fillMaxSize().background(InkLive).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("SETTINGS", color = TextLive, fontSize = 25.sp, fontWeight = FontWeight.Bold); Card(colors = CardDefaults.cardColors(containerColor = PanelLive), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { Text("Pips-life", color = TextLive, fontSize = 18.sp, fontWeight = FontWeight.Bold); Text("Release 0.2.1 (3)", color = CyanLive, fontSize = 12.sp); Text("Live MT5 telemetry enabled • Strategy slots preserved • no purple palette", color = MutedLive, fontSize = 11.sp) } } } }
@Composable private fun StatusBadge(value: String) { Row(Modifier.background(PanelLive, RoundedCornerShape(50)), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.padding(start = 10.dp).size(8.dp).background(if (value == "CONNECTED") GreenLive else MutedLive, CircleShape)); Text(" $value", color = MutedLive, fontSize = 9.sp, modifier = Modifier.padding(end = 10.dp)) } }
@Composable private fun SectionLive(title: String, subtitle: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) { Text(title, color = TextLive, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp); Text(subtitle, color = MutedLive, fontSize = 9.sp) } }
@Composable private fun MoneyMetric(label: String, value: Double?, currency: String?) { Column { Text(label, color = MutedLive, fontSize = 8.sp); Text(if (value == null) "—" else String.format(Locale.US, "%.2f", value), color = TextLive, fontSize = 13.sp, fontWeight = FontWeight.Bold); if (!currency.isNullOrBlank()) Text(currency, color = MutedLive, fontSize = 8.sp) } }
@Composable private fun EmptyCard(text: String) { Card(colors = CardDefaults.cardColors(containerColor = PanelLive), shape = RoundedCornerShape(18.dp)) { Text(text, color = MutedLive, modifier = Modifier.padding(18.dp), fontSize = 12.sp) } }
@Composable private fun LivePositionCard(p: LivePosition) { Card(colors = CardDefaults.cardColors(containerColor = PanelLive), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text(p.symbol, color = TextLive, fontSize = 17.sp, fontWeight = FontWeight.Bold); Text("${p.side} • ${String.format(Locale.US, "%.2f", p.volume)} lot", color = CyanLive, fontSize = 10.sp, fontWeight = FontWeight.Bold) }; Text(String.format(Locale.US, "%+.2f", p.profit), color = if (p.profit >= 0) GreenLive else RedLive, fontSize = 17.sp, fontWeight = FontWeight.Bold) }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { SmallMetric("ENTRY", p.entry); SmallMetric("CURRENT", p.current); SmallMetric("SL", p.stopLoss); SmallMetric("TP", p.takeProfit) } } } }
@Composable private fun SmallMetric(label: String, value: Double) { Column { Text(label, color = MutedLive, fontSize = 8.sp); Text(if (value == 0.0) "—" else String.format(Locale.US, "%.5f", value), color = TextLive, fontSize = 10.sp) } }

private object LiveKeys { val ACCOUNT_ID = stringPreferencesKey("account_id"); val LOGIN = stringPreferencesKey("login"); val SERVER = stringPreferencesKey("server"); val BROKER = stringPreferencesKey("broker") }

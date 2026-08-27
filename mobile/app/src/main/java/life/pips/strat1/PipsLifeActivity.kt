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
import life.pips.strat1.data.BotControlState
import life.pips.strat1.data.LivePosition
import life.pips.strat1.data.LiveTradingApi
import life.pips.strat1.data.LiveTradingState
import life.pips.strat1.data.Mt5Server
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

private val Context.pipsPrefs by preferencesDataStore("pips_life_live")
private val AID = stringPreferencesKey("account_id")
private val STOKEN = stringPreferencesKey("session_token")
private val BROKER = stringPreferencesKey("broker")
private val SERVER = stringPreferencesKey("server")
private val LOGIN = stringPreferencesKey("login")
private val Ink = Color(0xFF071018); private val Panel = Color(0xFF0D1822); private val Raised = Color(0xFF12212D); private val Line = Color(0xFF223541); private val Text = Color(0xFFF4F8FA); private val Muted = Color(0xFF91A4AF); private val Green = Color(0xFF35D07F); private val Red = Color(0xFFFF5E68); private val Cyan = Color(0xFF28B7D9)

class PipsLifeActivity : ComponentActivity() { override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { PipsLifeApp(applicationContext) } } }

@Composable private fun PipsLifeApp(context: Context) {
    val api = remember { LiveTradingApi() }; var tab by remember { mutableStateOf(0) }; var accountId by remember { mutableStateOf<String?>(null) }; var token by remember { mutableStateOf<String?>(null) }; var state by remember { mutableStateOf<LiveTradingState?>(null) }; var bot by remember { mutableStateOf<BotControlState?>(null) }
    LaunchedEffect(Unit) { val p = context.pipsPrefs.data.first(); accountId = p[AID]; token = p[STOKEN] }
    LaunchedEffect(accountId, token) { val id = accountId; val t = token; if (id != null) while (true) { api.state(id, t).onSuccess { state = it }; if (!t.isNullOrBlank()) api.botStatus(id, t).onSuccess { bot = it }; delay(4000) } }
    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, secondary = Green, background = Ink, surface = Panel, surfaceVariant = Raised, onBackground = Text, onSurface = Text, outline = Line, error = Red)) {
        Scaffold(containerColor = Ink, bottomBar = { NavigationBar(containerColor = Panel) { listOf("Home", "Strategies", "Positions", "MT5", "Settings").forEachIndexed { i, label -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(listOf(Icons.Default.Home, Icons.Default.Tune, Icons.Default.ShowChart, Icons.Default.AccountBalance, Icons.Default.Settings)[i], label) }, label = { Text(label, fontSize = 9.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Cyan, selectedTextColor = Cyan, unselectedIconColor = Muted, unselectedTextColor = Muted, indicatorColor = Raised)) } } }) { pad -> when (tab) { 0 -> Home(context, api, accountId, token, state, bot, { bot = it }, Modifier.padding(pad)); 1 -> Strategies(Modifier.padding(pad)); 2 -> Positions(state, Modifier.padding(pad)); 3 -> Mt5(context, api, { id, session -> accountId = id; token = session }, Modifier.padding(pad)); else -> Settings(Modifier.padding(pad)) } }
    }
}

@Composable private fun Home(context: Context, api: LiveTradingApi, id: String?, token: String?, state: LiveTradingState?, bot: BotControlState?, setBot: (BotControlState) -> Unit, modifier: Modifier) {
    var busy by remember { mutableStateOf(false) }; val pulse = rememberInfiniteTransition(label = "bot").animateFloat(1f, if (bot?.running == true) 1.07f else 1f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "pulse")
    LazyColumn(modifier.fillMaxSize().background(Ink).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("PIPS-LIFE", color = Text, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("LIVE TRADING CONTROL CENTER", color = Muted, fontSize = 10.sp, letterSpacing = 1.2.sp) }; Badge(state?.connectionStatus ?: "NO MT5") } }
        item { Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) { Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("BOT STATUS", color = Muted, fontSize = 11.sp, letterSpacing = 1.4.sp); Spacer(Modifier.height(10.dp)); Box(Modifier.size(86.dp).scale(pulse.value).border(2.dp, if (bot?.running == true) Green else Line, CircleShape), contentAlignment = Alignment.Center) { Box(Modifier.size(13.dp).background(if (bot?.running == true) Green else Muted, CircleShape)) }; Spacer(Modifier.height(9.dp)); Text(if (bot?.running == true) "RUNNING" else "STOPPED", color = if (bot?.running == true) Green else Text, fontSize = 21.sp, fontWeight = FontWeight.Bold); Text("Strategy 001 • QOF", color = Muted, fontSize = 11.sp); Spacer(Modifier.height(14.dp)); Button(enabled = id != null && !token.isNullOrBlank() && bot?.configured == true && !busy, onClick = { busy = true; kotlinx.coroutines.GlobalScope.launch { runCatching { api.setBot(id!!, token!!, bot?.running != true) }.onSuccess { setBot(it) }.onFailure { setBot(BotControlState(bot?.running == true, "ERROR: ${it.message}", "001", it.message ?: "Command failed", true)) }; busy = false } }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = if (bot?.running == true) Red else Green, contentColor = Ink)) { Text(if (busy) "SENDING…" else if (bot?.running == true) "STOP BOT" else "START BOT", fontWeight = FontWeight.Bold) }; if (bot?.configured != true) Text("Backend strategy-runner control is not configured", color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 7.dp)) } } }
        item { Section("ACCOUNT", if (state == null) "NOT CONNECTED" else state.server) }
        item { Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Money("BALANCE", state?.balance, state?.currency); Money("EQUITY", state?.equity, state?.currency); Money("FREE MARGIN", state?.freeMargin, state?.currency) } } }
        item { Section("LIVE ACTIVITY", bot?.activity ?: "Connect MT5 to activate live telemetry") }
        item { Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp)) { Text(bot?.activity ?: "Awaiting MT5 connection", color = Text, fontSize = 12.sp); Text("Positions: ${state?.positions?.size ?: 0} • Trading: ${if (state?.tradeAllowed == true) "ALLOWED" else "NOT CONFIRMED"}", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp)) } } }
        item { Section("ACTIVE POSITIONS", "${state?.positions?.size ?: 0} open") }; if (state?.positions.isNullOrEmpty()) item { Empty("No open MT5 positions") }; items(state?.positions ?: emptyList()) { Position(it) }
    }
}

@Composable private fun Mt5(context: Context, api: LiveTradingApi, connected: (String, String) -> Unit, modifier: Modifier) { val scope = rememberCoroutineScope(); var broker by remember { mutableStateOf("") }; var login by remember { mutableStateOf("") }; var server by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var servers by remember { mutableStateOf(emptyList<Mt5Server>()) }; var busy by remember { mutableStateOf(false) }; var status by remember { mutableStateOf("Find your broker server, then connect.") }; val serverApi = remember { life.pips.strat1.data.Mt5ApiClient() }
    LazyColumn(modifier.fillMaxSize().background(Ink).padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp), contentPadding = PaddingValues(bottom = 24.dp)) { item { Text("MT5 CONNECTION", color = Text, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Password is sent to the backend and cleared from the screen after connection.", color = Muted, fontSize = 11.sp) }; item { OutlinedTextField(broker, { broker = it }, Modifier.fillMaxWidth(), label = { Text("Broker") }, singleLine = true) }; item { Button(enabled = broker.length >= 2 && !busy, onClick = { busy = true; serverApi.searchServers(broker) { r -> scope.launch { busy = false; r.onSuccess { servers = it; status = "Select the exact MT5 server." }.onFailure { status = it.message ?: "Server search failed" } } } }) { Text(if (busy) "SEARCHING…" else "FIND SERVERS") } }; items(servers) { s -> Card(onClick = { server = s.serverName }, colors = CardDefaults.cardColors(containerColor = if (server == s.serverName) Raised else Panel), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(13.dp)) { Text(s.brokerName, color = Muted, fontSize = 10.sp); Text(s.serverName, color = Text, fontWeight = FontWeight.Bold); Text(s.environment.uppercase(), color = if (s.environment == "demo") Cyan else Green, fontSize = 9.sp) } } }; item { OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("MT5 server") }, singleLine = true) }; item { OutlinedTextField(login, { login = it }, Modifier.fillMaxWidth(), label = { Text("MT5 account/login") }, singleLine = true) }; item { OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("MT5 password") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) }; item { Button(enabled = !busy && login.isNotBlank() && password.isNotBlank() && server.isNotBlank(), onClick = { busy = true; status = "Connecting through Pips-life backend…"; scope.launch { runCatching { api.connect(login, password, server, broker) }.onSuccess { c -> context.pipsPrefs.edit { p -> p[AID] = c.accountId; p[STOKEN] = c.sessionToken; p[BROKER] = broker; p[SERVER] = c.server; p[LOGIN] = login }; password = ""; connected(c.accountId, c.sessionToken); status = "CONNECTED • ${c.connectionStatus}" }.onFailure { status = it.message ?: "MT5 connection failed" }; busy = false } }) { Text(if (busy) "CONNECTING…" else "CONNECT MT5 ACCOUNT") } }; item { Text(status, color = Muted, fontSize = 11.sp) } }
}

@Composable private fun Positions(state: LiveTradingState?, modifier: Modifier) { LazyColumn(modifier.fillMaxSize().background(Ink).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { item { Text("POSITIONS", color = Text, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Actual positions from the connected MT5 account.", color = Muted, fontSize = 11.sp) }; if (state?.positions.isNullOrEmpty()) item { Empty("No open MT5 positions") }; items(state?.positions ?: emptyList()) { Position(it) } } }
@Composable private fun Strategies(modifier: Modifier) { LazyColumn(modifier.fillMaxSize().background(Ink).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { Text("STRATEGIES", color = Text, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Reserved slots keep the app extensible.", color = Muted, fontSize = 11.sp) }; item { Strategy("001", "QOF Confluence", "PRIMARY", true) }; item { Strategy("002", "Reserved", "COMING SOON", false) }; item { Strategy("003", "Reserved", "COMING SOON", false) } } }
@Composable private fun Settings(modifier: Modifier) { Column(modifier.fillMaxSize().background(Ink).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("SETTINGS", color = Text, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Pips-life 0.2.1 (3)", color = Cyan, fontSize = 15.sp); Text("Live MetaApi telemetry • authenticated account session • backend bot-control contract • no purple", color = Muted, fontSize = 11.sp) } }
@Composable private fun Badge(value: String) { Row(Modifier.background(Panel, RoundedCornerShape(50)), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.padding(start = 10.dp).size(8.dp).background(if (value == "CONNECTED") Green else Muted, CircleShape)); Text(" $value", color = Muted, fontSize = 9.sp, modifier = Modifier.padding(end = 10.dp)) } }
@Composable private fun Section(a: String, b: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(a, color = Text, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text(b, color = Muted, fontSize = 9.sp) } }
@Composable private fun Money(label: String, value: Double?, currency: String?) { Column { Text(label, color = Muted, fontSize = 8.sp); Text(if (value == null) "—" else String.format(Locale.US, "%.2f", value), color = Text, fontSize = 13.sp, fontWeight = FontWeight.Bold); if (!currency.isNullOrBlank()) Text(currency, color = Muted, fontSize = 8.sp) } }
@Composable private fun Position(p: LivePosition) { Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text(p.symbol, color = Text, fontSize = 17.sp, fontWeight = FontWeight.Bold); Text("${p.side} • ${String.format(Locale.US, "%.2f", p.volume)} lot", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold) }; Text(String.format(Locale.US, "%+.2f", p.profit), color = if (p.profit >= 0) Green else Red, fontSize = 17.sp, fontWeight = FontWeight.Bold) }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Small("ENTRY", p.entry); Small("CURRENT", p.current); Small("SL", p.stopLoss); Small("TP", p.takeProfit) } } } }
@Composable private fun Small(label: String, value: Double) { Column { Text(label, color = Muted, fontSize = 8.sp); Text(if (value == 0.0) "—" else String.format(Locale.US, "%.5f", value), color = Text, fontSize = 10.sp) } }
@Composable private fun Strategy(n: String, name: String, s: String, active: Boolean) { Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) { Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) { Text(n, color = if (active) Cyan else Muted, fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp)); Column(Modifier.weight(1f)) { Text(name, color = Text, fontWeight = FontWeight.Bold); Text(s, color = if (active) Green else Muted, fontSize = 10.sp) }; Icon(Icons.Default.ChevronRight, null, tint = Muted) } } }
@Composable private fun Empty(s: String) { Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) { Text(s, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(18.dp)) } }

package life.pips.strat1

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import life.pips.strat1.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

private val Context.pipsDataStore by preferencesDataStore("pips_life_session")
private val ACCOUNT_ID = stringPreferencesKey("account_id")
private val SESSION_TOKEN = stringPreferencesKey("session_token")
private val SERVER = stringPreferencesKey("server")

private val Ink = Color(0xFF050812)
private val Panel = Color(0xFF0C1220)
private val Raised = Color(0xFF141D30)
private val Line = Color(0xFF26344A)
private val Primary = Color(0xFFF6FAFF)
private val Muted = Color(0xFF8FA2B8)
private val Green = Color(0xFF35E58A)
private val Red = Color(0xFFFF5570)
private val Cyan = Color(0xFF27D7FF)
private val Amber = Color(0xFFFFC857)
private val Purple = Color(0xFFB77CFF)
private val Blue = Color(0xFF6D8CFF)
private val Scheme = darkColorScheme(primary = Cyan, secondary = Green, tertiary = Purple, background = Ink, surface = Panel, surfaceVariant = Raised, onBackground = Primary, onSurface = Primary, outline = Line, error = Red)

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9001)
        setContent { PipsLifeApp(applicationContext) }
    }
}

private enum class Screen(val label: String) { HOME("Home"), MARKETS("Markets"), STRATEGIES("Strategies"), MT5("MT5"), ACTIVITY("Activity") }

@Composable
private fun PipsLifeApp(context: Context) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    var session by remember { mutableStateOf<BackendSession?>(null) }
    val api = remember { BackendApiClient() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val p = context.pipsDataStore.data.first()
        val id = p[ACCOUNT_ID]; val token = p[SESSION_TOKEN]
        if (!id.isNullOrBlank() && !token.isNullOrBlank()) session = BackendSession(id, token, p[SERVER].orEmpty())
    }
    MaterialTheme(colorScheme = Scheme) {
        Scaffold(containerColor = Ink, bottomBar = {
            NavigationBar(containerColor = Panel, tonalElevation = 12.dp) {
                listOf(Screen.HOME to Icons.Default.Home, Screen.MARKETS to Icons.Default.ShowChart, Screen.STRATEGIES to Icons.Default.Tune, Screen.MT5 to Icons.Default.AccountBalance, Screen.ACTIVITY to Icons.Default.History).forEach { (s, icon) ->
                    NavigationBarItem(selected = screen == s, onClick = { screen = s }, icon = { Icon(icon, s.label) }, label = { Text(s.label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Ink, selectedTextColor = Cyan, unselectedIconColor = Muted, unselectedTextColor = Muted, indicatorColor = Cyan))
                }
            }
        }) { pad ->
            when (screen) {
                Screen.HOME -> HomeScreen(Modifier.padding(pad), api, session) { screen = Screen.MT5 }
                Screen.MARKETS -> MarketsScreen(Modifier.padding(pad), session)
                Screen.STRATEGIES -> StrategiesScreen(Modifier.padding(pad), api, session)
                Screen.MT5 -> Mt5Screen(Modifier.padding(pad), api, session) { s -> session = s; scope.launch { context.pipsDataStore.edit { it[ACCOUNT_ID] = s.accountId; it[SESSION_TOKEN] = s.token; it[SERVER] = s.server } } }
                Screen.ACTIVITY -> ActivityScreen(Modifier.padding(pad), api, session)
            }
        }
    }
}

@Composable
private fun TopBar(title: String, subtitle: String, connected: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column { Text("PIPS-LIFE", color = Cyan, fontSize = 26.sp, fontWeight = FontWeight.Black); Text(subtitle, color = Muted, fontSize = 10.sp, letterSpacing = 1.2.sp) }
        Pill(if (connected) "MT5 LIVE" else "OFFLINE")
    }
}

@Composable
private fun HomeScreen(modifier: Modifier, api: BackendApiClient, session: BackendSession?, openMt5: () -> Unit) {
    var state by remember { mutableStateOf<LiveState?>(null) }; var bot by remember { mutableStateOf<BotState?>(null) }; var busy by remember { mutableStateOf(false) }; val scope = rememberCoroutineScope()
    val running = bot?.state?.uppercase(Locale.US)?.contains("RUN") == true
    LaunchedEffect(session) { if (session == null) { state = null; bot = null } else while (true) { api.liveState(session).onSuccess { state = it }; api.botStatus(session).onSuccess { bot = it }; delay(3000) } }
    LazyColumn(modifier.fillMaxSize().background(Ink).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp)) {
        item { TopBar("Pips-life", "TRADING COMMAND CENTER", state != null) }
        item { HeroCard(state, openMt5) }
        item { SectionTitle("ACCOUNT", "LIVE") }
        item { if (state == null) EmptyCard("Connect MT5 to unlock live balance, equity and position intelligence.", "CONNECT MT5", openMt5) else AccountCard(state!!) }
        item { SectionTitle("STRATEGY ENGINE", "STRATEGY 001") }
        item { EngineCard(bot, running, session != null, busy) { action -> busy = true; scope.launch { api.botCommand(session!!, action); busy = false } } }
        item { SectionTitle("ACTIVE POSITIONS", "${state?.positions?.size ?: 0} open") }
        if (state?.positions?.isNotEmpty() == true) items(state!!.positions) { PositionCard(it) } else item { EmptyCard("No live MT5 positions right now.") }
    }
}

@Composable private fun HeroCard(state: LiveState?, openMt5: () -> Unit) = Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.background(Brush.linearGradient(listOf(Color(0xFF17264A), Color(0xFF10152C), Color(0xFF20133D))), RoundedCornerShape(26.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("LIVE PICTURE", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp); Box(Modifier.size(9.dp).background(if (state != null) Green else Amber, CircleShape)) }
        Text(if (state == null) "Ready when you are." else "Your account is live.", color = Primary, fontSize = 25.sp, fontWeight = FontWeight.Black)
        Text(if (state == null) "Pips-life is ready to turn market intelligence into a calm trading workflow." else "${state.connectionStatus} • ${state.server}", color = Muted, fontSize = 12.sp)
        if (state == null) Button(onClick = openMt5, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)) { Text("CONNECT MT5", fontWeight = FontWeight.Black) }
    }
}

@Composable private fun MarketsScreen(modifier: Modifier, session: BackendSession?) {
    val symbols = listOf("NAS100" to "Watching", "XAUUSD" to "Watching", "EURUSD" to "Watching", "US30" to "Watching")
    LazyColumn(modifier.fillMaxSize().background(Ink).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp)) {
        item { TopBar("Markets", "MARKET INTELLIGENCE", session != null) }; item { SearchCard() }; item { SectionTitle("WATCHLIST", "${symbols.size} instruments") }; items(symbols) { (symbol, status) -> MarketCard(symbol, status) }; item { EmptyCard("Market intelligence cards are ready for live evidence from the existing backend. No strategy logic is changed here.") }
    }
}

@Composable private fun SearchCard() = OutlinedTextField("", {}, Modifier.fillMaxWidth(), placeholder = { Text("Search markets…") }, leadingIcon = { Icon(Icons.Default.Search, null, tint = Cyan) }, singleLine = true, shape = RoundedCornerShape(16.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Panel, focusedContainerColor = Panel, unfocusedBorderColor = Line, focusedBorderColor = Cyan))

@Composable private fun MarketCard(symbol: String, status: String) = Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(46.dp).background(Brush.linearGradient(listOf(Purple, Blue)), CircleShape), contentAlignment = Alignment.Center) { Text(symbol.take(2), color = Primary, fontWeight = FontWeight.Black) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(symbol, color = Primary, fontSize = 16.sp, fontWeight = FontWeight.Bold); Text("Live evidence • $status", color = Muted, fontSize = 11.sp) }; Icon(Icons.Default.ChevronRight, null, tint = Cyan) }
}

@Composable private fun StrategiesScreen(modifier: Modifier, api: BackendApiClient, session: BackendSession?) {
    var bot by remember { mutableStateOf<BotState?>(null) }; LaunchedEffect(session) { if (session != null) while (true) { api.botStatus(session).onSuccess { bot = it }; delay(3000) } }
    LazyColumn(modifier.fillMaxSize().background(Ink).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp)) { item { TopBar("Strategies", "EVIDENCE → CONFLUENCE → EXECUTION", session != null) }; item { StrategyDetail("001", "QOF", bot?.state ?: "READY", true, "Primary strategy. Engine remains unchanged.") }; item { StrategyDetail("002", "Reserved", "LOCKED", false, "Future strategy slot.") }; item { StrategyDetail("003", "Reserved", "LOCKED", false, "Future strategy slot.") } }
}

@Composable private fun StrategyDetail(number: String, name: String, state: String, active: Boolean, desc: String) = Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
    Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("STRATEGY $number", color = if (active) Cyan else Purple, fontWeight = FontWeight.Bold, letterSpacing = 1.sp); Pill(state.replace('_', ' ')) }; Text(name, color = Primary, fontSize = 23.sp, fontWeight = FontWeight.Black); Text(desc, color = Muted, fontSize = 12.sp); if (active) { Divider(color = Line); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("ENGINE", "PROTECTED"); Metric("STATE", state); Metric("ROLE", "PRIMARY") } } }
}

@Composable private fun Mt5Screen(modifier: Modifier, api: BackendApiClient, existing: BackendSession?, onConnected: (BackendSession) -> Unit) {
    val scope = rememberCoroutineScope(); var broker by remember { mutableStateOf("") }; var login by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var server by remember { mutableStateOf(existing?.server.orEmpty()) }; var servers by remember { mutableStateOf(emptyList<Mt5Server>()) }; var busy by remember { mutableStateOf(false) }; var status by remember { mutableStateOf(if (existing != null) "Account linked and ready." else "Find your broker to begin.") }
    LazyColumn(modifier.fillMaxSize().background(Ink).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp)) {
        item { TopBar("MT5", "SECURE ACCOUNT CONNECTION", existing != null) }; if (existing != null) item { AccountCardMini(existing) }; item { Field(broker, { broker = it }, "Broker or broker alias") }
        item { Button(enabled = broker.length >= 2 && !busy, onClick = { busy = true; scope.launch { api.findServers(broker).onSuccess { servers = it; status = "Select the exact server used by your MT5 account." }.onFailure { status = it.message ?: "Broker search failed" }; busy = false } }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Ink)) { Text(if (busy) "SEARCHING…" else "FIND SERVERS", fontWeight = FontWeight.Black) } }
        items(servers) { s -> Card(onClick = { server = s.serverName }, colors = CardDefaults.cardColors(containerColor = if (server == s.serverName) Raised else Panel), shape = RoundedCornerShape(17.dp), modifier = Modifier.border(1.dp, if (server == s.serverName) Cyan else Line, RoundedCornerShape(17.dp))) { Column(Modifier.padding(15.dp)) { Text(s.brokerName, color = Muted, fontSize = 10.sp); Text(s.serverName, color = Cyan, fontWeight = FontWeight.Bold); Text(s.environment.uppercase(), color = Muted, fontSize = 9.sp) } } }
        item { Field(server, { server = it }, "MT5 server") }; item { Field(login, { login = it }, "Account number") }; item { OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("MT5 password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(15.dp)) }
        item { Button(enabled = login.isNotBlank() && password.isNotBlank() && server.isNotBlank() && !busy, onClick = { busy = true; status = "Connecting securely through backend…"; scope.launch { api.connect(login, password, server, broker).onSuccess { s -> password = ""; onConnected(s); status = "Connected. Live account sync is active." }.onFailure { status = it.message ?: "MT5 connection failed" }; busy = false } }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Ink)) { Text(if (busy) "CONNECTING…" else "CONNECT SECURELY", fontWeight = FontWeight.Black) } }
        item { Text(status, color = if (status.contains("Connected", true)) Green else Muted, fontSize = 12.sp) }
    }
}

@Composable private fun ActivityScreen(modifier: Modifier, api: BackendApiClient, session: BackendSession?) {
    var state by remember { mutableStateOf<LiveState?>(null) }; LaunchedEffect(session) { if (session != null) while (true) { api.liveState(session).onSuccess { state = it }; delay(5000) } }
    LazyColumn(modifier.fillMaxSize().background(Ink).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp)) { item { TopBar("Activity", "ACCOUNT & POSITION TIMELINE", session != null) }; item { if (state != null) AccountCard(state!!) else EmptyCard("Connect MT5 to populate live account activity.") }; if (state?.positions?.isNotEmpty() == true) items(state!!.positions) { PositionCard(it) } else item { EmptyCard("No live positions to show.") } }
}

@Composable private fun AccountCardMini(s: BackendSession) = Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) { Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(44.dp).background(Brush.linearGradient(listOf(Green, Cyan)), CircleShape)); Spacer(Modifier.width(12.dp)); Column { Text("MT5 ACCOUNT CONNECTED", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Black); Text(s.server, color = Primary, fontWeight = FontWeight.Bold) } } }
@Composable private fun AccountCard(s: LiveState) = Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(21.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("MT5 ${s.login}", color = Primary, fontWeight = FontWeight.Bold); Text(s.server, color = Muted, fontSize = 10.sp) }; Pill(s.connectionStatus) }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("BALANCE", money(s.balance, s.currency)); Metric("EQUITY", money(s.equity, s.currency)); Metric("FREE MARGIN", money(s.freeMargin, s.currency)) } } }
@Composable private fun EngineCard(bot: BotState?, running: Boolean, enabled: Boolean, busy: Boolean, command: (String) -> Unit) = Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(21.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("QOF", color = Primary, fontSize = 20.sp, fontWeight = FontWeight.Black); Text("Strategy 001", color = Muted, fontSize = 11.sp) }; Text(bot?.state?.replace('_', ' ') ?: "READY", color = if (running) Green else Amber, fontWeight = FontWeight.Bold) }; Text(bot?.activity ?: "Awaiting backend account connection.", color = Muted, fontSize = 12.sp); Button(enabled = enabled && !busy, onClick = { command(if (running) "stop" else "start") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = if (running) Red else Green, contentColor = Ink)) { Text(if (busy) "WORKING…" else if (running) "STOP ENGINE" else "START ENGINE", fontWeight = FontWeight.Black) } } }
@Composable private fun PositionCard(p: LivePosition) = Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(19.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text(p.symbol, color = Primary, fontSize = 17.sp, fontWeight = FontWeight.Black); Text("${p.side} • ${p.volume} lot", color = Cyan, fontSize = 11.sp) }; Text(money(p.profit, ""), color = if (p.profit >= 0) Green else Red, fontWeight = FontWeight.Black) }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("ENTRY", num(p.entry)); Metric("CURRENT", num(p.current)); Metric("SL", num(p.stopLoss)); Metric("TP", num(p.takeProfit)) } } }
@Composable private fun EmptyCard(text: String, action: String? = null, onAction: (() -> Unit)? = null) = Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(19.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(text, color = Muted, fontSize = 12.sp); if (action != null && onAction != null) OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(action, fontWeight = FontWeight.Bold) } } }
@Composable private fun SectionTitle(a: String, b: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(a, color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp); Text(b, color = Muted, fontSize = 10.sp) }
@Composable private fun Field(v: String, set: (String) -> Unit, label: String) = OutlinedTextField(v, set, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(15.dp))
@Composable private fun Metric(a: String, b: String) = Column { Text(a, color = Muted, fontSize = 9.sp); Text(b, color = Primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
@Composable private fun Pill(t: String) = Row(Modifier.background(Panel, RoundedCornerShape(50)).border(1.dp, if (t.contains("LIVE", true) || t.contains("CONNECTED", true) || t.contains("RUN", true)) Green else Amber, RoundedCornerShape(50)), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.padding(start = 9.dp).size(7.dp).background(if (t.contains("LIVE", true) || t.contains("CONNECTED", true) || t.contains("RUN", true)) Green else Amber, CircleShape)); Text(t.take(18), color = Primary, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)) }
private fun num(v: Double?) = if (v == null || v.isNaN()) "—" else String.format(Locale.US, "%.5f", v)
private fun money(v: Double, c: String) = if (v.isNaN()) "—" else String.format(Locale.US, "%s%.2f", if (c.isBlank()) "" else "$c ", v)

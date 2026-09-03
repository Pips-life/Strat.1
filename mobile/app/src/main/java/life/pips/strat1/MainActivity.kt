package life.pips.strat1

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import java.time.LocalTime
import java.util.Locale

private val Context.pipsDataStore by preferencesDataStore("pips_life_session")
private val ACCOUNT_ID = stringPreferencesKey("account_id")
private val SESSION_TOKEN = stringPreferencesKey("session_token")
private val SERVER = stringPreferencesKey("server")
private val SELECTED_STRATEGY = stringPreferencesKey("selected_strategy")

private val Ink = Color(0xFF040712)
private val Panel = Color(0xFF0B1220)
private val Panel2 = Color(0xFF101A2D)
private val Line = Color(0xFF24334D)
private val Primary = Color(0xFFF6FAFF)
private val Muted = Color(0xFF8EA2BB)
private val Green = Color(0xFF39F28A)
private val Red = Color(0xFFFF5570)
private val Cyan = Color(0xFF25D9FF)
private val Purple = Color(0xFFB66CFF)
private val Blue = Color(0xFF5D8CFF)
private val Amber = Color(0xFFFFC857)
private val Scheme = darkColorScheme(primary = Cyan, secondary = Green, tertiary = Purple, background = Ink, surface = Panel, surfaceVariant = Panel2, onBackground = Primary, onSurface = Primary, outline = Line, error = Red)

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9001)
        setContent { PipsLifeApp(applicationContext) }
    }
}

private enum class Screen(val label: String) { HOME("Home"), MARKETS("Markets"), STRATEGIES("Strategies"), MT5("MT5"), ACTIVITY("Activity") }

private fun strategyLabel(strategy: String?): String {
    return when (strategy?.trim()?.uppercase(Locale.US)?.replace('-', '_')?.replace(' ', '_')) {
        "002", "STRATEGY_002", "VELOCITY_EXPANSION" -> "002 · Velocity Expansion"
        "003", "STRATEGY_003", "RESERVED" -> "003 · Reserved"
        else -> "001 · QOF"
    }
}

private fun normalizeStrategy(strategy: String?): String = if (strategy?.trim() == "002" || strategy.equals("STRATEGY_002", true)) "002" else "001"

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
            NavigationBar(containerColor = Color(0xFF07101D), tonalElevation = 0.dp) {
                listOf(Screen.HOME to Icons.Default.Home, Screen.MARKETS to Icons.Default.ShowChart, Screen.STRATEGIES to Icons.Default.Tune, Screen.MT5 to Icons.Default.AccountBalance, Screen.ACTIVITY to Icons.Default.History).forEach { (s, icon) ->
                    NavigationBarItem(selected = screen == s, onClick = { screen = s }, icon = { Icon(icon, s.label) }, label = { Text(s.label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Ink, selectedTextColor = Cyan, unselectedIconColor = Muted, unselectedTextColor = Muted, indicatorColor = Cyan))
                }
            }
        }) { pad ->
            when (screen) {
                Screen.HOME -> HomeScreen(Modifier.padding(pad), api, session) { screen = Screen.MT5 }
                Screen.MARKETS -> MarketsScreen(Modifier.padding(pad), session)
                Screen.STRATEGIES -> StrategiesScreen(Modifier.padding(pad), api, session)
                Screen.MT5 -> Mt5Screen(Modifier.padding(pad), api, session) { s ->
                    session = s
                    scope.launch { context.pipsDataStore.edit { it[ACCOUNT_ID] = s.accountId; it[SESSION_TOKEN] = s.token; it[SERVER] = s.server } }
                }
                Screen.ACTIVITY -> ActivityScreen(Modifier.padding(pad), api, session)
            }
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier, api: BackendApiClient, session: BackendSession?, openMt5: () -> Unit) {
    var state by remember { mutableStateOf<LiveState?>(null) }
    var bot by remember { mutableStateOf<BotState?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val running = bot?.state?.uppercase(Locale.US)?.contains("RUN") == true
    LaunchedEffect(session) {
        if (session == null) { state = null; bot = null }
        else while (true) {
            api.liveState(session).onSuccess { state = it }
            api.botStatus(session).onSuccess { bot = it }
            delay(3000)
        }
    }
    LazyColumn(modifier.fillMaxSize().background(Ink).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp)) {
        item { HomeHeader(state != null, state?.userName) }
        item { AccountHero(state, openMt5) }
        item { EngineActivityCard(bot, running, session != null, busy) { action -> busy = true; scope.launch { api.botCommand(session!!, action).onSuccess { bot = it }; busy = false } } }
        item { SectionTitle("MARKET WATCHLIST", "LIVE") }
        item { WatchlistStrip() }
        item { SectionTitle("ACTIVE POSITIONS", "${state?.positions?.size ?: 0} OPEN") }
        if (state?.positions?.isNotEmpty() == true) items(state!!.positions) { PositionCard(it) } else item { EmptyCard("No live positions right now.") }
        item { EngineHealthCard(session != null, bot) }
        item { AppUpdateCard() }
    }
}

@Composable private fun HomeHeader(connected: Boolean, userName: String?) {
    val hour = LocalTime.now().hour
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
    val name = userName?.trim()?.takeIf { it.isNotBlank() }?.substringBefore(" MT5")?.ifBlank { "Pips+" } ?: "Pips+"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Pips-life", color = Blue, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
            Text("$greeting, $name 👋", color = Primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(if (connected) "Your engine is running with a live account." else "Your command center is ready.", color = Muted, fontSize = 11.sp)
        }
        StatusDot(if (connected) Green else Amber)
    }
}

@Composable private fun AccountHero(state: LiveState?, openMt5: () -> Unit) = Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.background(Brush.linearGradient(listOf(Color(0xFF122B4C), Color(0xFF0C1730), Color(0xFF241342))), RoundedCornerShape(26.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("ACCOUNT EQUITY", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp); Text(if (state == null) "—" else money(state.equity, state.currency), color = Primary, fontSize = 31.sp, fontWeight = FontWeight.Black) }
            Column(horizontalAlignment = Alignment.End) { Text("CONNECTION", color = Muted, fontSize = 9.sp); Text(if (state == null) "OFFLINE" else "LIVE", color = if (state == null) Amber else Green, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        }
        if (state == null) {
            Text("Connect MT5 to unlock live balance, equity and position intelligence.", color = Muted, fontSize = 12.sp)
            Button(onClick = openMt5, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)) { Text("CONNECT MT5", fontWeight = FontWeight.Black) }
        } else {
            MiniWave(Modifier.fillMaxWidth().height(48.dp), Cyan)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("BALANCE", money(state.balance, state.currency)); Metric("FREE MARGIN", money(state.freeMargin, state.currency)); Metric("SERVER", state.server.ifBlank { "—" }) }
        }
    }
}

@Composable private fun EngineActivityCard(bot: BotState?, running: Boolean, enabled: Boolean, busy: Boolean, command: (String) -> Unit) {
    val activity = bot?.activity?.ifBlank { null }
    val state = bot?.state?.replace('_', ' ')?.ifBlank { "READY" } ?: "READY"
    val selectedStrategy = strategyLabel(bot?.strategy)
    val tone = when { running -> Green; state.contains("BLOCK", true) -> Red; state.contains("ANAL", true) -> Purple; state.contains("WAIT", true) -> Amber; else -> Cyan }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().border(1.dp, tone.copy(alpha = 0.65f), RoundedCornerShape(24.dp))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("STRATEGY ENGINE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp); Text("Strategy $selectedStrategy", color = Primary, fontSize = 19.sp, fontWeight = FontWeight.Black) }
                StatusPill(if (enabled) if (running) "LIVE" else state else "OFFLINE", tone)
            }
            BotPulse(tone, running || enabled)
            Text(if (activity.isNullOrBlank()) "Awaiting backend activity." else activity, color = Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(if (enabled) "Monitoring through the selected strategy engine." else "Connect an MT5 account to activate the engine view.", color = Muted, fontSize = 11.sp)
            EvidenceRow(running, tone)
            Button(enabled = enabled && !busy, onClick = { command(if (running) "stop" else "start") }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = if (running) Red else Green, contentColor = Ink)) { Text(if (busy) "WORKING…" else if (running) "STOP ENGINE" else "START ENGINE", fontWeight = FontWeight.Black) }
        }
    }
}

@Composable private fun BotPulse(tone: Color, active: Boolean) {
    val transition = rememberInfiniteTransition(label = "botPulse")
    val scale by transition.animateFloat(if (active) 0.88f else 1f, if (active) 1.12f else 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val alpha by transition.animateFloat(if (active) 0.35f else 0.15f, if (active) 0.95f else 0.25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow")
    Box(Modifier.fillMaxWidth().height(88.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(62.dp).scale(scale).alpha(alpha).background(tone.copy(alpha = 0.22f), CircleShape).border(1.dp, tone, CircleShape)); Box(Modifier.size(24.dp).background(tone, CircleShape)); MiniWave(Modifier.fillMaxWidth().height(72.dp), tone.copy(alpha = 0.9f)) }
}

@Composable private fun EvidenceRow(running: Boolean, tone: Color) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Evidence("Structure", true, tone); Evidence("Momentum", running, Green); Evidence("Liquidity", false, Cyan); Evidence("Options", false, Purple); Evidence("Risk", true, Green) }
@Composable private fun Evidence(label: String, ready: Boolean, color: Color) = Column(horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(8.dp).background(if (ready) color else Muted.copy(alpha = 0.35f), CircleShape)); Spacer(Modifier.height(4.dp)); Text(label, color = Muted, fontSize = 8.sp) }
@Composable private fun MiniWave(modifier: Modifier, color: Color) { Canvas(modifier) { val p = Path(); val w = size.width; val h = size.height; p.moveTo(0f, h * 0.62f); for (i in 1..48) { val x = w * i / 48f; val y = h * (0.5f + 0.18f * kotlin.math.sin(i * 0.65f)); p.lineTo(x, y) }; drawPath(p, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)) } }

@Composable private fun MarketsScreen(modifier: Modifier, session: BackendSession?) {
    val symbols = listOf("NAS100", "XAUUSD", "EURUSD", "US30", "GBPUSD")
    LazyColumn(modifier.fillMaxSize().background(Ink).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp)) { item { TopBar("Markets", "MARKET INTELLIGENCE", session != null) }; item { SearchCard() }; item { SectionTitle("WATCHLIST", "${symbols.size} INSTRUMENTS") }; items(symbols) { MarketCard(it) }; item { InfoCard("Live market feeds can be connected here without changing the strategy engine.", Cyan) } }
}
@Composable private fun MarketCard(symbol: String) = Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(19.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(46.dp).background(Brush.linearGradient(listOf(Purple, Blue)), CircleShape), contentAlignment = Alignment.Center) { Text(symbol.take(2), color = Primary, fontWeight = FontWeight.Black) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(symbol, color = Primary, fontSize = 16.sp, fontWeight = FontWeight.Bold); Text("Monitoring • live evidence ready", color = Muted, fontSize = 10.sp) }; Text("WATCH", color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold) } }

@Composable private fun StrategiesScreen(modifier: Modifier, api: BackendApiClient, session: BackendSession?) {
    var bot by remember { mutableStateOf<BotState?>(null) }
    var selected by remember { mutableStateOf("001") }
    var pendingSelection by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Choose the strategy the bot should trade.") }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(session) {
        if (session == null) {
            bot = null
            selected = "001"
            pendingSelection = null
            message = "Connect MT5 before selecting a live trading strategy."
        } else {
            val saved = context.pipsDataStore.data.first()[SELECTED_STRATEGY]
            if (saved == "001" || saved == "002") selected = saved
            while (true) {
                api.botStatus(session).onSuccess {
                    bot = it
                    val serverStrategy = normalizeStrategy(it.strategy)
                    if (pendingSelection == null) {
                        selected = serverStrategy
                        context.pipsDataStore.edit { prefs -> prefs[SELECTED_STRATEGY] = serverStrategy }
                    } else if (serverStrategy == pendingSelection) {
                        selected = serverStrategy
                        pendingSelection = null
                        message = "Strategy $serverStrategy active in engine."
                        context.pipsDataStore.edit { prefs -> prefs[SELECTED_STRATEGY] = serverStrategy }
                    }
                }.onFailure { if (pendingSelection != null) message = "Waiting for engine confirmation…" }
                delay(1500)
            }
        }
    }

    fun choose(strategy: String) {
        if (session == null) {
            message = "Connect MT5 before selecting a live trading strategy."
            return
        }
        selected = strategy
        pendingSelection = strategy
        message = "Activating Strategy $strategy…"
        busy = true
        scope.launch {
            context.pipsDataStore.edit { prefs -> prefs[SELECTED_STRATEGY] = strategy }
            api.botCommand(session, "select", strategy)
                .onSuccess { selection ->
                    bot = selection
                    if (normalizeStrategy(selection.strategy) == strategy) {
                        message = "Strategy $strategy selected in BotEngine. Starting trading…"
                        api.botCommand(session, "start", strategy)
                            .onSuccess { started ->
                                bot = started
                                if (normalizeStrategy(started.strategy) == strategy) {
                                    pendingSelection = null
                                    message = "Strategy $strategy active and trading."
                                } else {
                                    message = "Strategy $strategy selected; waiting for engine confirmation."
                                }
                            }
                            .onFailure { message = it.message ?: "Strategy selected, but trading could not be started." }
                    } else {
                        message = "Strategy $strategy requested; waiting for engine confirmation."
                    }
                }
                .onFailure { message = it.message ?: "Strategy selection failed; the engine will retry through status sync." }
            busy = false
        }
    }

    LazyColumn(modifier.fillMaxSize().background(Ink).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp)) {
        item { TopBar("Strategies", "CHOOSE THE ACTIVE TRADING STRATEGY", session != null) }
        item {
            Text("SELECTED STRATEGY", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(5.dp))
            Text(strategyLabel(selected), color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text(message, color = if (message.contains("active", true) || message.contains("trading", true)) Green else Muted, fontSize = 11.sp)
        }
        item { StrategyDetail("001", "QOF", if (selected == "001") bot?.state ?: "READY" else "READY", true, "Primary QOF strategy. Uses the reusable confluence engine and QOF decision path.", "PRIMARY", selected == "001", busy) { choose("001") } }
        item { StrategyDetail("002", "Velocity Expansion", if (selected == "002") bot?.state ?: "READY" else "READY", true, "Detects expanding price velocity, enters immediately in the detected direction, then maintains a 100-pip trailing opposite stop and reverses continuously when triggered.", "SECONDARY", selected == "002", busy) { choose("002") } }
        item { StrategyDetail("003", "Reserved", "LOCKED", false, "Future strategy slot.", "RESERVED", false, busy) {} }
    }
}

@Composable private fun StrategyDetail(number: String, name: String, state: String, active: Boolean, desc: String, role: String, selected: Boolean, busy: Boolean, onSelect: () -> Unit) = Card(colors = CardDefaults.cardColors(containerColor = if (selected) Panel2 else Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().border(2.dp, if (selected) Cyan else if (active) Cyan.copy(alpha = .55f) else Line, RoundedCornerShape(22.dp))) {
    Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("STRATEGY $number", color = if (selected) Cyan else if (active) Cyan.copy(alpha = .75f) else Purple, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            StatusPill(if (selected) "SELECTED" else if (active && state == "READY") "READY" else state.replace('_', ' '), if (selected) Green else if (active) Green else Purple)
        }
        Text(name, color = Primary, fontSize = 23.sp, fontWeight = FontWeight.Black)
        Text(desc, color = Muted, fontSize = 12.sp)
        Divider(color = Line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Metric("ENGINE", if (selected) "ACTIVE" else "AVAILABLE")
            Metric("STATE", state)
            Metric("ROLE", role)
        }
        if (active) {
            Button(enabled = !busy && !selected && number != "003", onClick = onSelect, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = if (selected) Panel2 else Cyan, contentColor = Ink)) {
                Text(if (busy) "SELECTING…" else if (selected) "CURRENT STRATEGY" else "SELECT STRATEGY $number", fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable private fun Mt5Screen(modifier: Modifier, api: BackendApiClient, existing: BackendSession?, onConnected: (BackendSession) -> Unit) {
    val scope = rememberCoroutineScope(); var brokerQuery by remember { mutableStateOf("") }; var login by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var server by remember { mutableStateOf(existing?.server.orEmpty()) }; var servers by remember { mutableStateOf(emptyList<Mt5Server>()) }; var busy by remember { mutableStateOf(false) }; var status by remember { mutableStateOf(if (existing != null) "Account linked and ready." else "Search your broker to choose its MT5 server.") }; var searchStarted by remember { mutableStateOf(false) }
    LaunchedEffect(brokerQuery) { val query = brokerQuery.trim(); if (query.length < 2) { servers = emptyList(); searchStarted = false; return@LaunchedEffect }; delay(350); if (query != brokerQuery.trim()) return@LaunchedEffect; busy = true; searchStarted = true; api.findServers(query).onSuccess { servers = it; status = if (it.isEmpty()) "No matching MT5 servers found." else "Select the exact server used by your MT5 account." }.onFailure { servers = emptyList(); status = it.message ?: "Broker search failed" }; busy = false }
    LazyColumn(modifier.fillMaxSize().background(Ink).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp)) {
        item { TopBar("MT5", "SECURE ACCOUNT CONNECTION", existing != null) }
        if (existing != null) item { AccountCardMini(existing) }
        item { Text("MT5 server", color = Primary, fontSize = 20.sp, fontWeight = FontWeight.Black) }
        item { OutlinedTextField(value = brokerQuery, onValueChange = { value -> brokerQuery = value; if (value.isBlank()) { server = ""; servers = emptyList() } }, modifier = Modifier.fillMaxWidth(), label = { Text("MT5 server") }, placeholder = { Text("Search broker name…") }, leadingIcon = { Icon(Icons.Default.Search, null, tint = Cyan) }, trailingIcon = { if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Cyan) }, singleLine = true, shape = RoundedCornerShape(15.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Panel, focusedContainerColor = Panel, unfocusedBorderColor = Line, focusedBorderColor = Cyan)) }
        if (server.isNotBlank()) item { Text("SELECTED SERVER", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp); Text(server, color = Green, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        if (servers.isNotEmpty()) items(servers) { s -> Card(onClick = { server = s.serverName; brokerQuery = s.brokerName }, colors = CardDefaults.cardColors(containerColor = if (server == s.serverName) Panel2 else Panel), shape = RoundedCornerShape(17.dp), modifier = Modifier.border(1.dp, if (server == s.serverName) Cyan else Line, RoundedCornerShape(17.dp))) { Column(Modifier.padding(15.dp)) { Text(s.brokerName, color = Muted, fontSize = 10.sp); Text(s.serverName, color = Cyan, fontWeight = FontWeight.Bold); Text(s.environment.uppercase(), color = Muted, fontSize = 9.sp) } } } else if (searchStarted && !busy && brokerQuery.trim().length >= 2) item { Text(status, color = Muted, fontSize = 11.sp) }
        item { Field(login, { login = it }, "Account number") }
        item { OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("MT5 password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(15.dp)) }
        item { Button(enabled = login.isNotBlank() && password.isNotBlank() && server.isNotBlank() && !busy, onClick = { busy = true; status = "Connecting securely through backend…"; scope.launch { api.connect(login, password, server, brokerQuery.trim()).onSuccess { s -> password = ""; onConnected(s); status = "Connected. Live account sync is active." }.onFailure { status = it.message ?: "MT5 connection failed" }; busy = false } }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Ink)) { Text(if (busy) "CONNECTING…" else "CONNECT SECURELY", fontWeight = FontWeight.Black) } }
        item { Text(status, color = if (status.contains("Connected", true)) Green else Muted, fontSize = 12.sp) }
    }
}

@Composable private fun ActivityScreen(modifier: Modifier, api: BackendApiClient, session: BackendSession?) {
    var state by remember { mutableStateOf<LiveState?>(null) }; var bot by remember { mutableStateOf<BotState?>(null) }
    LaunchedEffect(session) { if (session != null) while (true) { api.liveState(session).onSuccess { state = it }; api.botStatus(session).onSuccess { bot = it }; delay(3000) } }
    LazyColumn(modifier.fillMaxSize().background(Ink).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp)) { item { TopBar("Activity", "ACCOUNT & BOT TIMELINE", session != null) }; item { BotTimelineHeader(bot) }; item { if (state != null) AccountCard(state!!) else EmptyCard("Connect MT5 to populate live account activity.") }; if (state?.positions?.isNotEmpty() == true) items(state!!.positions) { PositionCard(it) } else item { EmptyCard("No live positions to show.") } }
}
@Composable private fun BotTimelineHeader(bot: BotState?) = Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("BOT ACTIVITY", color = Primary, fontSize = 16.sp, fontWeight = FontWeight.Black); StatusPill(if (bot == null) "OFFLINE" else "LIVE", if (bot == null) Amber else Green) }; ActivityRow("NOW", bot?.activity?.ifBlank { "Waiting for engine activity." } ?: "Waiting for backend activity.", Cyan, true); ActivityRow("STATE", bot?.state?.replace('_', ' ') ?: "NOT CONNECTED", Purple, false); ActivityRow("STRATEGY", strategyLabel(bot?.strategy), Green, false) } }
@Composable private fun ActivityRow(time: String, text: String, color: Color, active: Boolean) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(10.dp).background(color, CircleShape)); Box(Modifier.width(2.dp).height(28.dp).background(color.copy(alpha = .35f))) }; Spacer(Modifier.width(14.dp)); Column { Text(time, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold); Text(text, color = Primary, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal) } }
@Composable private fun AccountCardMini(s: BackendSession) = Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(42.dp).background(Green, CircleShape)); Spacer(Modifier.width(12.dp)); Column { Text("MT5 ACCOUNT CONNECTED", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(s.server, color = Primary, fontWeight = FontWeight.Bold) } } }
@Composable private fun AccountCard(s: LiveState) = Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("MT5 ${s.login}", color = Primary, fontWeight = FontWeight.Bold); Text(s.server, color = Muted, fontSize = 10.sp) }; StatusPill(s.connectionStatus.ifBlank { "CONNECTED" }, Green) }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("BALANCE", money(s.balance, s.currency)); Metric("EQUITY", money(s.equity, s.currency)); Metric("FREE MARGIN", money(s.freeMargin, s.currency)) } } }
@Composable private fun PositionCard(p: LivePosition) = Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(19.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text(p.symbol, color = Primary, fontSize = 17.sp, fontWeight = FontWeight.Black); Text("${p.side} • ${p.volume} lot", color = Cyan, fontSize = 11.sp) }; Text(money(p.profit, ""), color = if (p.profit >= 0) Green else Red, fontWeight = FontWeight.Black) }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("ENTRY", num(p.entry)); Metric("CURRENT", num(p.current)); Metric("SL", num(p.stopLoss)); Metric("TP", num(p.takeProfit)) } } }
@Composable private fun WatchlistStrip() = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("NAS100", "XAUUSD", "EURUSD").forEach { s -> Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(15.dp), modifier = Modifier.weight(1f)) { Column(Modifier.padding(10.dp)) { Text(s, color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold); Text("—", color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Black); Text("WATCH", color = Muted, fontSize = 8.sp) } } } }
@Composable private fun EngineHealthCard(connected: Boolean, bot: BotState?) = Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("ENGINE STATUS", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold); Text(if (connected) (bot?.state?.replace('_', ' ') ?: "READY") else "WAITING FOR MT5", color = if (connected) Green else Amber, fontWeight = FontWeight.Bold) }; Metric("STRATEGY", strategyLabel(bot?.strategy)); StatusPill(if (connected) "LIVE DATA" else "OFFLINE", if (connected) Green else Amber) } }
@Composable private fun SearchCard() = OutlinedTextField("", {}, Modifier.fillMaxWidth(), placeholder = { Text("Search markets…") }, leadingIcon = { Icon(Icons.Default.Search, null, tint = Cyan) }, singleLine = true, shape = RoundedCornerShape(16.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Panel, focusedContainerColor = Panel, unfocusedBorderColor = Line, focusedBorderColor = Cyan))
@Composable private fun TopBar(title: String, subtitle: String, connected: Boolean) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text(title, color = Primary, fontSize = 26.sp, fontWeight = FontWeight.Black); Text(subtitle, color = Muted, fontSize = 10.sp, letterSpacing = 1.2.sp) }; StatusPill(if (connected) "MT5 LIVE" else "OFFLINE", if (connected) Green else Amber) }
@Composable private fun SectionTitle(a: String, b: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(a, color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp); Text(b, color = Muted, fontSize = 9.sp) }
@Composable private fun Field(v: String, set: (String) -> Unit, label: String) = OutlinedTextField(v, set, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(15.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Panel, focusedContainerColor = Panel, unfocusedBorderColor = Line, focusedBorderColor = Cyan))
@Composable private fun Metric(a: String, b: String) = Column { Text(a, color = Muted, fontSize = 8.sp); Text(b, color = Primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
@Composable private fun StatusPill(text: String, color: Color) = Row(Modifier.background(color.copy(alpha = .10f), RoundedCornerShape(50)).border(1.dp, color.copy(alpha = .45f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(6.dp).background(color, CircleShape)); Spacer(Modifier.width(5.dp)); Text(text.take(16), color = Primary, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
@Composable private fun StatusDot(color: Color) = Box(Modifier.size(13.dp).background(color.copy(alpha = .20f), CircleShape).border(1.dp, color, CircleShape), contentAlignment = Alignment.Center) { Box(Modifier.size(6.dp).background(color, CircleShape)) }
@Composable private fun EmptyCard(text: String) = InfoCard(text, Muted)
@Composable private fun InfoCard(text: String, accent: Color) = Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().border(1.dp, accent.copy(alpha = .25f), RoundedCornerShape(18.dp))) { Text(text, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(17.dp)) }
private fun num(v: Double?) = if (v == null || v.isNaN()) "—" else String.format(Locale.US, "%.5f", v)
private fun money(v: Double, c: String) = if (v.isNaN()) "—" else String.format(Locale.US, "%s%.2f", if (c.isBlank()) "" else "$c ", v)

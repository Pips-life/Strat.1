package life.pips.strat1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.pips.strat1.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private val Bg = Color(0xFF050A12)
private val Panel = Color(0xFF0C1422)
private val Line = Color(0xFF223149)
private val TextMain = Color(0xFFF4F7FB)
private val Muted = Color(0xFF8EA2BB)
private val Cyan = Color(0xFF27D8FF)
private val Green = Color(0xFF43F28E)
private val Red = Color(0xFFFF5872)
private val Purple = Color(0xFFB36BFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { PipsLifeApp() } }
}

private enum class Tab { HOME, METAAPI, STRATEGY, ACTIVITY }

private class Strategy001Engine {
    fun evaluate(f: FlashAlphaSnapshot?): String {
        if (f == null) return "WAIT — FlashAlpha data unavailable"
        val flow = f.flowDirection.uppercase(Locale.US)
        val direction = when { flow.contains("LONG") || flow.contains("CALL") -> "LONG"; flow.contains("SHORT") || flow.contains("PUT") -> "SHORT"; else -> "NEUTRAL" }
        val gammaBoost = if (!f.netGex.isNaN() && f.netGex < 0) 10 else 0
        val score = (50 + if (direction != "NEUTRAL") 15 else 0 + gammaBoost + if (f.regime.equals("NEGATIVE_GAMMA", true)) 10 else 0).coerceAtMost(100)
        return if (direction == "NEUTRAL") "WAIT — no directional QOF edge" else "$direction — QOF confluence $score/100"
    }
}

@Composable
private fun PipsLifeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val meta = remember { DirectMetaApiClient() }
    val flash = remember { FlashAlphaClient() }
    val engine = remember { Strategy001Engine() }
    var tab by remember { mutableStateOf(Tab.HOME) }
    var saved by remember { mutableStateOf(SavedConnection("", "", "", "", "", "")) }
    var account by remember { mutableStateOf<MetaAccount?>(null) }
    var snapshot by remember { mutableStateOf<MetaSnapshot?>(null) }
    var flashData by remember { mutableStateOf<FlashAlphaSnapshot?>(null) }
    var strategyRunning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready — direct connections only") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        saved = context.loadSavedConnection()
        if (saved.accountId.isNotBlank() && saved.metaApiToken.isNotBlank()) account = runCatching { meta.connectExisting(saved.metaApiToken, saved.accountId).getOrThrow() }.getOrNull()
    }
    LaunchedEffect(account, strategyRunning) {
        val current = account ?: return@LaunchedEffect
        while (true) {
            if (saved.metaApiToken.isNotBlank()) meta.refresh(saved.metaApiToken, current).onSuccess { snapshot = it; status = "MetaApi ${it.account.connectionStatus}" }.onFailure { status = it.message ?: "MetaApi refresh failed" }
            if (strategyRunning && saved.flashAlphaKey.isNotBlank()) flash.snapshot(saved.flashAlphaKey, "SPX").onSuccess { flashData = it }
            delay(5000)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Panel, primary = Cyan, secondary = Green, error = Red)) {
        Scaffold(containerColor = Bg, bottomBar = {
            NavigationBar(containerColor = Color(0xFF08111D)) {
                listOf(Tab.HOME to "HOME", Tab.METAAPI to "METAAPI", Tab.STRATEGY to "STRATEGY", Tab.ACTIVITY to "ACTIVITY").forEach { (t, label) -> NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = {}, label = { Text(label, fontSize = 9.sp) }) }
            }
        }) { pad ->
            when (tab) {
                Tab.HOME -> Home(Modifier.padding(pad), account, snapshot, flashData, strategyRunning) { tab = Tab.METAAPI }
                Tab.METAAPI -> MetaApiTab(Modifier.padding(pad), saved, account, busy, status, onSave = { saved = it; scope.launch { context.saveConnection(it) } }, onConnect = {
                    busy = true
                    scope.launch {
                        val result = if (it.accountId.isNotBlank()) meta.connectExisting(it.metaApiToken, it.accountId) else meta.createAndDeploy(it.metaApiToken, it.login, it.password, it.server)
                        result.onSuccess { a -> account = a; saved = it.copy(accountId = a.id); context.saveConnection(saved); status = "CONNECTED — ${a.login} / ${a.server}" }.onFailure { status = it.message ?: "Connection failed" }
                        busy = false
                    }
                })
                Tab.STRATEGY -> StrategyTab(Modifier.padding(pad), saved, flashData, strategyRunning, status, engine) { running ->
                    strategyRunning = running
                    if (running && saved.flashAlphaKey.isNotBlank()) scope.launch { flash.snapshot(saved.flashAlphaKey, "SPX").onSuccess { flashData = it; status = "Strategy 001 active — FlashAlpha connected" }.onFailure { status = it.message ?: "FlashAlpha request failed" } }
                }
                Tab.ACTIVITY -> ActivityTab(Modifier.padding(pad), status, account, strategyRunning, flashData)
            }
        }
    }
}

@Composable private fun Header(title: String, subtitle: String) { Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp)) { Text("Pips-life", color = Cyan, fontSize = 25.sp, fontWeight = FontWeight.Black); Text(title, color = TextMain, fontSize = 21.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, fontSize = 11.sp) } }

@Composable private fun Home(modifier: Modifier, account: MetaAccount?, snapshot: MetaSnapshot?, flash: FlashAlphaSnapshot?, running: Boolean, connect: () -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("Command Center", "Direct-to-MetaApi trading application") }
        item { CardBlock { Text("CONNECTION", color = Muted, fontSize = 10.sp); Text(if (account == null) "NOT CONNECTED" else "METAAPI • ${account.connectionStatus}", color = if (account == null) Red else Green, fontWeight = FontWeight.Bold); if (account == null) Button(onClick = connect, modifier = Modifier.fillMaxWidth()) { Text("OPEN METAAPI CONNECTION") } } }
        item { CardBlock { Text("STRATEGY 001", color = Muted, fontSize = 10.sp); Text(if (running) "QOF ENGINE ACTIVE" else "READY", color = if (running) Green else Cyan, fontSize = 19.sp, fontWeight = FontWeight.Black); Text("FlashAlpha intelligence is consumed locally by the Strategy 001 engine.", color = Muted, fontSize = 11.sp) } }
        item { CardBlock { Text("EQUITY", color = Muted, fontSize = 10.sp); Text(money(snapshot?.equity), color = TextMain, fontSize = 29.sp, fontWeight = FontWeight.Black); Text("Balance ${money(snapshot?.balance)} • Free margin ${money(snapshot?.freeMargin)}", color = Muted, fontSize = 11.sp) } }
        item { CardBlock { Text("FLASHALPHA", color = Muted, fontSize = 10.sp); Text(flash?.let { "${it.symbol} • ${it.regime}" } ?: "NOT CONNECTED", color = if (flash == null) Muted else Purple, fontWeight = FontWeight.Bold); Text(flash?.let { "Flow ${it.flowDirection.ifBlank { "—" }} • GEX ${number(it.netGex)}" } ?: "Add your FlashAlpha API key in Strategy", color = Muted, fontSize = 11.sp) } }
        item { Text("OPEN POSITIONS", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp)) }
        if (snapshot?.positions?.isNotEmpty() == true) items(snapshot.positions) { CardBlock { Text("${it.symbol} • ${it.type}", color = TextMain, fontWeight = FontWeight.Bold); Text("${it.volume} @ ${number(it.openPrice)} • P/L ${number(it.profit)}", color = Muted, fontSize = 11.sp) } } else item { CardBlock { Text("No open positions", color = Muted) } }
    }
}

@Composable private fun MetaApiTab(modifier: Modifier, saved: SavedConnection, account: MetaAccount?, busy: Boolean, status: String, onSave: (SavedConnection) -> Unit, onConnect: (SavedConnection) -> Unit) {
    var token by remember(saved.metaApiToken) { mutableStateOf(saved.metaApiToken) }
    var accountId by remember(saved.accountId) { mutableStateOf(saved.accountId) }
    var login by remember(saved.login) { mutableStateOf(saved.login) }
    var password by remember(saved.password) { mutableStateOf(saved.password) }
    var server by remember(saved.server) { mutableStateOf(saved.server) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("MetaApi Connection", "No Vercel • No Render • No Pips-life backend") }
        item { Field("MetaApi auth token", token, { token = it }, true) }
        item { Field("Existing MetaApi account ID (optional)", accountId, { accountId = it }) }
        item { Text("If account ID is blank, Pips-life creates and deploys the MT5 account directly through MetaApi.", color = Muted, fontSize = 11.sp) }
        item { Field("MT5 login", login, { login = it }) }
        item { Field("MT5 password", password, { password = it }, true) }
        item { Field("MT5 server", server, { server = it }) }
        item { Text("Credentials and tokens are encrypted with Android Keystore and saved locally on this device.", color = Muted, fontSize = 10.sp) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { onSave(SavedConnection(token, accountId, login, password, server, saved.flashAlphaKey)) }, modifier = Modifier.weight(1f)) { Text("SAVE") }; Button(enabled = !busy && token.isNotBlank(), onClick = { onConnect(SavedConnection(token, accountId, login, password, server, saved.flashAlphaKey)) }, modifier = Modifier.weight(1f)) { Text(if (busy) "CONNECTING…" else "CONNECT") } } }
        item { CardBlock { Text(status, color = if (account != null) Green else Muted, fontSize = 12.sp); account?.let { Text("${it.login} • ${it.server} • ${it.region}", color = TextMain, fontWeight = FontWeight.Bold) } } }
    }
}

@Composable private fun StrategyTab(modifier: Modifier, saved: SavedConnection, flash: FlashAlphaSnapshot?, running: Boolean, status: String, engine: Strategy001Engine, onToggle: (Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember(saved.flashAlphaKey) { mutableStateOf(saved.flashAlphaKey) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("Strategy 001", "QOF engine • FlashAlpha integrated on-device") }
        item { Field("FlashAlpha API key", key, { key = it }, true) }
        item { Button(onClick = { scope.launch { val current = context.loadSavedConnection(); context.saveConnection(current.copy(flashAlphaKey = key)); onToggle(!running) } }, modifier = Modifier.fillMaxWidth()) { Text(if (running) "STOP STRATEGY 001" else "START STRATEGY 001") } }
        item { CardBlock { Text("ENGINE STATUS", color = Muted, fontSize = 10.sp); Text(if (running) "ACTIVE" else "READY", color = if (running) Green else Cyan, fontSize = 20.sp, fontWeight = FontWeight.Black); Text(status, color = Muted, fontSize = 11.sp) } }
        item { CardBlock { Text("QOF SIGNAL", color = Muted, fontSize = 10.sp); Text(engine.evaluate(flash), color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold); Text("GEX ${number(flash?.netGex)} • Regime ${flash?.regime ?: "—"} • Flow ${flash?.flowDirection ?: "—"}", color = Muted, fontSize = 11.sp) } }
    }
}

@Composable private fun ActivityTab(modifier: Modifier, status: String, account: MetaAccount?, running: Boolean, flash: FlashAlphaSnapshot?) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("Activity", "Local engine and direct API connection events") }
        item { CardBlock { Text(status, color = TextMain, fontWeight = FontWeight.Bold); Text("MetaApi: ${account?.connectionStatus ?: "OFFLINE"}", color = Muted); Text("Strategy 001: ${if (running) "ACTIVE" else "STOPPED"}", color = Muted); Text("FlashAlpha: ${if (flash != null) "LIVE" else "NOT CONNECTED"}", color = Muted) } }
        item { CardBlock { Text("ARCHITECTURE", color = Muted, fontSize = 10.sp); Text("Android → MetaApi / FlashAlpha", color = Cyan, fontWeight = FontWeight.Bold); Text("No Vercel runtime. No Render runtime. No Pips-life application backend.", color = Muted, fontSize = 11.sp) } }
    }
}

@Composable private fun Field(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false) { OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = Line)) }
@Composable private fun CardBlock(content: @Composable ColumnScope.() -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content) } }
private fun money(v: Double?): String = v?.takeIf { !it.isNaN() }?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
private fun number(v: Double): String = if (v.isNaN()) "—" else String.format(Locale.US, "%.2f", v)
private fun number(v: Double?): String = if (v == null || v.isNaN()) "—" else String.format(Locale.US, "%.2f", v)

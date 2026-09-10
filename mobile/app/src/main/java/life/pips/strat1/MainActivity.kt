package life.pips.strat1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

private val Bg = Color(0xFF050A12); private val Panel = Color(0xFF0C1422); private val Line = Color(0xFF223149); private val TextMain = Color(0xFFF4F7FB); private val Muted = Color(0xFF8EA2BB); private val Cyan = Color(0xFF27D8FF); private val Green = Color(0xFF43F28E); private val Red = Color(0xFFFF5872); private val Purple = Color(0xFFB36BFF)

class MainActivity : ComponentActivity() { override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { PipsLifeApp() } } }
private enum class Tab { HOME, METAAPI, STRATEGY, WATCHLIST, ACTIVITY }

private data class Signal(val side: TradeSide?, val score: Int, val reason: String)
private class Strategy001Engine {
    fun evaluate(f: FlashAlphaSnapshot?, candles: List<PriceCandle>): Signal {
        if (f == null || candles.size < 2) return Signal(null, 0, "Waiting for FlashAlpha + 1m market data")
        val flow = f.flowDirection.uppercase(Locale.US)
        val flowSide = when { flow.contains("LONG") || flow.contains("CALL") || flow.contains("BULL") -> TradeSide.BUY; flow.contains("SHORT") || flow.contains("PUT") || flow.contains("BEAR") -> TradeSide.SELL; else -> null }
        val last = candles.last(); val previous = candles[candles.lastIndex - 1]
        val priceSide = when { last.close > previous.close -> TradeSide.BUY; last.close < previous.close -> TradeSide.SELL; else -> null }
        val gamma = if (!f.liveGex.isNaN() && f.liveGex < 0) 15 else 0
        val score = (50 + if (flowSide != null) 20 else 0 + if (flowSide == priceSide) 20 else 0 + gamma).coerceAtMost(100)
        return if (flowSide != null && flowSide == priceSide && score >= 70) Signal(flowSide, score, "QOF flow + 1m momentum aligned") else Signal(null, score, "No tradable QOF confluence")
    }
    fun momentumReversed(position: MetaPosition, candles: List<PriceCandle>): Boolean {
        if (candles.size < 2) return false
        val c = candles.last(); val prev = candles[candles.lastIndex - 1]
        return if (position.type.uppercase(Locale.US).contains("BUY") || position.type.uppercase(Locale.US).contains("LONG")) c.close < prev.close else c.close > prev.close
    }
}

@Composable private fun PipsLifeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current; val scope = rememberCoroutineScope(); val meta = remember { DirectMetaApiClient() }; val flash = remember { FlashAlphaClient() }; val engine = remember { Strategy001Engine() }
    var tab by remember { mutableStateOf(Tab.HOME) }; var saved by remember { mutableStateOf(SavedConnection("", "", "", "", "", "")) }; var account by remember { mutableStateOf<MetaAccount?>(null) }; var snapshot by remember { mutableStateOf<MetaSnapshot?>(null) }; var flashData by remember { mutableStateOf<FlashAlphaSnapshot?>(null) }; var candles by remember { mutableStateOf<List<PriceCandle>>(emptyList()) }; var running by remember { mutableStateOf(false) }; var armed by remember { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }; var status by remember { mutableStateOf("Ready — direct connections only") }; var selectedSymbol by remember { mutableStateOf("XAUUSD") }

    LaunchedEffect(Unit) { saved = context.loadSavedConnection(); selectedSymbol = saved.watchlist.split(',').firstOrNull()?.trim().orEmpty().ifBlank { "XAUUSD" }; if (saved.accountId.isNotBlank() && saved.metaApiToken.isNotBlank()) account = meta.connectExisting(saved.metaApiToken, saved.accountId).getOrNull() }
    LaunchedEffect(account, running, armed, selectedSymbol) {
        val a = account ?: return@LaunchedEffect
        while (true) {
            if (saved.metaApiToken.isBlank()) { delay(1000); continue }
            meta.refresh(saved.metaApiToken, a, saved.watchlist.split(',')).onSuccess { s -> snapshot = s; status = "MetaApi ${s.account.connectionStatus}" }.onFailure { status = it.message ?: "MetaApi refresh failed" }
            if (saved.flashAlphaKey.isNotBlank()) flash.snapshot(saved.flashAlphaKey, "SPY").onSuccess { flashData = it }.onFailure { if (running) status = it.message ?: "FlashAlpha failed" }
            meta.candles(saved.metaApiToken, a, selectedSymbol, "1m", 10).onSuccess { candles = it }
            if (running && armed && saved.flashAlphaKey.isNotBlank()) executeCycle(context, meta, engine, a, saved, selectedSymbol, snapshot, flashData, candles) { status = it }
            delay(5000)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Panel, primary = Cyan, secondary = Green, error = Red)) {
        Scaffold(containerColor = Bg, bottomBar = { NavigationBar(containerColor = Color(0xFF08111D)) { listOf(Tab.HOME to "HOME", Tab.METAAPI to "METAAPI", Tab.STRATEGY to "STRATEGY", Tab.WATCHLIST to "WATCH", Tab.ACTIVITY to "ACTIVITY").forEach { (t, label) -> NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = {}, label = { Text(label, fontSize = 8.sp) }) } } }) { pad ->
            when (tab) {
                Tab.HOME -> Home(Modifier.padding(pad), account, snapshot, flashData, running, armed) { tab = Tab.METAAPI }
                Tab.METAAPI -> MetaApiTab(Modifier.padding(pad), saved, account, busy, status, onSave = { saved = it; scope.launch { context.saveConnection(it) } }, onConnect = { c -> busy = true; scope.launch { val r = if (c.accountId.isNotBlank()) meta.connectExisting(c.metaApiToken, c.accountId) else meta.createAndDeploy(c.metaApiToken, c.login, c.password, c.server); r.onSuccess { x -> account = x; saved = c.copy(accountId = x.id); context.saveConnection(saved); status = "CONNECTED — ${x.login} / ${x.server}" }.onFailure { status = it.message ?: "Connection failed" }; busy = false } })
                Tab.STRATEGY -> StrategyTab(Modifier.padding(pad), saved, flashData, candles, running, armed, engine) { r, a -> running = r; armed = a; if (!r) armed = false }
                Tab.WATCHLIST -> WatchlistTab(Modifier.padding(pad), saved, snapshot, selectedSymbol) { value -> selectedSymbol = value; saved = saved.copy(watchlist = value); scope.launch { context.saveConnection(saved) } }
                Tab.ACTIVITY -> ActivityTab(Modifier.padding(pad), status, account, running, armed, flashData)
            }
        }
    }
}

private suspend fun executeCycle(context: android.content.Context, meta: DirectMetaApiClient, engine: Strategy001Engine, account: MetaAccount, saved: SavedConnection, symbol: String, snapshot: MetaSnapshot?, flash: FlashAlphaSnapshot?, candles: List<PriceCandle>, setStatus: (String) -> Unit) {
    val s = snapshot ?: return; val signal = engine.evaluate(flash, candles); val positions = s.positions.filter { it.symbol.equals(symbol, true) }
    for (p in positions) {
        if (engine.momentumReversed(p, candles) && p.profit > 0.0) meta.closePosition(saved.metaApiToken, account, p.id).onSuccess { setStatus("Quick exit confirmed: ${p.id}") }.onFailure { setStatus(it.message ?: "Quick exit failed") }
        else if (!p.stopLoss.isNaN()) {
            val tick = s.prices[symbol] ?: continue; val distance = (candles.takeLast(5).map { it.high - it.low }.average()).coerceAtLeast(tick.ask - tick.bid) * 1.2
            val desired = if (p.type.uppercase(Locale.US).contains("BUY") || p.type.uppercase(Locale.US).contains("LONG")) tick.bid - distance else tick.ask + distance
            val improves = if (p.type.uppercase(Locale.US).contains("BUY") || p.type.uppercase(Locale.US).contains("LONG")) desired > p.stopLoss else desired < p.stopLoss
            if (improves) meta.modifyPosition(saved.metaApiToken, account, p.id, stopLoss = desired).onSuccess { setStatus("Trailing stop confirmed") }
        }
    }
    if (positions.size >= 4 || signal.side == null) return
    if (positions.any { (it.type.uppercase(Locale.US).contains("BUY") && signal.side == TradeSide.BUY) || (it.type.uppercase(Locale.US).contains("SELL") && signal.side == TradeSide.SELL) }) return
    val tick = s.prices[symbol] ?: return; val distance = (candles.takeLast(5).map { it.high - it.low }.average()).coerceAtLeast(tick.ask - tick.bid) * 1.5
    val stop = if (signal.side == TradeSide.BUY) tick.bid - distance else tick.ask + distance
    meta.marketOrder(saved.metaApiToken, account, signal.side, symbol, 0.01, stopLoss = stop).onSuccess { setStatus("${signal.side} order confirmed: ${it.stringCode} ${it.orderId}") }.onFailure { setStatus(it.message ?: "Entry failed") }
}

@Composable private fun Header(title: String, subtitle: String) { Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp)) { Text("Pips-life", color = Cyan, fontSize = 25.sp, fontWeight = FontWeight.Black); Text(title, color = TextMain, fontSize = 21.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, fontSize = 11.sp) } }
@Composable private fun Home(modifier: Modifier, account: MetaAccount?, snapshot: MetaSnapshot?, flash: FlashAlphaSnapshot?, running: Boolean, armed: Boolean, connect: () -> Unit) { LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) { item { Header("Command Center", "Android → MetaApi / FlashAlpha • no backend") }; item { CardBlock { Text("CONNECTION", color = Muted, fontSize = 10.sp); Text(if (account == null) "NOT CONNECTED" else "METAAPI • ${account.connectionStatus}", color = if (account == null) Red else Green, fontWeight = FontWeight.Bold); if (account == null) Button(onClick = connect, modifier = Modifier.fillMaxWidth()) { Text("OPEN METAAPI CONNECTION") } } }; item { CardBlock { Text("STRATEGY 001", color = Muted, fontSize = 10.sp); Text(if (!running) "STOPPED" else if (armed) "LIVE TRADING ARMED" else "MONITORING", color = if (armed) Red else Cyan, fontSize = 19.sp, fontWeight = FontWeight.Black); Text("Trailing protection + same-candle momentum exit. No fixed TP.", color = Muted, fontSize = 11.sp) } }; item { CardBlock { Text("EQUITY", color = Muted, fontSize = 10.sp); Text(money(snapshot?.equity), color = TextMain, fontSize = 29.sp, fontWeight = FontWeight.Black); Text("Balance ${money(snapshot?.balance)} • Free margin ${money(snapshot?.freeMargin)}", color = Muted, fontSize = 11.sp) } }; item { CardBlock { Text("FLASHALPHA", color = Muted, fontSize = 10.sp); Text(flash?.let { "${it.symbol} • ${it.regime}" } ?: "NOT CONNECTED", color = if (flash == null) Muted else Purple, fontWeight = FontWeight.Bold); Text(flash?.let { "Flow ${it.flowDirection.ifBlank { "—" }} • Live GEX ${number(it.liveGex)}" } ?: "Add API key in Strategy", color = Muted, fontSize = 11.sp) } }; item { Text("OPEN POSITIONS", color = Muted, fontSize = 10.sp) }; if (snapshot?.positions?.isNotEmpty() == true) items(snapshot.positions) { CardBlock { Text("${it.symbol} • ${it.type}", color = TextMain, fontWeight = FontWeight.Bold); Text("${it.volume} @ ${number(it.openPrice)} • P/L ${number(it.profit)} • SL ${number(it.stopLoss)}", color = Muted, fontSize = 11.sp) } } else item { CardBlock { Text("No open positions", color = Muted) } } } }

@Composable private fun MetaApiTab(modifier: Modifier, saved: SavedConnection, account: MetaAccount?, busy: Boolean, status: String, onSave: (SavedConnection) -> Unit, onConnect: (SavedConnection) -> Unit) { var token by remember(saved.metaApiToken) { mutableStateOf(saved.metaApiToken) }; var accountId by remember(saved.accountId) { mutableStateOf(saved.accountId) }; var login by remember(saved.login) { mutableStateOf(saved.login) }; var password by remember(saved.password) { mutableStateOf(saved.password) }; var server by remember(saved.server) { mutableStateOf(saved.server) }; LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) { item { Header("MetaApi Connection", "Direct account provisioning + direct trade API") }; item { Field("MetaApi auth token", token, { token = it }, true) }; item { Field("Existing MetaApi account ID (optional)", accountId, { accountId = it }) }; item { Field("MT5 login", login, { login = it }) }; item { Field("MT5 password", password, { password = it }, true) }; item { Field("MT5 server", server, { server = it }) }; item { Text("Keys are encrypted locally with Android Keystore. MetaApi calls leave the device directly.", color = Muted, fontSize = 10.sp) }; item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { onSave(SavedConnection(token, accountId, login, password, server, saved.flashAlphaKey, saved.watchlist)) }, modifier = Modifier.weight(1f)) { Text("SAVE") }; Button(enabled = !busy && token.isNotBlank(), onClick = { onConnect(SavedConnection(token, accountId, login, password, server, saved.flashAlphaKey, saved.watchlist)) }, modifier = Modifier.weight(1f)) { Text(if (busy) "CONNECTING…" else "CONNECT") } } }; item { CardBlock { Text(status, color = if (account != null) Green else Muted); account?.let { Text("${it.login} • ${it.server} • ${it.region}", color = TextMain, fontWeight = FontWeight.Bold) } } } } }

@Composable private fun StrategyTab(modifier: Modifier, saved: SavedConnection, flash: FlashAlphaSnapshot?, candles: List<PriceCandle>, running: Boolean, armed: Boolean, engine: Strategy001Engine, onState: (Boolean, Boolean) -> Unit) { val context = androidx.compose.ui.platform.LocalContext.current; val scope = rememberCoroutineScope(); var key by remember(saved.flashAlphaKey) { mutableStateOf(saved.flashAlphaKey) }; var flashSymbol by remember { mutableStateOf("SPY") }; LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) { item { Header("Strategy 001", "QOF confluence + FlashAlpha + MetaApi execution") }; item { Field("FlashAlpha API key", key, { key = it }, true) }; item { Field("FlashAlpha symbol", flashSymbol, { flashSymbol = it }) }; item { Button(onClick = { scope.launch { val c = context.loadSavedConnection(); context.saveConnection(c.copy(flashAlphaKey = key)); onState(!running, false) } }, modifier = Modifier.fillMaxWidth()) { Text(if (running) "STOP STRATEGY 001" else "START MONITORING") } }; if (running) item { Button(onClick = { onState(true, !armed) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (armed) Red else Green)) { Text(if (armed) "DISARM LIVE TRADING" else "ARM LIVE TRADING") } }; item { CardBlock { Text("QOF SIGNAL", color = Muted, fontSize = 10.sp); val s = engine.evaluate(flash, candles); Text(if (s.side == null) "WAIT" else s.side.name, color = if (s.side == null) Muted else Green, fontSize = 20.sp, fontWeight = FontWeight.Black); Text("${s.reason} • score ${s.score}/100", color = TextMain); Text("Live GEX ${number(flash?.liveGex)} • Flow ${flash?.flowDirection ?: "—"}", color = Muted, fontSize = 11.sp) } }; item { Text("Live trading is explicitly armed by a separate button. Maximum 4 positions; 0.01 lot default; dynamic trailing SL; no fixed TP.", color = Muted, fontSize = 10.sp) } } }

@Composable private fun WatchlistTab(modifier: Modifier, saved: SavedConnection, snapshot: MetaSnapshot?, selected: String, onSave: (String) -> Unit) { var text by remember(saved.watchlist) { mutableStateOf(saved.watchlist) }; LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) { item { Header("Market Watchlist", "Real MetaApi quotes — not placeholder data") }; item { Field("Symbols, comma separated", text, { text = it.uppercase(Locale.US) }) }; item { Button(onClick = { onSave(text) }, modifier = Modifier.fillMaxWidth()) { Text("SAVE WATCHLIST") } }; items(text.split(',').map { it.trim() }.filter { it.isNotBlank() }) { symbol -> CardBlock { Text(symbol, color = if (symbol == selected) Cyan else TextMain, fontWeight = FontWeight.Bold); val p = snapshot?.prices?.get(symbol); Text("Bid ${number(p?.bid)} • Ask ${number(p?.ask)}", color = Muted); if (symbol != selected) OutlinedButton(onClick = { onSave(text) }) { Text("Use in Strategy: $symbol") } } } } }

@Composable private fun ActivityTab(modifier: Modifier, status: String, account: MetaAccount?, running: Boolean, armed: Boolean, flash: FlashAlphaSnapshot?) { LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { Header("Activity", "Direct connection state") }; item { CardBlock { Text(status, color = TextMain, fontWeight = FontWeight.Bold); Text("MetaApi: ${account?.connectionStatus ?: "OFFLINE"}", color = Muted); Text("Strategy 001: ${if (running) "ACTIVE" else "STOPPED"}", color = Muted); Text("Live trading: ${if (armed) "ARMED" else "DISARMED"}", color = Muted); Text("FlashAlpha: ${if (flash != null) "LIVE" else "NOT CONNECTED"}", color = Muted) } }; item { CardBlock { Text("ARCHITECTURE", color = Muted, fontSize = 10.sp); Text("Android → MetaApi / FlashAlpha", color = Cyan, fontWeight = FontWeight.Bold); Text("No Vercel runtime. No Render runtime. No Pips-life application backend.", color = Muted, fontSize = 11.sp) } } } }

@Composable private fun Field(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false) { OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = Line)) }
@Composable private fun CardBlock(content: @Composable ColumnScope.() -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content) } }
private fun money(v: Double?): String = v?.takeIf { !it.isNaN() }?.let { String.format(Locale.US, "%.2f", it) } ?: "—"; private fun number(v: Double): String = if (v.isNaN()) "—" else String.format(Locale.US, "%.2f", v); private fun number(v: Double?): String = if (v == null || v.isNaN()) "—" else String.format(Locale.US, "%.2f", v)

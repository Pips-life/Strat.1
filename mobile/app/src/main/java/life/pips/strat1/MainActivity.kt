package life.pips.strat1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

private val Bg = Color(0xFF050A12)
private val Panel = Color(0xFF0C1422)
private val TextMain = Color(0xFFF4F7FB)
private val Muted = Color(0xFF8EA2BB)
private val Cyan = Color(0xFF27D8FF)
private val Green = Color(0xFF43F28E)
private val Red = Color(0xFFFF5872)
private val Purple = Color(0xFFB36BFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PipsLifeApp() }
    }
}

private enum class Tab { HOME, METAAPI, STRATEGY, WATCHLIST, ACTIVITY }

@Composable
private fun PipsLifeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val meta = remember { DirectMetaApiClient() }
    val flash = remember { FlashAlphaClient() }
    val engine = remember { TradingEngine(meta) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    var saved by remember { mutableStateOf(SavedConnection("", "", "", "", "", "", "XAUUSD,NAS100,EURUSD,GBPUSD,US30")) }
    var account by remember { mutableStateOf<MetaAccount?>(null) }
    var snapshot by remember { mutableStateOf<MetaSnapshot?>(null) }
    var flashData by remember { mutableStateOf<FlashAlphaSnapshot?>(null) }
    var selectedStrategy by remember { mutableStateOf(TradingEngine.StrategyId.STRATEGY_001) }
    var selectedSymbol by remember { mutableStateOf("XAUUSD") }
    var flashSymbol by remember { mutableStateOf("SPY") }
    var running by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready — direct MetaApi + FlashAlpha") }
    var busy by remember { mutableStateOf(false) }
    var lastFlashPull by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        saved = context.loadSavedConnection()
        selectedSymbol = saved.watchlist.split(',').firstOrNull()?.trim().orEmpty().ifBlank { "XAUUSD" }
        if (saved.accountId.isNotBlank() && saved.metaApiToken.isNotBlank()) {
            account = meta.connectExisting(saved.metaApiToken, saved.accountId).getOrNull()
        }
    }

    LaunchedEffect(account, running, armed, selectedSymbol, flashSymbol, saved) {
        val a = account ?: return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            val symbols = saved.watchlist.split(',').map { it.trim() }.filter { it.isNotBlank() }
            // Broker state/quotes are refreshed frequently. Account provisioning is configured
            // for quoteStreamingIntervalInSeconds=0 so the MetaApi side keeps tick-level quotes.
            meta.refresh(saved.metaApiToken, a, symbols)
                .onSuccess { s ->
                    snapshot = s
                    if (!running) status = "MetaApi ${s.account.connectionStatus} • live quote monitor"
                }
                .onFailure { if (!running) status = it.message ?: "MetaApi refresh failed" }

            // FlashAlpha is deliberately throttled separately to avoid 429s while MetaApi
            // market monitoring remains responsive.
            if (saved.flashAlphaKey.isNotBlank() && (now - lastFlashPull >= 10_000L || flashData == null)) {
                lastFlashPull = now
                flash.snapshot(saved.flashAlphaKey, flashSymbol)
                    .onSuccess { flashData = it }
                    .onFailure { if (running) status = it.message ?: "FlashAlpha failed" }
            }

            if (running && saved.metaApiToken.isNotBlank()) {
                snapshot?.let { s ->
                    engine.execute(selectedStrategy, a, saved, s, flashData, selectedSymbol) { status = it }
                }
            }
            // 1s control cadence keeps the broker price/zone/execution state visibly live
            // without hammering FlashAlpha.
            delay(1000)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Panel, primary = Cyan, secondary = Green, error = Red)) {
        Scaffold(containerColor = Bg, bottomBar = {
            NavigationBar(containerColor = Color(0xFF08111D)) {
                listOf(Tab.HOME to "HOME", Tab.METAAPI to "METAAPI", Tab.STRATEGY to "STRATEGY", Tab.WATCHLIST to "WATCH", Tab.ACTIVITY to "ACTIVITY").forEach { (t, label) ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = {}, label = { Text(label, fontSize = 8.sp) })
                }
            }
        }) { pad ->
            when (tab) {
                Tab.HOME -> Home(Modifier.padding(pad), account, snapshot, flashData, selectedStrategy, running, armed) { tab = Tab.STRATEGY }
                Tab.METAAPI -> MetaApiTab(Modifier.padding(pad), saved, account, busy, status,
                    onSave = { value -> saved = value; scope.launch { context.saveConnection(value) } },
                    onConnect = { value ->
                        busy = true
                        scope.launch {
                            val result = if (value.accountId.isNotBlank()) meta.connectExisting(value.metaApiToken, value.accountId)
                            else meta.createAndDeploy(value.metaApiToken, value.login, value.password, value.server)
                            result.onSuccess { a2 -> account = a2; saved = value.copy(accountId = a2.id); context.saveConnection(saved); status = "CONNECTED — ${a2.login} / ${a2.server}" }
                                .onFailure { status = it.message ?: "Connection failed" }
                            busy = false
                        }
                    })
                Tab.STRATEGY -> StrategyTab(Modifier.padding(pad), saved, account, snapshot, flashData, selectedSymbol, flashSymbol, selectedStrategy, running, armed, status,
                    onSelect = { selectedStrategy = it; running = false; armed = false },
                    onFlashSymbol = { flashSymbol = it },
                    onSave = { value -> saved = value; scope.launch { context.saveConnection(value) } },
                    onRun = { running = it; if (!it) armed = false },
                    onArm = { armed = it })
                Tab.WATCHLIST -> WatchlistTab(Modifier.padding(pad), saved, snapshot, selectedSymbol) { value ->
                    selectedSymbol = value.split(',').firstOrNull()?.trim().orEmpty().ifBlank { selectedSymbol }
                    saved = saved.copy(watchlist = value)
                    scope.launch { context.saveConnection(saved) }
                }
                Tab.ACTIVITY -> ActivityTab(Modifier.padding(pad), status, account, selectedStrategy, running, armed, flashData, snapshot, selectedSymbol, engine)
            }
        }
    }
}

@Composable private fun Header(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp)) {
        Text("Pips-life", color = Cyan, fontSize = 25.sp, fontWeight = FontWeight.Black)
        Text(title, color = TextMain, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, fontSize = 11.sp)
    }
}

@Composable private fun Home(modifier: Modifier, account: MetaAccount?, snapshot: MetaSnapshot?, flash: FlashAlphaSnapshot?, strategy: TradingEngine.StrategyId, running: Boolean, armed: Boolean, openStrategy: () -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("Command Center", "Live broker monitor • independent strategy modules") }
        item { CardBlock {
            Text("CONNECTION", color = Muted, fontSize = 10.sp)
            Text(if (account == null) "NOT CONNECTED" else "METAAPI • ${account.connectionStatus}", color = if (account == null) Red else Green, fontWeight = FontWeight.Bold)
        } }
        item { CardBlock {
            Text("SELECTED STRATEGY", color = Muted, fontSize = 10.sp)
            Text(if (strategy == TradingEngine.StrategyId.STRATEGY_001) "001 — GEX / QOF DATA ZONES" else "002 — VELOCITY EXPANSION", color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text(if (strategy == TradingEngine.StrategyId.STRATEGY_001) "Live price → FlashAlpha zones → confluence → execution → management. London + New York only." else "Live tick-to-tick velocity drives direction and a 70-pip trailing stop.", color = Muted, fontSize = 11.sp)
            Button(onClick = openStrategy, modifier = Modifier.fillMaxWidth()) { Text("OPEN STRATEGY SELECTOR") }
            Text(if (!running) "STOPPED" else if (armed) "LIVE TRADING ARMED" else "MONITORING", color = if (armed) Red else Cyan, fontWeight = FontWeight.Bold)
        } }
        item { CardBlock { Text("EQUITY", color = Muted, fontSize = 10.sp); Text(money(snapshot?.equity), color = TextMain, fontSize = 29.sp, fontWeight = FontWeight.Black); Text("Balance ${money(snapshot?.balance)} • Free margin ${money(snapshot?.freeMargin)}", color = Muted, fontSize = 11.sp) } }
        item { CardBlock { Text("FLASHALPHA GEX", color = Muted, fontSize = 10.sp); Text(flash?.let { "${it.symbol} • ${it.regime}" } ?: "NOT CONNECTED", color = if (flash == null) Muted else Purple, fontWeight = FontWeight.Bold); Text(flash?.let { "Flip ${number(it.gammaFlip)} • Put ${number(it.putWall)} • Call ${number(it.callWall)}" } ?: "Strategy 001 requires FlashAlpha", color = Muted, fontSize = 11.sp) } }
        item { Text("OPEN POSITIONS", color = Muted, fontSize = 10.sp) }
        if (snapshot?.positions?.isNotEmpty() == true) snapshot.positions.forEach { p -> item { CardBlock { Text("${p.symbol} • ${p.type}", color = TextMain, fontWeight = FontWeight.Bold); Text("${p.volume} @ ${number(p.openPrice)} • P/L ${number(p.profit)} • SL ${number(p.stopLoss)} • TP ${number(p.takeProfit)}", color = Muted, fontSize = 11.sp) } } } else item { CardBlock { Text("No open positions", color = Muted) } }
    }
}

@Composable private fun MetaApiTab(modifier: Modifier, saved: SavedConnection, account: MetaAccount?, busy: Boolean, status: String, onSave: (SavedConnection) -> Unit, onConnect: (SavedConnection) -> Unit) {
    var token by remember(saved.metaApiToken) { mutableStateOf(saved.metaApiToken) }
    var accountId by remember(saved.accountId) { mutableStateOf(saved.accountId) }
    var login by remember(saved.login) { mutableStateOf(saved.login) }
    var password by remember(saved.password) { mutableStateOf(saved.password) }
    var server by remember(saved.server) { mutableStateOf(saved.server) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("MetaApi Connection", "Direct account provisioning + direct trade API") }
        item { Field("MetaApi auth token", token, { token = it }, true) }
        item { Field("Existing MetaApi account ID (optional)", accountId, { accountId = it }) }
        item { Field("MT5 login", login, { login = it }) }
        item { Field("MT5 password", password, { password = it }, true) }
        item { Field("MT5 server", server, { server = it }) }
        item { Text("Credentials are encrypted locally with Android Keystore. No Pips-life trading backend is used.", color = Muted, fontSize = 10.sp) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { onSave(SavedConnection(token, accountId, login, password, server, saved.flashAlphaKey, saved.watchlist)) }, modifier = Modifier.weight(1f)) { Text("SAVE") }; Button(enabled = !busy && token.isNotBlank(), onClick = { onConnect(SavedConnection(token, accountId, login, password, server, saved.flashAlphaKey, saved.watchlist)) }, modifier = Modifier.weight(1f)) { Text(if (busy) "CONNECTING…" else "CONNECT") } } }
        item { CardBlock { Text(status, color = if (account != null) Green else Muted); account?.let { Text("${it.login} • ${it.server} • ${it.region}", color = TextMain, fontWeight = FontWeight.Bold) } } }
    }
}

@Composable private fun StrategyTab(modifier: Modifier, saved: SavedConnection, account: MetaAccount?, snapshot: MetaSnapshot?, flash: FlashAlphaSnapshot?, tradeSymbol: String, flashSymbol: String, selected: TradingEngine.StrategyId, running: Boolean, armed: Boolean, status: String, onSelect: (TradingEngine.StrategyId) -> Unit, onFlashSymbol: (String) -> Unit, onSave: (SavedConnection) -> Unit, onRun: (Boolean) -> Unit, onArm: (Boolean) -> Unit) {
    var key by remember(saved.flashAlphaKey) { mutableStateOf(saved.flashAlphaKey) }
    var symbol by remember(flashSymbol) { mutableStateOf(flashSymbol) }
    var menu by remember { mutableStateOf(false) }
    val price = snapshot?.prices?.get(tradeSymbol)?.let { (it.bid + it.ask) / 2.0 }
    val p1 = remember(flash, price) { if (price != null) Strategy001Engine().evaluate(flash, price) else null }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("Strategy Control", "Shared execution engine • rules remain independent") }
        item { Box {
            Button(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) { Text(if (selected == TradingEngine.StrategyId.STRATEGY_001) "001 — GEX / QOF DATA ZONES" else "002 — VELOCITY EXPANSION") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("001 — GEX / QOF DATA ZONES") }, onClick = { menu = false; onSelect(TradingEngine.StrategyId.STRATEGY_001) })
                DropdownMenuItem(text = { Text("002 — VELOCITY EXPANSION") }, onClick = { menu = false; onSelect(TradingEngine.StrategyId.STRATEGY_002) })
            }
        } }
        item { Text("MetaApi trading symbol: $tradeSymbol", color = Muted, fontSize = 11.sp) }
        if (selected == TradingEngine.StrategyId.STRATEGY_001) {
            item { Field("FlashAlpha API key", key, { key = it }, true) }
            item { Field("FlashAlpha symbol", symbol, { symbol = it; onFlashSymbol(it) }) }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { onSave(saved.copy(flashAlphaKey = key)) }, modifier = Modifier.weight(1f)) { Text("SAVE KEY") }; Button(onClick = { onRun(!running) }, modifier = Modifier.weight(1f)) { Text(if (running) "STOP" else "START") } } }
            if (running) item { Button(onClick = { onArm(!armed) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (armed) Red else Green)) { Text(if (armed) "DISARM LIVE TRADING" else "ARM LIVE TRADING") } }
            item { CardBlock { Text("GEX MARKET MAP", color = Muted, fontSize = 10.sp); if (p1 == null) Text("Waiting for live GEX + MetaApi price", color = Muted) else { Text("${p1.side?.name ?: "WAIT"} • confidence ${p1.confidence}%", color = if (p1.side == null) Muted else Green, fontSize = 20.sp, fontWeight = FontWeight.Black); Text(p1.reason, color = Muted, fontSize = 11.sp); p1.entryZone?.let { Text("ENTRY ${it.kind}: ${number(it.lower)} — ${number(it.upper)} (${it.source})", color = Cyan, fontSize = 11.sp) }; p1.exitZone?.let { Text("EXIT ${it.kind}: ${number(it.lower)} — ${number(it.upper)} (${it.source})", color = Purple, fontSize = 11.sp) }; p1.invalidation?.let { Text("INVALIDATION: ${number(it)}", color = Red, fontSize = 11.sp) } } } }
            item { CardBlock { Text("STRATEGY 001 SESSION", color = Muted, fontSize = 10.sp); Text("London + New York only • 08:00–17:00 local session time with DST handled automatically. Outside these sessions the strategy will not enter trades.", color = TextMain, fontSize = 11.sp) } }
        } else {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { onRun(!running) }, modifier = Modifier.weight(1f)) { Text(if (running) "STOP" else "START") } } }
            if (running) item { Button(onClick = { onArm(!armed) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (armed) Red else Green)) { Text(if (armed) "DISARM LIVE TRADING" else "ARM LIVE TRADING") } }
            item { CardBlock { Text("STRATEGY 002", color = Muted, fontSize = 10.sp); Text("INSTANT VELOCITY EXPANSION", color = Cyan, fontSize = 19.sp, fontWeight = FontWeight.Black); Text("Independent tick-to-tick engine. Uses a 70-pip trailing stop and exits when tick direction reverses. No GEX rules are applied.", color = TextMain, fontSize = 11.sp); Text("Current status: $status", color = Muted, fontSize = 10.sp) } }
        }
    }
}

@Composable private fun WatchlistTab(modifier: Modifier, saved: SavedConnection, snapshot: MetaSnapshot?, selected: String, onSave: (String) -> Unit) {
    var text by remember(saved.watchlist) { mutableStateOf(saved.watchlist) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("Market Watchlist", "Actual MetaApi symbols — stored locally") }
        item { Field("Comma-separated MetaApi symbols", text, { text = it }) }
        item { Button(onClick = { onSave(text) }, modifier = Modifier.fillMaxWidth()) { Text("SAVE WATCHLIST") } }
        item { Text("Selected: $selected", color = Cyan, fontWeight = FontWeight.Bold) }
        snapshot?.prices?.forEach { (symbol, tick) -> item { CardBlock { Text(symbol, color = TextMain, fontWeight = FontWeight.Bold); Text("Bid ${number(tick.bid)} • Ask ${number(tick.ask)} • tick ${tick.time}", color = Muted, fontSize = 11.sp) } } }
    }
}

@Composable private fun ActivityTab(modifier: Modifier, status: String, account: MetaAccount?, strategy: TradingEngine.StrategyId, running: Boolean, armed: Boolean, flash: FlashAlphaSnapshot?, snapshot: MetaSnapshot?, symbol: String, engine: TradingEngine) {
    val price = snapshot?.prices?.get(symbol)?.let { (it.bid + it.ask) / 2.0 }
    val view = if (price != null) engine.view(strategy, flash, price, symbol) else null
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("Activity", "Live strategy state") }
        item { CardBlock { Text("ENGINE", color = Muted, fontSize = 10.sp); Text("${if (strategy == TradingEngine.StrategyId.STRATEGY_001) "STRATEGY 001" else "STRATEGY 002"} • ${if (running) "RUNNING" else "STOPPED"}", color = if (armed) Red else Cyan, fontWeight = FontWeight.Black); Text(if (armed) "LIVE EXECUTION ARMED" else "Monitoring only", color = Muted) } }
        if (view != null) item { CardBlock {
            Text("LIVE DECISION", color = Muted, fontSize = 10.sp)
            Text("${view.phase} • ${view.session}", color = if (view.side == null) Cyan else Green, fontWeight = FontWeight.Black, fontSize = 17.sp)
            Text("Symbol $symbol • Direction ${view.side?.name ?: "WAIT"} • Confluence ${view.confidence}%", color = TextMain, fontSize = 12.sp)
            Text("Entry zone: ${view.entry?.let(::number) ?: "—"}", color = Cyan, fontSize = 11.sp)
            Text("Exit level: ${view.exit?.let(::number) ?: "—"}", color = Purple, fontSize = 11.sp)
            Text("Stop / invalidation: ${view.stop?.let(::number) ?: "—"}", color = Red, fontSize = 11.sp)
            Text(view.confluence, color = Muted, fontSize = 10.sp)
        } }
        item { CardBlock { Text("LATEST EXECUTION EVENT", color = Muted, fontSize = 10.sp); Text(status, color = TextMain, fontSize = 12.sp) } }
        item { CardBlock { Text("DATA PIPELINE", color = Muted, fontSize = 10.sp); Text("MetaApi: ${account?.connectionStatus ?: "not connected"}", color = TextMain); Text("Broker price: ${price?.let(::number) ?: "—"}", color = Muted); Text("FlashAlpha: ${flash?.symbol ?: "not configured"}", color = Muted); Text("Pipeline: live broker price → zones → confluence → execution → management → exit", color = Muted, fontSize = 10.sp) } }
    }
}

@Composable private fun Field(label: String, value: String, onValue: (String) -> Unit, password: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = onValue, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
}

@Composable private fun CardBlock(content: @Composable ColumnScope.() -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = content) } }

private fun number(value: Double): String = if (value.isFinite()) String.format("%.4f", value) else "—"
private fun money(value: Double?): String = value?.let { if (it.isFinite()) String.format("%.2f", it) else "—" } ?: "—"

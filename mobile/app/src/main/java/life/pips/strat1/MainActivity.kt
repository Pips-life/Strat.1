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
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { PipsLifeApp() } }
}

private enum class Tab { HOME, METAAPI, STRATEGY, WATCHLIST, UPDATE }

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
    var flashSymbol by remember { mutableStateOf("GC=F") }
    var running by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready — direct MetaApi + FlashAlpha") }
    var busy by remember { mutableStateOf(false) }
    var lastFlashPull by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        saved = context.loadSavedConnection()
        selectedSymbol = saved.watchlist.split(',').firstOrNull()?.trim().orEmpty().ifBlank { "XAUUSD" }
        if (saved.accountId.isNotBlank() && saved.metaApiToken.isNotBlank()) {
            meta.connectExisting(saved.metaApiToken, saved.accountId).onSuccess { account = it }.onFailure { status = it.message ?: "MetaApi connection failed" }
        }
    }

    LaunchedEffect(account, running, armed, selectedSymbol, flashSymbol, saved.flashAlphaKey, selectedStrategy) {
        val a = account ?: return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            val symbols = saved.watchlist.split(',').map { it.trim() }.filter { it.isNotBlank() }
            meta.refresh(saved.metaApiToken, a, symbols).onSuccess { s -> snapshot = s; if (!running) status = "MetaApi ${s.account.connectionStatus} • live quote monitor" }.onFailure { if (!running) status = it.message ?: "MetaApi refresh failed" }
            if (saved.flashAlphaKey.isNotBlank() && (flashData == null || now - lastFlashPull >= 2 * 60 * 60 * 1000L)) {
                lastFlashPull = now
                flash.snapshot(saved.flashAlphaKey, flashSymbol).onSuccess { flashData = it; status = "FlashAlpha GC=F snapshot received" }.onFailure { if (running && selectedStrategy == TradingEngine.StrategyId.STRATEGY_001) status = it.message ?: "FlashAlpha unavailable" }
            }
            if (selectedStrategy == TradingEngine.StrategyId.STRATEGY_003) {
                engine.strategy003.refresh(a, saved.metaApiToken, selectedSymbol).onFailure { if (!running) status = it.message ?: "SMC candle data unavailable" }
            }
            if (running && armed && saved.metaApiToken.isNotBlank()) snapshot?.let { s -> engine.execute(selectedStrategy, a, saved, s, flashData, selectedSymbol) { status = it } }
            delay(1000)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Panel, primary = Cyan, secondary = Green, error = Red)) {
        Scaffold(containerColor = Bg, bottomBar = {
            NavigationBar(containerColor = Color(0xFF08111D), tonalElevation = 0.dp) {
                listOf(Tab.HOME to "HOME", Tab.METAAPI to "METAAPI", Tab.STRATEGY to "STRATEGY", Tab.WATCHLIST to "WATCH", Tab.UPDATE to "UPDATE").forEach { (t, label) ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = {}, label = { Text(label, fontSize = 7.sp) })
                }
            }
        }) { pad ->
            when (tab) {
                Tab.HOME -> HomeDashboard(Modifier.padding(pad), account, snapshot, flashData, selectedStrategy, running, armed, status, engine, selectedSymbol,
                    onSelectStrategy = { selectedStrategy = it; running = false; armed = false },
                    onOpenStrategy = { tab = Tab.STRATEGY }, onStartStop = { running = it; if (!it) armed = false },
                    onMetaApi = { tab = Tab.METAAPI }, onMarkets = { tab = Tab.WATCHLIST }, onAccount = { tab = Tab.METAAPI })
                Tab.METAAPI -> MetaApiTab(Modifier.padding(pad), saved, account, busy, status,
                    onSave = { value -> saved = value; scope.launch { context.saveConnection(value) } },
                    onConnect = { value ->
                        busy = true
                        scope.launch {
                            val result = if (value.accountId.isNotBlank()) meta.connectExisting(value.metaApiToken, value.accountId) else meta.createAndDeploy(value.metaApiToken, value.login, value.password, value.server)
                            result.onSuccess { a2 -> account = a2; saved = value.copy(accountId = a2.id); context.saveConnection(saved); status = "CONNECTED — ${a2.login} / ${a2.server}" }.onFailure { status = it.message ?: "Connection failed" }
                            busy = false
                        }
                    })
                Tab.STRATEGY -> StrategyTab(Modifier.padding(pad), saved, account, snapshot, flashData, selectedSymbol, flashSymbol, selectedStrategy, running, armed, status,
                    onSelect = { selectedStrategy = it; running = false; armed = false }, onFlashSymbol = { flashSymbol = it }, onSave = { value -> saved = value; scope.launch { context.saveConnection(value) } }, onRun = { running = it; if (!it) armed = false }, onArm = { armed = it })
                Tab.WATCHLIST -> WatchlistTab(Modifier.padding(pad), saved, snapshot, selectedSymbol) { value ->
                    selectedSymbol = value.split(',').firstOrNull()?.trim().orEmpty().ifBlank { selectedSymbol }
                    saved = saved.copy(watchlist = value); scope.launch { context.saveConnection(saved) }
                }
                Tab.UPDATE -> LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 10.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Header("App Update", "Release updater — download only when a newer signed release exists") }
                    item { AppUpdateCard() }
                    item { CardBlock { Text("CURRENT RELEASE", color = Muted, fontSize = 9.sp); Text("v${BuildConfig.VERSION_NAME} • build ${BuildConfig.VERSION_CODE}", color = TextMain, fontWeight = FontWeight.Bold); Text("Updates install over the existing Pips-life app when the package is signed with the same release key.", color = Muted, fontSize = 9.sp) } }
                }
            }
        }
    }
}

@Composable private fun Header(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) { Text("Pips-life", color = Cyan, fontSize = 23.sp, fontWeight = FontWeight.Black); Text(title, color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, fontSize = 9.sp) }
}

@Composable private fun MetaApiTab(modifier: Modifier, saved: SavedConnection, account: MetaAccount?, busy: Boolean, status: String, onSave: (SavedConnection) -> Unit, onConnect: (SavedConnection) -> Unit) {
    var token by remember(saved.metaApiToken) { mutableStateOf(saved.metaApiToken) }; var accountId by remember(saved.accountId) { mutableStateOf(saved.accountId) }; var login by remember(saved.login) { mutableStateOf(saved.login) }; var password by remember(saved.password) { mutableStateOf(saved.password) }; var server by remember(saved.server) { mutableStateOf(saved.server) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
        item { Header("MetaApi Connection", "Direct account provisioning + direct trade API") }; item { Field("MetaApi auth token", token, { token = it }, true) }; item { Field("Existing MetaApi account ID (optional)", accountId, { accountId = it }) }; item { Field("MT5 login", login, { login = it }) }; item { Field("MT5 password", password, { password = it }, true) }; item { Field("MT5 server", server, { server = it }) }
        item { Text("Credentials are encrypted locally with Android Keystore. No Pips-life trading backend is used.", color = Muted, fontSize = 9.sp) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Button(onClick = { onSave(SavedConnection(token, accountId, login, password, server, saved.flashAlphaKey, saved.watchlist)) }, modifier = Modifier.weight(1f)) { Text("SAVE") }; Button(enabled = !busy && token.isNotBlank(), onClick = { onConnect(SavedConnection(token, accountId, login, password, server, saved.flashAlphaKey, saved.watchlist)) }, modifier = Modifier.weight(1f)) { Text(if (busy) "CONNECTING…" else "CONNECT") } } }
        item { CardBlock { Text(status, color = if (account != null) Green else Muted); account?.let { Text("${it.login} • ${it.server} • ${it.region}", color = TextMain, fontWeight = FontWeight.Bold) } } }
    }
}

@Composable private fun StrategyTab(modifier: Modifier, saved: SavedConnection, account: MetaAccount?, snapshot: MetaSnapshot?, flash: FlashAlphaSnapshot?, tradeSymbol: String, flashSymbol: String, selected: TradingEngine.StrategyId, running: Boolean, armed: Boolean, status: String, onSelect: (TradingEngine.StrategyId) -> Unit, onFlashSymbol: (String) -> Unit, onSave: (SavedConnection) -> Unit, onRun: (Boolean) -> Unit, onArm: (Boolean) -> Unit) {
    var key by remember(saved.flashAlphaKey) { mutableStateOf(saved.flashAlphaKey) }; var symbol by remember(flashSymbol) { mutableStateOf(flashSymbol) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
        item { Header("Strategies", "Each strategy has its own selectable tab and isolated rules") }
        item { StrategySelector(selected, onSelect) }
        item { Text("MetaApi trading symbol: $tradeSymbol", color = Muted, fontSize = 10.sp) }
        when (selected) {
            TradingEngine.StrategyId.STRATEGY_001 -> {
                item { Field("FlashAlpha API key", key, { key = it }, true) }; item { Field("FlashAlpha symbol", symbol, { symbol = it; onFlashSymbol(it) }) }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Button(onClick = { onSave(saved.copy(flashAlphaKey = key)) }, modifier = Modifier.weight(1f)) { Text("SAVE KEY") }; Button(onClick = { onRun(!running) }, modifier = Modifier.weight(1f)) { Text(if (running) "STOP" else "START") } } }
                if (running) item { Button(onClick = { onArm(!armed) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (armed) Red else Green)) { Text(if (armed) "DISARM LIVE TRADING" else "ARM LIVE TRADING") } }
                item { CardBlock { Text("STRATEGY 001 • GEX / QOF DATA ZONES", color = Cyan, fontSize = 16.sp, fontWeight = FontWeight.Black); Text("London + New York sessions. FlashAlpha GEX/GREEKS determine entry and exit zones.", color = Muted, fontSize = 10.sp) } }
            }
            TradingEngine.StrategyId.STRATEGY_002 -> {
                item { Button(onClick = { onRun(!running) }, modifier = Modifier.fillMaxWidth()) { Text(if (running) "STOP" else "START") } }; if (running) item { Button(onClick = { onArm(!armed) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (armed) Red else Green)) { Text(if (armed) "DISARM LIVE TRADING" else "ARM LIVE TRADING") } }
                item { CardBlock { Text("STRATEGY 002 • VELOCITY EXPANSION", color = Cyan, fontSize = 16.sp, fontWeight = FontWeight.Black); Text("Independent tick-to-tick engine. Uses its own trailing/reversal rules and does not consume S001 GEX logic.", color = Muted, fontSize = 10.sp) } }
            }
            TradingEngine.StrategyId.STRATEGY_003 -> {
                item { CardBlock { val p = engine003Plan(); Text("STRATEGY 003 • SMART MONEY CONCEPTS", color = Cyan, fontSize = 16.sp, fontWeight = FontWeight.Black); Text("Multi-timeframe market structure: 1H → 15M → 5M → 1M. Execution timeframe: 5M.", color = TextMain, fontSize = 10.sp); Text("Structure  ${p.h1Bias.name} / ${p.m15Bias.name} / ${p.m5Bias.name} / ${p.m1Bias.name}", color = Green, fontSize = 10.sp); Text("${p.bos} • ${p.liquidity} • ${p.fvg} • ${p.orderBlock} • ${p.premiumDiscount}", color = Muted, fontSize = 9.sp); Text("${p.confidence}% confidence • ${p.reason}", color = Muted, fontSize = 9.sp) } }
                item { Button(onClick = { onRun(!running) }, modifier = Modifier.fillMaxWidth()) { Text(if (running) "STOP" else "START 5M SMC") } }; if (running) item { Button(onClick = { onArm(!armed) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (armed) Red else Green)) { Text(if (armed) "DISARM LIVE TRADING" else "ARM LIVE TRADING") } }
            }
        }
        item { CardBlock { Text("STATUS", color = Muted, fontSize = 9.sp); Text(status, color = TextMain, fontSize = 10.sp) } }
    }
}

@Composable private fun StrategySelector(selected: TradingEngine.StrategyId, onSelect: (TradingEngine.StrategyId) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StrategySelectorButton("001", "GEX", selected == TradingEngine.StrategyId.STRATEGY_001) { onSelect(TradingEngine.StrategyId.STRATEGY_001) }
        StrategySelectorButton("002", "VELOCITY", selected == TradingEngine.StrategyId.STRATEGY_002) { onSelect(TradingEngine.StrategyId.STRATEGY_002) }
        StrategySelectorButton("003", "SMC", selected == TradingEngine.StrategyId.STRATEGY_003) { onSelect(TradingEngine.StrategyId.STRATEGY_003) }
    }
}

@Composable private fun RowScope.StrategySelectorButton(id: String, name: String, selected: Boolean, onClick: () -> Unit) {
    Surface(color = if (selected) Cyan.copy(.14f) else Panel, shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp), modifier = Modifier.weight(1f)) { Text("$id\n$name", color = if (selected) Cyan else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    LaunchedEffect(selected) { }
}

@Composable private fun WatchlistTab(modifier: Modifier, saved: SavedConnection, snapshot: MetaSnapshot?, selected: String, onSave: (String) -> Unit) {
    var text by remember(saved.watchlist) { mutableStateOf(saved.watchlist) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 18.dp)) { item { Header("Market Watchlist", "Actual MetaApi symbols — stored locally") }; item { Field("Comma-separated MetaApi symbols", text, { text = it }) }; item { Button(onClick = { onSave(text) }, modifier = Modifier.fillMaxWidth()) { Text("SAVE WATCHLIST") }; Text("Selected: $selected", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 10.sp) }; snapshot?.prices?.forEach { (symbol, tick) -> item { CardBlock { Text(symbol, color = TextMain, fontWeight = FontWeight.Bold); Text("Bid ${number(tick.bid)} • Ask ${number(tick.ask)} • tick ${tick.time}", color = Muted, fontSize = 10.sp) } } } }
}

private fun engine003Plan(): Strategy003Engine.Plan = Strategy003Engine().latest()

@Composable private fun Field(label: String, value: String, onValue: (String) -> Unit, password: Boolean = false) { OutlinedTextField(value = value, onValueChange = onValue, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None) }
@Composable private fun CardBlock(content: @Composable ColumnScope.() -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content) } }
private fun number(value: Double): String = if (value.isFinite()) String.format("%.4f", value) else "—"

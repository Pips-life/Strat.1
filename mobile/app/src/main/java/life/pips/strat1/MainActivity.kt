package life.pips.strat1

import android.os.Bundle
import java.time.LocalDate
import java.time.ZoneId
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.pips.strat1.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Bg = Color.Transparent
private val Panel = Color(0xFF0C1422)
private val TextMain = Color(0xFFF4F7FB)
private val Muted = Color(0xFF8EA2BB)
private val Cyan = Color(0xFF27D8FF)
private val Green = Color(0xFF43F28E)
private val Red = Color(0xFFFF5872)
private val Purple = Color(0xFFB36BFF)

class MainActivity : ComponentActivity() { override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { PipsLifeApp() } } }
private enum class Tab { HOME, METAAPI, STRATEGY, WATCHLIST, UPDATE }

@Composable private fun PipsLifeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current; val scope = rememberCoroutineScope(); val meta = remember { DirectMetaApiClient() }; val flash = remember { FlashAlphaClient() }; val engine = remember { TradingEngine(meta) }
    var tab by remember { mutableStateOf(Tab.HOME) }; var saved by remember { mutableStateOf(SavedConnection("", "", "", "", "", "", "XAUUSD,NAS100,EURUSD,GBPUSD,US30")) }; var account by remember { mutableStateOf<MetaAccount?>(null) }; var snapshot by remember { mutableStateOf<MetaSnapshot?>(null) }; var flashData by remember { mutableStateOf<FlashAlphaSnapshot?>(null) }; var selectedStrategy by remember { mutableStateOf(TradingEngine.StrategyId.STRATEGY_001) }; var selectedSymbol by remember { mutableStateOf("XAUUSD") }; var flashSymbol by remember { mutableStateOf("GC=F") }; var running by remember { mutableStateOf(false) }; var armed by remember { mutableStateOf(false) }; var status by remember { mutableStateOf("Ready — direct MetaApi + FlashAlpha") }; var busy by remember { mutableStateOf(false) }; var lastFlashPull by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { saved = context.loadSavedConnection(); selectedSymbol = saved.watchlist.split(',').firstOrNull()?.trim().orEmpty().ifBlank { "XAUUSD" }; if (saved.accountId.isNotBlank() && saved.metaApiToken.isNotBlank()) meta.connectExisting(saved.metaApiToken, saved.accountId).onSuccess { account = it }.onFailure { status = it.message ?: "MetaApi connection failed" } }
    LaunchedEffect(account, running, armed, selectedSymbol, flashSymbol, saved.flashAlphaKey, selectedStrategy) {
        val a = account ?: return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis(); val symbols = saved.watchlist.split(',').map { it.trim() }.filter { it.isNotBlank() }
            meta.refresh(saved.metaApiToken, a, symbols).onSuccess { s -> snapshot = s; if (!running) status = "MetaApi ${s.account.connectionStatus} • live quote monitor" }.onFailure { if (!running) status = it.message ?: "MetaApi refresh failed" }
            if (saved.flashAlphaKey.isNotBlank() && (flashData == null || now - lastFlashPull >= 2 * 60 * 60 * 1000L)) { lastFlashPull = now; flash.snapshot(saved.flashAlphaKey, flashSymbol).onSuccess { flashData = it; status = "FlashAlpha GC=F snapshot received" }.onFailure { if (running && selectedStrategy == TradingEngine.StrategyId.STRATEGY_001) status = it.message ?: "FlashAlpha unavailable" } }
            if (selectedStrategy == TradingEngine.StrategyId.STRATEGY_003) engine.strategy003.refresh(a, saved.metaApiToken, selectedSymbol).onFailure { if (!running) status = it.message ?: "SMC candle data unavailable" }
            if (selectedStrategy == TradingEngine.StrategyId.STRATEGY_004) engine.strategy004.refresh(a, saved.metaApiToken, selectedSymbol)
            if (selectedStrategy == TradingEngine.StrategyId.STRATEGY_005) engine.strategy005.refresh(a, saved.metaApiToken, selectedSymbol, engine.liveSamples(selectedSymbol)).onFailure { if (!running) status = it.message ?: "Woodie pivot data unavailable" }
            if (running && armed && saved.metaApiToken.isNotBlank()) snapshot?.let { s -> engine.execute(selectedStrategy, a, saved, s, flashData, selectedSymbol) { status = it } }
            delay(1000)
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Panel, primary = Cyan, secondary = Green, error = Red)) {
        Box(Modifier.fillMaxSize()) {
            Image(bitmap = ImageBitmap.imageResource(id = R.drawable.pipslife_ocean_background), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.High, colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(1.08f, 0f, 0f, 0f, -6f, 0f, 1.08f, 0f, 0f, -6f, 0f, 0f, 1.08f, 0f, -6f, 0f, 0f, 0f, 1f, 0f))))
            Scaffold(containerColor = Color.Transparent, bottomBar = { NavigationBar(containerColor = Color(0xFF08111D), tonalElevation = 0.dp) { listOf(Tab.HOME to "HOME", Tab.METAAPI to "METAAPI", Tab.STRATEGY to "STRATEGY", Tab.WATCHLIST to "WATCH", Tab.UPDATE to "UPDATE").forEach { (t, label) -> NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = {}, label = { Text(label, fontSize = 7.sp) }) } } }) { pad ->
                when (tab) {
                    Tab.HOME -> HomeDashboard(Modifier.padding(pad), account, snapshot, flashData, selectedStrategy, running, armed, status, engine, selectedSymbol, onStartStop = { running = it; if (!it) armed = false })
                    Tab.METAAPI -> MetaApiTab(Modifier.padding(pad), saved, account, busy, status, onSave = { value -> saved = value; scope.launch { context.saveConnection(value) } }, onConnect = { value -> busy = true; scope.launch { val result = if (value.accountId.isNotBlank()) meta.connectExisting(value.metaApiToken, value.accountId) else meta.createAndDeploy(value.metaApiToken, value.login, value.password, value.server); result.onSuccess { a2 -> account = a2; saved = value.copy(accountId = a2.id); context.saveConnection(saved); status = "CONNECTED — ${a2.login} / ${a2.server}" }.onFailure { status = it.message ?: "Connection failed" }; busy = false } })
                    Tab.STRATEGY -> StrategyTab(Modifier.padding(pad), saved, account, snapshot, flashData, selectedSymbol, flashSymbol, selectedStrategy, running, armed, status, engine.strategy003.latest(), engine.strategy004.latest(), engine.strategy005.latest(), engine.strategy006, onSelect = { selectedStrategy = it; running = false; armed = false }, onFlashSymbol = { flashSymbol = it }, onSave = { value -> saved = value; scope.launch { context.saveConnection(value) } }, onRun = { running = it; if (!it) armed = false }, onArm = { armed = it })
                    Tab.WATCHLIST -> WatchlistTab(Modifier.padding(pad), saved, snapshot, selectedSymbol) { value -> selectedSymbol = value.split(',').firstOrNull()?.trim().orEmpty().ifBlank { selectedSymbol }; saved = saved.copy(watchlist = value); scope.launch { context.saveConnection(saved) } }
                    Tab.UPDATE -> LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 10.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { item { Header("App Update", "Release updater — download only when a newer signed release exists") }; item { AppUpdateCard() }; item { CardBlock { Text("CURRENT RELEASE", color = Muted, fontSize = 9.sp); Text("v${BuildConfig.VERSION_NAME} • build ${BuildConfig.VERSION_CODE}", color = TextMain, fontWeight = FontWeight.Bold); Text("Updates install over the existing Pips-life app when the package is signed with the same release key.", color = Muted, fontSize = 9.sp) } } }
                }
            }
        }
    }
}

@Composable private fun Header(title: String, subtitle: String) { Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) { Text("Pips-life", color = Cyan, fontSize = 23.sp, fontWeight = FontWeight.Black); Text(title, color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, fontSize = 9.sp) } }

@Composable private fun MetaApiTab(modifier: Modifier, saved: SavedConnection, account: MetaAccount?, busy: Boolean, status: String, onSave: (SavedConnection) -> Unit, onConnect: (SavedConnection) -> Unit) {
    var token by remember(saved.metaApiToken) { mutableStateOf(saved.metaApiToken) }; var accountId by remember(saved.accountId) { mutableStateOf(saved.accountId) }; var login by remember(saved.login) { mutableStateOf(saved.login) }; var password by remember(saved.password) { mutableStateOf(saved.password) }; var server by remember(saved.server) { mutableStateOf(saved.server) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 18.dp)) { item { Header("MetaApi Connection", "Direct account provisioning + direct trade API") }; item { Field("MetaApi auth token", token, { token = it }, true) }; item { Field("Existing MetaApi account ID (optional)", accountId, { accountId = it }) }; item { Field("MT5 login", login, { login = it }) }; item { Field("MT5 password", password, { password = it }, true) }; item { Field("MT5 server", server, { server = it }) }; item { Text("Credentials are encrypted locally with Android Keystore. No Pips-life trading backend is used.", color = Muted, fontSize = 9.sp) }; item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Button(onClick = { onSave(SavedConnection(token, accountId, login, password, server, saved.flashAlphaKey, saved.watchlist)) }, modifier = Modifier.weight(1f)) { Text("SAVE") }; Button(enabled = !busy && token.isNotBlank(), onClick = { onConnect(SavedConnection(token, accountId, login, password, server, saved.flashAlphaKey, saved.watchlist)) }, modifier = Modifier.weight(1f)) { Text(if (busy) "CONNECTING…" else "CONNECT") } } }; item { CardBlock { Text(status, color = if (account != null) Green else Muted); account?.let { Text("${it.login} • ${it.server} • ${it.region}", color = TextMain, fontWeight = FontWeight.Bold) } } } }
}

@Composable private fun StrategyTab(modifier: Modifier, saved: SavedConnection, account: MetaAccount?, snapshot: MetaSnapshot?, flash: FlashAlphaSnapshot?, tradeSymbol: String, flashSymbol: String, selected: TradingEngine.StrategyId, running: Boolean, armed: Boolean, status: String, smcPlan: Strategy003Engine.Plan, priceActionPlan: Strategy004Engine.Plan, woodiePlan: Strategy005Engine.Plan, optionsFlow: Strategy006Engine, onSelect: (TradingEngine.StrategyId) -> Unit, onFlashSymbol: (String) -> Unit, onSave: (SavedConnection) -> Unit, onRun: (Boolean) -> Unit, onArm: (Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var localStatus by remember(status) { mutableStateOf(status) }
    var key by remember(saved.flashAlphaKey) { mutableStateOf(saved.flashAlphaKey) }; var symbol by remember(flashSymbol) { mutableStateOf(flashSymbol) }
    val s006Prefs = remember { context.getSharedPreferences("strategy006_files", android.content.Context.MODE_PRIVATE) }
    var barchartName by remember { mutableStateOf("No Barchart file selected") }
    var greeksName by remember { mutableStateOf("No Greeks CSV selected") }
    var barchartText by remember { mutableStateOf("") }
    var greeksText by remember { mutableStateOf("") }
    val s006Today = remember { LocalDate.now(ZoneId.of("Africa/Nairobi")).toString() }
    LaunchedEffect(Unit) {
        if (s006Prefs.getString("date", "") == s006Today) {
            barchartText = s006Prefs.getString("barchart_text", "").orEmpty()
            greeksText = s006Prefs.getString("greeks_text", "").orEmpty()
            barchartName = s006Prefs.getString("barchart_name", "Cached Barchart options file").orEmpty()
            greeksName = s006Prefs.getString("greeks_name", "Cached Greeks / volatility CSV").orEmpty()
        } else {
            s006Prefs.edit().clear().apply()
        }
    }
    val barchartPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                localStatus = "S006 | READING FILE 1…"
                Strategy006FileExtractor.extract(context, it)
                    .onSuccess { result ->
                        val text = result.first
                        val name = result.second
                        barchartText = text
                        barchartName = name
                        s006Prefs.edit().putString("date", s006Today).putString("barchart_text", text).putString("barchart_name", barchartName).apply()
                        localStatus = "S006 | FILE 1 LOADED • " + name
                    }
                    .onFailure { error -> localStatus = "S006 | FILE 1 ERROR • " + (error.message ?: "Could not read file") }
            }
        }
    }
    val greeksPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { runCatching { context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() } ?: "" }
            .onSuccess { text ->
                greeksText = text; greeksName = uri.lastPathSegment ?: "Greeks CSV"
                s006Prefs.edit().putString("date", s006Today).putString("greeks_text", text).putString("greeks_name", greeksName).apply()
            } }
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
        item { Header("Strategies", "Each strategy has its own selectable tab and isolated rules") }; item { StrategySelector(selected, onSelect) }; item { Text("MetaApi trading symbol: $tradeSymbol", color = Muted, fontSize = 10.sp) }
        when (selected) {
            TradingEngine.StrategyId.STRATEGY_001 -> { item { Field("FlashAlpha API key", key, { key = it }, true) }; item { Field("FlashAlpha symbol", symbol, { symbol = it; onFlashSymbol(it) }) }; item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Button(onClick = { onSave(saved.copy(flashAlphaKey = key)) }, modifier = Modifier.weight(1f)) { Text("SAVE KEY") }; Button(onClick = { onRun(!running) }, modifier = Modifier.weight(1f)) { Text(if (running) "STOP" else "START") } } }; if (running) item { Button(onClick = { onArm(!armed) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (armed) Red else Green)) { Text(if (armed) "DISARM LIVE TRADING" else "ARM LIVE TRADING") } }; item { CardBlock { Text("STRATEGY 001 • GEX / QOF DATA ZONES", color = Cyan, fontSize = 16.sp, fontWeight = FontWeight.Black); Text("London + New York sessions. FlashAlpha GEX/GREEKS determine entry and exit zones.", color = Muted, fontSize = 10.sp) } } }
            TradingEngine.StrategyId.STRATEGY_002 -> { item { Button(onClick = { onRun(!running) }, modifier = Modifier.fillMaxWidth()) { Text(if (running) "STOP" else "START") } }; if (running) item { Button(onClick = { onArm(!armed) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (armed) Red else Green)) { Text(if (armed) "DISARM LIVE TRADING" else "ARM LIVE TRADING") } }; item { CardBlock { Text("STRATEGY 002 • VELOCITY EXPANSION", color = Cyan, fontSize = 16.sp, fontWeight = FontWeight.Black); Text("Independent tick-to-tick engine. Uses its own trailing/reversal rules and does not consume S001 GEX logic.", color = Muted, fontSize = 10.sp) } } }
            TradingEngine.StrategyId.STRATEGY_003 -> { item { CardBlock { Text("STRATEGY 003 • SMART MONEY CONCEPTS", color = Cyan, fontSize = 16.sp, fontWeight = FontWeight.Black); Text("Reads 1H, 15M, 5M and 1M market structure; execution is locked to the 5M timeframe.", color = TextMain, fontSize = 10.sp); Text("HTF ${smcPlan.h1Bias.name}  •  15M ${smcPlan.m15Bias.name}  •  5M ${smcPlan.m5Bias.name}  •  1M ${smcPlan.m1Bias.name}", color = Green, fontSize = 10.sp); Text("${smcPlan.bos} • ${smcPlan.liquidity} • ${smcPlan.fvg} • ${smcPlan.orderBlock} • ${smcPlan.premiumDiscount}", color = Muted, fontSize = 9.sp); Text("${smcPlan.confidence}% • ${smcPlan.reason}", color = Muted, fontSize = 9.sp) } }; item { Button(onClick = { onRun(!running) }, modifier = Modifier.fillMaxWidth()) { Text(if (running) "STOP" else "START 5M SMC") } }; if (running) item { Button(onClick = { onArm(!armed) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (armed) Red else Green)) { Text(if (armed) "DISARM LIVE TRADING" else "ARM LIVE TRADING") } } }
            TradingEngine.StrategyId.STRATEGY_004 -> { item { CardBlock { Text("STRATEGY 004 • PURE PRICE ACTION", color = Cyan, fontSize = 16.sp, fontWeight = FontWeight.Black); Text("15M context → 5M liquidity sweep, rejection and displacement → 1M BOS execution. OHLC only.", color = TextMain, fontSize = 10.sp); Text("15M ${priceActionPlan.contextBias.name} • 5M ${priceActionPlan.setupState.name} • ${priceActionPlan.bos}", color = Green, fontSize = 10.sp); Text("${priceActionPlan.sweptLiquidity} • ${priceActionPlan.targetLiquidity}", color = Muted, fontSize = 9.sp); Text("Entry ${priceActionPlan.entry ?: "—"} • SL ${priceActionPlan.stop ?: "—"} • TP ${priceActionPlan.target ?: "—"}", color = Muted, fontSize = 9.sp); Text("${priceActionPlan.confidence}% • ${priceActionPlan.reason}", color = Muted, fontSize = 9.sp) } }; item { Button(onClick = { onRun(!running) }, modifier = Modifier.fillMaxWidth()) { Text(if (running) "STOP" else "START PURE PRICE ACTION") } }; if (running) item { Button(onClick = { onArm(!armed) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (armed) Red else Green)) { Text(if (armed) "DISARM LIVE TRADING" else "ARM LIVE TRADING") } } }
            TradingEngine.StrategyId.STRATEGY_005 -> { item { CardBlock { Text("STRATEGY 005 • WOODIE 4H / 5M PRICE ACTION", color = Cyan, fontSize = 16.sp, fontWeight = FontWeight.Black); Text("Previous completed 4H Woodie pivots → 5M rejection entries at S1/S2 or R1/R2 → exit at 4H PP. MetaApi stream supplies live ticks; no S001–S004 logic is used.", color = TextMain, fontSize = 10.sp); Text("PP ${woodiePlan.levels?.pp?.let { String.format("%.2f", it) } ?: "—"} • R1 ${woodiePlan.levels?.r1?.let { String.format("%.2f", it) } ?: "—"} • S1 ${woodiePlan.levels?.s1?.let { String.format("%.2f", it) } ?: "—"}", color = Green, fontSize = 10.sp); Text("Trigger ${woodiePlan.trigger.ifBlank { "WAIT" }} • Entry ${woodiePlan.entry?.let { String.format("%.2f", it) } ?: "—"} • SL ${woodiePlan.stop?.let { String.format("%.2f", it) } ?: "—"} • PP exit ${woodiePlan.target?.let { String.format("%.2f", it) } ?: "—"}", color = Muted, fontSize = 9.sp); Text("Risk cap: 5% of account balance • RR ${String.format("%.2f", woodiePlan.rewardRisk)} • ${woodiePlan.reason}", color = Muted, fontSize = 9.sp) } }; item { Button(onClick = { onRun(!running) }, modifier = Modifier.fillMaxWidth()) { Text(if (running) "STOP" else "START WOODIE 4H / 5M") } }; if (running) item { Button(onClick = { onArm(!armed) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (armed) Red else Green)) { Text(if (armed) "DISARM LIVE TRADING" else "ARM LIVE TRADING") } } }
            TradingEngine.StrategyId.STRATEGY_006 -> {
                item {
                    CardBlock {
                        Text("STRATEGY 006 • OPTIONS FLOW", color = Cyan, fontSize = 16.sp, fontWeight = FontWeight.Black)
                        Text("Independent daily options map. Load the two files at London open; live MT5 price reacts to the calculated zones.", color = TextMain, fontSize = 10.sp)
                        Button(onClick = { barchartPicker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { Text("LOAD FILE 1 • MHT / PDF / SCREENSHOT") }
                        Text(barchartName, color = Muted, fontSize = 9.sp)
                        Button(onClick = { greeksPicker.launch("text/*") }, modifier = Modifier.fillMaxWidth()) { Text("LOAD GREEKS / VOLATILITY CSV") }
                        Text(greeksName, color = Muted, fontSize = 9.sp)
                        Button(enabled = barchartText.isNotBlank() && greeksText.isNotBlank(), onClick = {
                            optionsFlow.loadFiles(barchartText, greeksText, snapshot?.prices?.get(tradeSymbol)?.let { q -> (q.bid + q.ask) / 2.0 })
                        }, modifier = Modifier.fillMaxWidth()) { Text("BUILD OPTIONS FLOW MAP") }
                        val m = optionsFlow.currentMap()
                        Text("MAP: " + if (m == null) "WAITING FOR BOTH FILES" else "CALCULATED • QOF " + String.format("%.1f", m.qof) + " • " + m.bias, color = if (m?.valid == true) Green else Muted, fontSize = 10.sp)
                        Text("PRICE MAP — calculated from loaded options OI + gamma", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        m?.zones?.let { z ->
                            Text("1  Upper Inventory Ceiling: " + (z.upperInventoryCeiling?.let { String.format("%.2f", it) } ?: "—"), color = Muted, fontSize = 9.sp)
                            Text("2  Reclaim Gate: " + (z.reclaimGate?.let { String.format("%.2f", it) } ?: "—"), color = Muted, fontSize = 9.sp)
                            Text("3  Immediate Hedge Wall: " + (z.immediateHedgeWall?.let { String.format("%.2f", it) } ?: "—"), color = Muted, fontSize = 9.sp)
                            Text("4  Primary Hedge Floor: " + (z.primaryHedgeFloor?.let { String.format("%.2f", it) } ?: "—"), color = Muted, fontSize = 9.sp)
                            Text("5  Call Wall: " + (z.callWall?.let { String.format("%.2f", it) } ?: "—"), color = Muted, fontSize = 9.sp)
                            Text("6  Put Wall: " + (z.putWall?.let { String.format("%.2f", it) } ?: "—"), color = Muted, fontSize = 9.sp)
                            Text("7  Gamma Flip: " + (z.gammaFlip?.let { String.format("%.2f", it) } ?: "—"), color = Muted, fontSize = 9.sp)
                            Text("8  Positive GEX Region: " + (z.positiveGexRegion?.let { String.format("%.2f → %.2f", it.first, it.second) } ?: "—"), color = Muted, fontSize = 9.sp)
                            Text("9  Negative GEX Region: " + (z.negativeGexRegion?.let { String.format("%.2f → %.2f", it.first, it.second) } ?: "—"), color = Muted, fontSize = 9.sp)
                            Text("10 Dealer Absorption Shelf: " + (z.dealerAbsorptionShelf?.let { String.format("%.2f", it) } ?: "—"), color = Green, fontSize = 9.sp)
                            Text("11 Liquidity Exhaustion Floor: " + (z.liquidityExhaustionFloor?.let { String.format("%.2f", it) } ?: "—"), color = Green, fontSize = 9.sp)
                        }
                        Text("FILES: cached locally for this Nairobi trading day — leaving the Strategy screen does not clear them. A new day requires new input.", color = TextMain, fontSize = 9.sp)
                        Text("M5 confirmation: completed candle must trade into a mapped zone and close back across it. Batch risk: 10% combined • Exit: nearest opposing zone.", color = TextMain, fontSize = 9.sp)
                    }
                }
                item { Button(onClick = { onRun(!running) }, modifier = Modifier.fillMaxWidth()) { Text(if (running) "STOP" else "START OPTIONS FLOW") } }
                if (running) item { Button(onClick = { onArm(!armed) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (armed) Red else Green)) { Text(if (armed) "DISARM LIVE TRADING" else "ARM LIVE TRADING") } }
            }
        }
        item { CardBlock { Text("STATUS", color = Muted, fontSize = 9.sp); Text(localStatus, color = TextMain, fontSize = 10.sp) } }
    }
}

@Composable private fun StrategySelector(selected: TradingEngine.StrategyId, onSelect: (TradingEngine.StrategyId) -> Unit) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { StrategySelectorButton("001", "GEX", selected == TradingEngine.StrategyId.STRATEGY_001) { onSelect(TradingEngine.StrategyId.STRATEGY_001) }; StrategySelectorButton("002", "VELOCITY", selected == TradingEngine.StrategyId.STRATEGY_002) { onSelect(TradingEngine.StrategyId.STRATEGY_002) }; StrategySelectorButton("003", "SMC", selected == TradingEngine.StrategyId.STRATEGY_003) { onSelect(TradingEngine.StrategyId.STRATEGY_003) }; StrategySelectorButton("004", "PRICE ACTION", selected == TradingEngine.StrategyId.STRATEGY_004) { onSelect(TradingEngine.StrategyId.STRATEGY_004) }; StrategySelectorButton("005", "WOODIE", selected == TradingEngine.StrategyId.STRATEGY_005) { onSelect(TradingEngine.StrategyId.STRATEGY_005) }; StrategySelectorButton("006", "OPTIONS FLOW", selected == TradingEngine.StrategyId.STRATEGY_006) { onSelect(TradingEngine.StrategyId.STRATEGY_006) } } }
@Composable private fun RowScope.StrategySelectorButton(id: String, name: String, selected: Boolean, onClick: () -> Unit) { Surface(color = if (selected) Cyan.copy(.14f) else Panel, shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp), modifier = Modifier.weight(1f).clickable { onClick() }) { Text("$id\n$name", color = if (selected) Cyan else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = TextAlign.Center) } }

@Composable private fun WatchlistTab(modifier: Modifier, saved: SavedConnection, snapshot: MetaSnapshot?, selected: String, onSave: (String) -> Unit) { var text by remember(saved.watchlist) { mutableStateOf(saved.watchlist) }; LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 18.dp)) { item { Header("Market Watchlist", "Actual MetaApi symbols — stored locally") }; item { Field("Comma-separated MetaApi symbols", text, { text = it }) }; item { Button(onClick = { onSave(text) }, modifier = Modifier.fillMaxWidth()) { Text("SAVE WATCHLIST") } }; item { Text("Selected: $selected", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 10.sp) }; snapshot?.prices?.forEach { (symbol, tick) -> item { CardBlock { Text(symbol, color = TextMain, fontWeight = FontWeight.Bold); Text("Bid ${number(tick.bid)} • Ask ${number(tick.ask)} • tick ${tick.time}", color = Muted, fontSize = 10.sp) } } } } }
@Composable private fun Field(label: String, value: String, onValue: (String) -> Unit, password: Boolean = false) { OutlinedTextField(value = value, onValueChange = onValue, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None) }
@Composable private fun CardBlock(content: @Composable ColumnScope.() -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content) } }
private fun number(value: Double): String = if (value.isFinite()) String.format("%.4f", value) else "—"

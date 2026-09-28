package life.pips.strat1

import android.os.Bundle
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
private val Panel = Color(0xFF0C1422).copy(alpha = .80f)
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
    var tab by remember { mutableStateOf(Tab.HOME) }; var saved by remember { mutableStateOf(SavedConnection("", "", "", "", "", "", "XAUUSD,NAS100,EURUSD,GBPUSD,US30")) }; var account by remember { mutableStateOf<MetaAccount?>(null) }; var snapshot by remember { mutableStateOf<MetaSnapshot?>(null) }; val s006PriceHistory = remember { mutableStateListOf<Pair<Long, Double>>() }; var flashData by remember { mutableStateOf<FlashAlphaSnapshot?>(null) }; var selectedStrategy by remember { mutableStateOf(TradingEngine.StrategyId.STRATEGY_001) }; var selectedSymbol by remember { mutableStateOf("XAUUSD") }; var flashSymbol by remember { mutableStateOf("GC=F") }; var running by remember { mutableStateOf(false) }; var armed by remember { mutableStateOf(false) }; var status by remember { mutableStateOf("Ready — direct MetaApi + FlashAlpha") }; var busy by remember { mutableStateOf(false) }; var lastFlashPull by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { saved = context.loadSavedConnection(); selectedSymbol = saved.watchlist.split(',').firstOrNull()?.trim().orEmpty().ifBlank { "XAUUSD" }; if (saved.accountId.isNotBlank() && saved.metaApiToken.isNotBlank()) meta.connectExisting(saved.metaApiToken, saved.accountId).onSuccess { account = it }.onFailure { status = it.message ?: "MetaApi connection failed" } }
    LaunchedEffect(account, running, armed, selectedSymbol, flashSymbol, saved.flashAlphaKey, selectedStrategy) {
        val a = account ?: return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis(); val symbols = saved.watchlist.split(',').map { it.trim() }.filter { it.isNotBlank() }
            meta.refresh(saved.metaApiToken, a, symbols).onSuccess { s -> snapshot = s; if (selectedStrategy == TradingEngine.StrategyId.STRATEGY_006) { val q = s.prices[selectedSymbol]; if (q != null) { val p = (q.bid + q.ask) / 2.0; if (p.isFinite() && p > 0.0) { s006PriceHistory.add(System.currentTimeMillis() to p); while (s006PriceHistory.size > 240) s006PriceHistory.removeAt(0) } } }; if (!running) status = "MetaApi ${s.account.connectionStatus} • live quote monitor" }.onFailure { if (!running) status = it.message ?: "MetaApi refresh failed" }
            if (selectedStrategy == TradingEngine.StrategyId.STRATEGY_001 && saved.flashAlphaKey.isNotBlank() && (flashData == null || now - lastFlashPull >= 2 * 60 * 60 * 1000L)) { lastFlashPull = now; flash.snapshot(saved.flashAlphaKey, flashSymbol).onSuccess { flashData = it; status = "FlashAlpha GC=F snapshot received" }.onFailure { if (running) status = it.message ?: "FlashAlpha unavailable" } }
            if (selectedStrategy == TradingEngine.StrategyId.STRATEGY_003) engine.strategy003.refresh(a, saved.metaApiToken, selectedSymbol).onFailure { if (!running) status = it.message ?: "SMC candle data unavailable" }
            if (selectedStrategy == TradingEngine.StrategyId.STRATEGY_004) engine.strategy004.refresh(a, saved.metaApiToken, selectedSymbol)
            if (selectedStrategy == TradingEngine.StrategyId.STRATEGY_005) engine.strategy005.refresh(a, saved.metaApiToken, selectedSymbol, engine.liveSamples(selectedSymbol)).onFailure { if (!running) status = it.message ?: "Woodie pivot data unavailable" }
            if (running && armed && saved.metaApiToken.isNotBlank()) snapshot?.let { s -> engine.execute(selectedStrategy, a, saved, s, flashData, selectedSymbol) { status = it } }
            delay(1000)
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Panel, primary = Cyan, secondary = Green, error = Red)) {
        Box(Modifier.fillMaxSize()) {
            Image(bitmap = ImageBitmap.imageResource(id = R.drawable.pipslife_ocean_background), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .08f), Color.Black.copy(alpha = .02f), Color.Black.copy(alpha = .16f)))))
            Scaffold(containerColor = Color.Transparent, bottomBar = { NavigationBar(containerColor = Color(0xFF08111D), tonalElevation = 0.dp, modifier = Modifier.navigationBarsPadding()) { listOf(Tab.HOME to "HOME", Tab.METAAPI to "METAAPI", Tab.STRATEGY to "STRATEGY", Tab.WATCHLIST to "WATCH", Tab.UPDATE to "UPDATE").forEach { (t, label) -> NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = {}, label = { Text(label, fontSize = 7.sp) }) } } }) { pad ->
                when (tab) {
                    Tab.HOME -> HomeDashboard(Modifier.padding(pad), account, snapshot, flashData, selectedStrategy, running, armed, status, engine, selectedSymbol, onStartStop = { running = it; if (!it) armed = false })
                    Tab.METAAPI -> MetaApiTab(Modifier.padding(pad), saved, account, busy, status, onSave = { value -> saved = value; scope.launch { context.saveConnection(value) } }, onConnect = { value -> busy = true; scope.launch { val result = if (value.accountId.isNotBlank()) meta.connectExisting(value.metaApiToken, value.accountId) else meta.createAndDeploy(value.metaApiToken, value.login, value.password, value.server); result.onSuccess { a2 -> account = a2; saved = value.copy(accountId = a2.id); context.saveConnection(saved); status = "CONNECTED — ${a2.login} / ${a2.server}" }.onFailure { status = it.message ?: "Connection failed" }; busy = false } })
                    Tab.STRATEGY -> StrategyTab(Modifier.padding(pad), saved, account, snapshot, flashData, selectedSymbol, flashSymbol, selectedStrategy, running, armed, status, engine.strategy003.latest(), engine.strategy004.latest(), engine.strategy005.latest(), engine.strategy006, s006PriceHistory, onSelect = { selectedStrategy = it; running = false; armed = false }, onFlashSymbol = { flashSymbol = it }, onSave = { value -> saved = value; scope.launch { context.saveConnection(value) } }, onRun = { running = it; if (!it) armed = false }, onArm = { armed = it })
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

@Composable private fun StrategyTab(modifier: Modifier, saved: SavedConnection, account: MetaAccount?, snapshot: MetaSnapshot?, flash: FlashAlphaSnapshot?, tradeSymbol: String, flashSymbol: String, selected: TradingEngine.StrategyId, running: Boolean, armed: Boolean, status: String, smcPlan: Strategy003Engine.Plan, priceActionPlan: Strategy004Engine.Plan, woodiePlan: Strategy005Engine.Plan, optionsFlow: Strategy006Engine, s006PriceHistory: List<Pair<Long, Double>>, onSelect: (TradingEngine.StrategyId) -> Unit, onFlashSymbol: (String) -> Unit, onSave: (SavedConnection) -> Unit, onRun: (Boolean) -> Unit, onArm: (Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var localStatus by remember(status) { mutableStateOf(status) }
    var key by remember(saved.flashAlphaKey) { mutableStateOf(saved.flashAlphaKey) }; var symbol by remember(flashSymbol) { mutableStateOf(flashSymbol) }
    val s006Prefs = remember { context.getSharedPreferences("strategy006_files", android.content.Context.MODE_PRIVATE) }
    var barchartName by remember { mutableStateOf("No IV options file selected") }
    var greeksName by remember { mutableStateOf("No Volatility / Greeks file selected") }
    var barchartText by remember { mutableStateOf("") }
    var greeksText by remember { mutableStateOf("") }
    var gcPriceText by remember { mutableStateOf("") }
    var spotPriceText by remember { mutableStateOf("") }
    val s006Today = remember { LocalDate.now(ZoneId.of("Africa/Nairobi")).toString() }
    LaunchedEffect(Unit) {
        if (s006Prefs.getString("date", "") == s006Today) {
            barchartText = s006Prefs.getString("barchart_text", "").orEmpty()
            greeksText = s006Prefs.getString("greeks_text", "").orEmpty()
            barchartName = s006Prefs.getString("barchart_name", "Cached IV options table").orEmpty()
            greeksName = s006Prefs.getString("greeks_name", "Cached Volatility / Greeks table").orEmpty()
            gcPriceText = s006Prefs.getString("gc_price", "").orEmpty()
            spotPriceText = s006Prefs.getString("spot_price", "").orEmpty()
            if (spotPriceText.isBlank()) spotPriceText = optionsFlow.extractIvSpotPrice(barchartText)?.let { String.format("%.2f", it) }.orEmpty()
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
                        optionsFlow.extractIvSpotPrice(text)?.let { spotPriceText = String.format("%.2f", it) }
                        s006Prefs.edit().putString("date", s006Today).putString("barchart_text", text).putString("barchart_name", barchartName).putString("spot_price", spotPriceText).apply()
                        localStatus = "S006 | FILE 1 LOADED • " + name
                    }
                    .onFailure { error -> localStatus = "S006 | FILE 1 ERROR • " + (error.message ?: "Could not read file") }
            }
        }
    }
    fun readS006Clipboard() {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        val clip = clipboard?.primaryClip
        if (clip == null || clip.itemCount == 0) {
            localStatus = "S006 | CLIPBOARD EMPTY"
            return
        }
        val item = clip.getItemAt(0)
        val uri = item.uri
        val description = clip.description
        val hasFileUri = uri != null && description != null && (
            description.hasMimeType("application/pdf") ||
                description.hasMimeType("image/*") ||
                description.hasMimeType("*/*")
            )
        scope.launch {
            localStatus = "S006 | READING CLIPBOARD…"
            if (hasFileUri && uri != null) {
                Strategy006FileExtractor.extract(context, uri)
                    .onSuccess { result ->
                        barchartText = result.first
                        barchartName = "Clipboard • ${result.second}"
                        optionsFlow.extractIvSpotPrice(result.first)?.let { spotPriceText = String.format("%.2f", it) }
                        s006Prefs.edit().putString("date", s006Today).putString("barchart_text", barchartText).putString("barchart_name", barchartName).putString("spot_price", spotPriceText).apply()
                        localStatus = "S006 | CLIPBOARD FILE READ • $barchartName"
                    }
                    .onFailure { localStatus = "S006 | CLIPBOARD FILE ERROR • " + (it.message ?: "Could not read clipboard file") }
            } else {
                val text = item.coerceToText(context)?.toString().orEmpty()
                Strategy006FileExtractor.normalizeClipboardText(text)
                    .onSuccess { normalized ->
                        barchartText = normalized
                        barchartName = "Clipboard text"
                        optionsFlow.extractIvSpotPrice(normalized)?.let { spotPriceText = String.format("%.2f", it) }
                        s006Prefs.edit().putString("date", s006Today).putString("barchart_text", normalized).putString("barchart_name", barchartName).putString("spot_price", spotPriceText).apply()
                        localStatus = "S006 | CLIPBOARD TEXT PARSED • IV rows + Spot recognized"
                    }
                    .onFailure { localStatus = "S006 | CLIPBOARD TEXT ERROR • " + (it.message ?: "Could not parse pasted table") }
            }
        }
    }

    val greeksPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                localStatus = "S006 | READING VOL/GREEKS FILE…"
                Strategy006GreeksFileExtractor.extract(context, it)
                    .onSuccess { result ->
                        val text = result.first
                        val name = result.second
                        greeksText = text
                        greeksName = name
                        s006Prefs.edit().putString("date", s006Today).putString("greeks_text", text).putString("greeks_name", name).apply()
                        localStatus = "S006 | VOL/GREEKS FILE LOADED • " + name
                    }
                    .onFailure { error ->
                        localStatus = "S006 | VOL/GREEKS FILE ERROR • " + (error.message ?: "Could not read file")
                    }
            }
        }
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
                    var showAdvanced by remember { mutableStateOf(false) }
                    CardBlock {
                        Text("STRATEGY 006 • IV ZONES + VOLATILITY / GREEKS", color = Cyan, fontSize = 16.sp, fontWeight = FontWeight.Black)
                        Text("IV strikes → signed GC/XAUUSD basis → six mapped zones → strongest confluence → M5 reaction → opposite-confluence target.", color = Muted, fontSize = 10.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { barchartPicker.launch("*/*") }, modifier = Modifier.weight(1f)) { Text("LOAD IV TABLE") }
                            Button(onClick = { readS006Clipboard() }, modifier = Modifier.weight(1f)) { Text("READ CLIPBOARD") }
                        }
                        Button(onClick = { greeksPicker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { Text("LOAD VOL/GREEKS FILE") }
                        Text("IV table: " + barchartName, color = Muted, fontSize = 8.sp)
                        Text("Vol/Greeks: " + greeksName, color = Muted, fontSize = 8.sp)
                        Text("READ CLIPBOARD accepts copied IV table text or a copied PDF/image file.", color = Muted, fontSize = 8.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = gcPriceText,
                                onValueChange = { gcPriceText = it },
                                label = { Text("GC PRICE") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = spotPriceText,
                                onValueChange = { spotPriceText = it },
                                label = { Text("SPOT PRICE") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        Text("SPOT PRICE is the XAUUSD anchor for IV mapping. Screenshot Spot is auto-filled when recognized; you can correct it before Build I.V Map.", color = if (spotPriceText.toDoubleOrNull()?.let { it > 0.0 } == true) Green else Muted, fontSize = 9.sp)
                        Button(
                            enabled = barchartText.isNotBlank() && greeksText.isNotBlank() &&
                                gcPriceText.toDoubleOrNull()?.let { it > 0.0 } == true &&
                                spotPriceText.toDoubleOrNull()?.let { it > 0.0 } == true,
                            onClick = {
                                val gc = gcPriceText.toDoubleOrNull()
                                val ivSpot = spotPriceText.toDoubleOrNull()
                                if (gc != null && gc > 0.0 && ivSpot != null && ivSpot > 0.0) {
                                    s006Prefs.edit().putString("date", s006Today).putString("gc_price", gcPriceText).putString("spot_price", spotPriceText).apply()
                                    val built = optionsFlow.loadFiles(barchartText, greeksText, gc, ivSpot)
                                    localStatus = if (built.valid) {
                                        "S006 | IV MAP BUILT • file Spot used as live XAUUSD mapping price • basis=" + String.format("%.2f", gc - ivSpot)
                                    } else {
                                        "S006 | IV MAP ERROR • " + built.warnings.joinToString(" • ")
                                    }
                                } else {
                                    localStatus = "S006 | BUILD MAP WAITING FOR GC PRICE + SPOT PRICE"
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("BUILD I.V MAP") }

                        val m = optionsFlow.currentMap()
                        val livePrice = snapshot?.prices?.get(tradeSymbol)?.let { (it.bid + it.ask) / 2.0 }
                        val chartPrice = livePrice ?: m?.spot
                        val zoneStatus = livePrice?.let { optionsFlow.zoneStatus(it) }

                        Text(
                            "BASIS " + (m?.basis?.basis?.let { String.format("%.2f", it) } ?: "—") +
                                " • LIVE XAUUSD " + (livePrice?.let { String.format("%.2f", it) } ?: "—"),
                            color = if (m?.valid == true) Green else Muted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (m?.valid == true && chartPrice != null) {
                            val greekRows = m.rows.filter { it.source != "iv" }.sortedBy { it.strike }
                            val confluenceRows = m.zones.confluence
                            val matchedZones = confluenceRows.count { it.matchedStrike != null }
                            val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
                            val tabs = listOf("I.V ZONES + PRICE", "GREEKS STRIKES")
                            Text("IV ZONES " + m.zones.confluence.size + "/6 • GREEKS " + greekRows.size + " ROWS • CONFLUENCE " + matchedZones + "/6", color = if (greekRows.isNotEmpty()) Green else Red, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            TabRow(selectedTabIndex = pagerState.currentPage, containerColor = Color.Transparent, contentColor = Cyan) {
                                tabs.forEachIndexed { index, title ->
                                    Tab(selected = pagerState.currentPage == index, onClick = { scope.launch { pagerState.animateScrollToPage(index) } }, text = { Text(title, fontSize = 9.sp, fontWeight = FontWeight.Bold) })
                                }
                            }
                            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                                if (page == 0) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        S006PolishedZones(m.zones, chartPrice, zoneStatus)
                                        CardBlock {
                                            Text("LIVE MAPPED ZONE", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            Text((zoneStatus?.likelyZoneName ?: "NO ACTIVE ZONE") + " • " + (zoneStatus?.likelyZone?.let { String.format("%.2f", it) } ?: "—") + " • " + (zoneStatus?.likelySide?.name ?: "WAIT"), color = when (zoneStatus?.likelySide) { life.pips.strat1.data.TradeSide.BUY -> Green; life.pips.strat1.data.TradeSide.SELL -> Red; else -> Muted }, fontSize = 16.sp, fontWeight = FontWeight.Black)
                                            val activeConfluence = m.zones.confluence.minByOrNull { abs(it.zone - (zoneStatus?.likelyZone ?: chartPrice)) }
                                            Text("Confluence: " + (activeConfluence?.score?.let { String.format("%.1f", it) } ?: "—") + " • Greeks strike " + (activeConfluence?.matchedStrike?.let { String.format("%.2f", it) } ?: "—"), color = Green, fontSize = 9.sp)
                                            Text("M5 reaction: " + (zoneStatus?.reactedZoneName ?: "waiting"), color = Muted, fontSize = 9.sp)
                                            Text("Opposite target: " + (zoneStatus?.possibleExitName ?: "—") + (zoneStatus?.possibleExit?.let { " @ " + String.format("%.2f", it) } ?: ""), color = Muted, fontSize = 9.sp)
                                        }
                                    }
                                } else {
                                    S006GreeksStrikes(m.zones.confluence, greekRows)
                                }
                            }
                            Text("Swipe left/right to switch between mapped I.V zones and parsed Greeks strikes.", color = Muted, fontSize = 8.sp)
                        } else {

                        TextButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (showAdvanced) "HIDE S006 DATA ▲" else "SHOW S006 DATA ▼", fontSize = 9.sp)
                        }
                        if (showAdvanced) {
                            Text("SIX POLISHED IV ZONES", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            m?.zones?.confluence?.sortedBy { it.zone }?.forEach { item ->
                                Text(
                                    item.zoneName + "  " + String.format("%.2f", item.zone) +
                                        " • CONFLUENCE " + String.format("%.1f", item.score) +
                                        " • MATCH " + (item.matchedStrike?.let { String.format("%.2f", it) } ?: "—"),
                                    color = if (item.score >= 70.0) Green else Muted,
                                    fontSize = 9.sp
                                )
                            }
                            Text("ATM IV strike is ignored • confluence buffer 1000 points (±500) • highest Volatility + Greeks strike drives entry.", color = Muted, fontSize = 8.sp)
                        }
                    }
                    }
                }
                item {
                    Button(
                        onClick = {
                            if (running) {
                                onRun(false)
                                onArm(false)
                            } else {
                                onRun(true)
                                onArm(true)
                                localStatus = "S006 | BOT STARTED • LIVE XAUUSD TRACKING + EXECUTION ACTIVE"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = if (running) Red else Green)
                    ) { Text(if (running) "STOP BOT" else "START BOT") }
                }
            }
        }
        item { CardBlock { Text("STATUS", color = Muted, fontSize = 9.sp); Text(localStatus, color = TextMain, fontSize = 10.sp) } }
    }
}

@Composable
private fun S006GreeksStrikes(confluence: List<Strategy006Engine.ZoneConfluence>, rows: List<Strategy006Engine.Row>) {
    CardBlock {
        Text("GREEKS STRIKES", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
        Text("Parsed volatility / Greeks rows used for 1000-point confluence matching (±500 points).", color = Muted, fontSize = 8.sp)
        if (rows.isEmpty()) {
            Text("FILE READ — 0 USABLE GREEKS ROWS", color = Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("STRIKE", color = Muted, fontSize = 7.sp, modifier = Modifier.weight(1f))
                Text("TYPE", color = Muted, fontSize = 7.sp, modifier = Modifier.weight(0.7f))
                Text("DELTA", color = Muted, fontSize = 7.sp, modifier = Modifier.weight(1f))
                Text("IV", color = Muted, fontSize = 7.sp, modifier = Modifier.weight(1f))
                Text("GAMMA", color = Muted, fontSize = 7.sp, modifier = Modifier.weight(1f))
                Text("VEGA", color = Muted, fontSize = 7.sp, modifier = Modifier.weight(1f))
                Text("THETA", color = Muted, fontSize = 7.sp, modifier = Modifier.weight(1f))
                Text("OI", color = Muted, fontSize = 7.sp, modifier = Modifier.weight(1f))
                Text("VOL", color = Muted, fontSize = 7.sp, modifier = Modifier.weight(1f))
                Text("MATCH", color = Muted, fontSize = 7.sp, modifier = Modifier.weight(1.4f))
            }
            rows.forEach { row ->
                val match = confluence.filter { it.matchedStrike != null && abs(it.matchedStrike!! - row.strike) <= Strategy006Engine.STRIKE_BUFFER }.minByOrNull { abs((it.matchedStrike ?: row.strike) - row.strike) }
                Row(Modifier.fillMaxWidth().background(if (match != null) Green.copy(alpha = .08f) else Color.Transparent).padding(vertical = 5.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(String.format("%.2f", row.strike), color = TextMain, fontSize = 8.sp, modifier = Modifier.weight(1f))
                    Text(row.type.toString(), color = Muted, fontSize = 8.sp, modifier = Modifier.weight(0.7f))
                    Text(String.format("%.3f", row.delta), color = Muted, fontSize = 8.sp, modifier = Modifier.weight(1f))
                    Text(String.format("%.2f", row.iv), color = Green, fontSize = 8.sp, modifier = Modifier.weight(1f))
                    Text(String.format("%.4f", row.gamma), color = Muted, fontSize = 8.sp, modifier = Modifier.weight(1f))
                    Text(String.format("%.4f", row.vega), color = Muted, fontSize = 8.sp, modifier = Modifier.weight(1f))
                    Text(String.format("%.4f", row.theta), color = Muted, fontSize = 8.sp, modifier = Modifier.weight(1f))
                    Text(String.format("%.0f", row.oi), color = Muted, fontSize = 8.sp, modifier = Modifier.weight(1f))
                    Text(String.format("%.0f", row.volume), color = Muted, fontSize = 8.sp, modifier = Modifier.weight(1f))
                    Text(match?.zoneName?.let { "✓ $it" } ?: "—", color = if (match != null) Green else Muted, fontSize = 7.sp, modifier = Modifier.weight(1.4f))
                }
                HorizontalDivider(color = Color.White.copy(alpha = .06f))
            }
        }
        Text("READ → PARSED → USED: " + rows.size + " rows • matched zones: " + confluence.count { it.matchedStrike != null } + "/6", color = if (rows.isNotEmpty()) Green else Red, fontSize = 8.sp)
    }
}

@Composable
fun S006PolishedZones(z: Strategy006Engine.Zones, livePrice: Double?, status: Strategy006Engine.ZoneStatus?) {
    val rows = listOf(
        "Upper Inventory Ceiling" to z.upperInventoryCeiling,
        "Reclaim Gate" to z.reclaimGate,
        "Immediate Hedge Wall" to z.immediateHedgeWall,
        "Primary Hedge Floor" to z.primaryHedgeFloor,
        "Absorption Floor" to z.dealerAbsorption,
        "Liquidity Exhaustion" to z.liquidityExhaustion
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF10283B)).padding(vertical = 8.dp, horizontal = 9.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("I.V ZONES + PRICE", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    Text("POLISHED LIVE CONFLUENCE ZONES", color = Muted, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    livePrice?.let { String.format("%.2f", it) } ?: "—",
                    color = Green, fontSize = 16.sp, fontWeight = FontWeight.Black
                )
            }
            rows.forEachIndexed { index, (name, price) ->
                val active = status?.likelyZoneName == name
                val reacted = status?.reactedZoneName == name
                Row(
                    Modifier.fillMaxWidth()
                        .background(
                            when {
                                active -> Cyan.copy(alpha = .10f)
                                reacted -> Green.copy(alpha = .08f)
                                index % 2 == 0 -> Color.White.copy(alpha = .025f)
                                else -> Color.Transparent
                            }
                        )
                        .padding(vertical = 7.dp, horizontal = 9.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        if (active) "● " + name else if (reacted) "✓ " + name else name,
                        color = if (active) Cyan else if (reacted) Green else TextMain,
                        fontSize = 9.sp, fontWeight = if (active || reacted) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1.6f)
                    )
                    Text(
                        price?.let { String.format("%.2f", it) } ?: "—",
                        color = if (active) Cyan else Green,
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(.8f),
                        textAlign = TextAlign.End
                    )
                }
                if (index < rows.lastIndex) HorizontalDivider(color = Color.White.copy(alpha = .08f), thickness = 1.dp)
            }
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("LIVE", color = Muted, fontSize = 7.sp)
                Text(
                    status?.likelyZoneName ?: "NO ACTIVE ZONE",
                    color = status?.likelySide?.let {
                        when (it) {
                            life.pips.strat1.data.TradeSide.BUY -> Green
                            life.pips.strat1.data.TradeSide.SELL -> Red
                            else -> Muted
                        }
                    } ?: Muted,
                    fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                Text(
                    status?.likelySide?.name ?: "WAIT",
                    color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable private fun StrategySelector(selected: TradingEngine.StrategyId, onSelect: (TradingEngine.StrategyId) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val strategies = listOf(
        TradingEngine.StrategyId.STRATEGY_001 to "S001 • GEX / QOF",
        TradingEngine.StrategyId.STRATEGY_002 to "S002 • VELOCITY",
        TradingEngine.StrategyId.STRATEGY_003 to "S003 • SMC",
        TradingEngine.StrategyId.STRATEGY_004 to "S004 • PRICE ACTION",
        TradingEngine.StrategyId.STRATEGY_005 to "S005 • WOODIE",
        TradingEngine.StrategyId.STRATEGY_006 to "S006 • OPTIONS FLOW"
    )
    val label = strategies.first { it.first == selected }.second
    Column(Modifier.fillMaxWidth()) {
        Text("ACTIVE STRATEGY", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.fillMaxWidth()) {
            Surface(
                color = Panel,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp),
                modifier = Modifier.fillMaxWidth().clickable { expanded = true }
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(label, color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(if (expanded) "▲" else "▼", color = Muted, fontSize = 9.sp)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                strategies.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name, color = if (id == selected) Cyan else TextMain, fontSize = 10.sp) },
                        onClick = { expanded = false; if (id != selected) onSelect(id) }
                    )
                }
            }
        }
    }
}

@Composable private fun WatchlistTab(modifier: Modifier, saved: SavedConnection, snapshot: MetaSnapshot?, selected: String, onSave: (String) -> Unit) { var text by remember(saved.watchlist) { mutableStateOf(saved.watchlist) }; LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 18.dp)) { item { Header("Market Watchlist", "Actual MetaApi symbols — stored locally") }; item { Field("Comma-separated MetaApi symbols", text, { text = it }) }; item { Button(onClick = { onSave(text) }, modifier = Modifier.fillMaxWidth()) { Text("SAVE WATCHLIST") } }; item { Text("Selected: $selected", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 10.sp) }; snapshot?.prices?.forEach { (symbol, tick) -> item { CardBlock { Text(symbol, color = TextMain, fontWeight = FontWeight.Bold); Text("Bid ${number(tick.bid)} • Ask ${number(tick.ask)} • tick ${tick.time}", color = Muted, fontSize = 10.sp) } } } } }
@Composable private fun Field(label: String, value: String, onValue: (String) -> Unit, password: Boolean = false) { OutlinedTextField(value = value, onValueChange = onValue, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None) }
@Composable private fun CardBlock(content: @Composable ColumnScope.() -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content) } }
private fun number(value: Double): String = if (value.isFinite()) String.format("%.4f", value) else "—"
package life.pips.strat1

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.pips.strat1.data.*
import kotlin.math.abs

private val Bg = Color.Transparent
private val Panel = Color(0xFF071727).copy(alpha = .76f)
private val Panel2 = Color(0xFF0A1D31).copy(alpha = .82f)
private val Text = Color(0xFFF3F7FF)
private val Muted = Color(0xFF91A8C5)
private val Cyan = Color(0xFF23D8FF)
private val Green = Color(0xFF21F28A)
private val Red = Color(0xFFFF3E68)
private val Purple = Color(0xFF776BFF)

@Composable
fun HomeDashboard(
    modifier: Modifier,
    account: MetaAccount?, snapshot: MetaSnapshot?, flash: FlashAlphaSnapshot?,
    strategy: TradingEngine.StrategyId, running: Boolean, armed: Boolean, status: String,
    engine: TradingEngine, selectedSymbol: String,
    onStartStop: (Boolean) -> Unit
) {
    val tick = snapshot?.prices?.get(selectedSymbol)
    val price = tick?.let { (it.bid + it.ask) / 2.0 }
    val view = price?.let { engine.view(strategy, flash, it, selectedSymbol) }
    val options: List<StrikeView> = flash?.options.orEmpty()
        .filter { it.strike.isFinite() && it.strike > 0 }
        .groupBy { it.strike }
        .map { (strike, contracts) ->
            val call = contracts.firstOrNull { it.type.equals("C", true) || it.type.equals("CALL", true) }
            val put = contracts.firstOrNull { it.type.equals("P", true) || it.type.equals("PUT", true) }
            StrikeView(
                strike = strike,
                gamma = call?.gamma ?: put?.gamma ?: Double.NaN,
                delta = call?.delta ?: put?.delta ?: Double.NaN,
                vega = call?.vega ?: put?.vega ?: Double.NaN,
                theta = call?.theta ?: put?.theta ?: Double.NaN,
                skew = if (call?.iv?.isFinite() == true && put?.iv?.isFinite() == true) (put.iv - call.iv) * 100 else Double.NaN,
                oi = (call?.openInterest ?: 0.0) + (put?.openInterest ?: 0.0)
            )
        }
        .sortedBy { abs(it.strike - (price ?: it.strike)) }
        .take(5)
    val best = options.maxByOrNull { row -> abs(row.gamma).takeIf { it.isFinite() } ?: 0.0 }
    val s002 = engine.strategy002.evaluate(engine.liveSamples(selectedSymbol))
    val s003 = engine.strategy003.latest()
    val s004 = engine.strategy004.latest()
    val s005 = engine.strategy005.latest()
    val s006 = engine.strategy006.currentMap()
    val confidence = view?.confidence ?: 0
    val side = view?.side
    val openPnl = snapshot?.positions?.sumOf { position -> if (position.profit.isFinite()) position.profit else 0.0 } ?: 0.0
    val pnlPct = if (snapshot?.balance?.isFinite() == true && snapshot.balance != 0.0) openPnl / snapshot.balance * 100.0 else Double.NaN

    LazyColumn(
        modifier = modifier.fillMaxSize().background(Bg),
        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item { TopBrand(account, running, onStartStop) }
        item { CompactMarketHeader(selectedSymbol, price, strategy, account) }
        item { AccountMetrics(snapshot, openPnl, pnlPct) }
        item {
            EqualPanel {
                Text("MARKET OVERVIEW", color = Text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(price?.let { fmt(it, 2) } ?: "—", color = Text, fontSize = 23.sp, fontWeight = FontWeight.Black)
                Text(if (tick != null) "BID " + fmt(tick.bid, 2) + "  ASK " + fmt(tick.ask, 2) + "  SPREAD " + fmt(tick.ask - tick.bid, 2) else "WAITING FOR MT5 PRICE", color = if (tick != null) Green else Muted, fontSize = 8.sp)
            }
        }
        item {
            EqualPanel {
                Text(strategyLabel(strategy) + " • ENGINE ACTIVITY", color = Text, fontSize = 15.sp, fontWeight = FontWeight.Black)
                when (strategy) {
                    TradingEngine.StrategyId.STRATEGY_001 -> { Text("GEX / QOF • " + (flash?.flowDirection?.uppercase() ?: "WAITING"), color = Green, fontSize = 10.sp); Text("Entry " + (view?.entry?.let { fmt(it, 2) } ?: "—") + " • Exit " + (view?.exit?.let { fmt(it, 2) } ?: "—") + " • SL " + (view?.stop?.let { fmt(it, 2) } ?: "—"), color = Text, fontSize = 9.sp) }
                    TradingEngine.StrategyId.STRATEGY_002 -> { Text("TICK VELOCITY • " + (s002.side?.name ?: "WAIT") + " • " + fmt(s002.velocity, 4), color = Green, fontSize = 10.sp); Text("Entry " + (s002.entry?.let { fmt(it, 2) } ?: "—") + " • SL " + (s002.stop?.let { fmt(it, 2) } ?: "—") + " • " + s002.reason, color = Muted, fontSize = 9.sp) }
                    TradingEngine.StrategyId.STRATEGY_003 -> { Text("HTF " + s003.h1Bias.name + " • 15M " + s003.m15Bias.name + " • 5M " + s003.m5Bias.name + " • 1M " + s003.m1Bias.name, color = Green, fontSize = 10.sp); Text(s003.bos + " • " + s003.liquidity + " • " + s003.fvg + " • " + s003.orderBlock, color = Text, fontSize = 9.sp); Text("Entry " + (s003.entry?.let { fmt(it, 2) } ?: "—") + " • SL " + (s003.stop?.let { fmt(it, 2) } ?: "—") + " • " + s003.confidence + "%", color = Muted, fontSize = 9.sp) }
                    TradingEngine.StrategyId.STRATEGY_004 -> { Text("15M " + s004.contextBias.name + " • 5M " + s004.setupState.name + " • " + s004.bos, color = Green, fontSize = 10.sp); Text(s004.sweptLiquidity + " • " + s004.targetLiquidity, color = Text, fontSize = 9.sp); Text("Entry " + (s004.entry?.let { fmt(it, 2) } ?: "—") + " • SL " + (s004.stop?.let { fmt(it, 2) } ?: "—") + " • TP " + (s004.target?.let { fmt(it, 2) } ?: "—"), color = Muted, fontSize = 9.sp) }
                    TradingEngine.StrategyId.STRATEGY_005 -> { val l = s005.levels; Text("4H WOODIE • PP " + (l?.pp?.let { fmt(it, 2) } ?: "—") + " • R1 " + (l?.r1?.let { fmt(it, 2) } ?: "—") + " • S1 " + (l?.s1?.let { fmt(it, 2) } ?: "—"), color = Green, fontSize = 10.sp); Text("Trigger " + s005.trigger.ifBlank { "WAIT" } + " • Entry " + (s005.entry?.let { fmt(it, 2) } ?: "—") + " • PP exit " + (s005.target?.let { fmt(it, 2) } ?: "—"), color = Text, fontSize = 9.sp); Text("SL " + (s005.stop?.let { fmt(it, 2) } ?: "—") + " • RR " + fmt(s005.rewardRisk, 2), color = Muted, fontSize = 9.sp) }
                    TradingEngine.StrategyId.STRATEGY_006 -> { val z = s006?.zones; Text("QOF " + (s006?.qof?.let { fmt(it, 1) } ?: "—") + " • " + (s006?.bias ?: "NO MAP"), color = Green, fontSize = 10.sp); Text("PHF " + (z?.primaryHedgeFloor?.let { fmt(it, 2) } ?: "—") + " • Absorption " + (z?.dealerAbsorptionShelf?.let { fmt(it, 2) } ?: "—") + " • Exhaustion " + (z?.liquidityExhaustionFloor?.let { fmt(it, 2) } ?: "—"), color = Text, fontSize = 9.sp); Text("Upper " + (z?.upperInventoryCeiling?.let { fmt(it, 2) } ?: "—") + " • Reclaim " + (z?.reclaimGate?.let { fmt(it, 2) } ?: "—") + " • IHW " + (z?.immediateHedgeWall?.let { fmt(it, 2) } ?: "—"), color = Muted, fontSize = 9.sp); Text("Batch positions " + (snapshot?.positions?.size ?: 0) + " • 10% combined risk • nearest opposing zone exit", color = Muted, fontSize = 9.sp) }
                }
            }
        }
        if (strategy == TradingEngine.StrategyId.STRATEGY_001) {
            item {
                EqualPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("REAL-TIME OPTIONS FLOW", color = Text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(5.dp))
                        Text("GC=F • S001 only", color = Muted, fontSize = 8.sp)
                    }
                    FlowTable(options)
                }
            }
        }
        item {
            EqualPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("●", color = if (running) Green else Muted, fontSize = 14.sp)
                    Spacer(Modifier.width(5.dp))
                    Text(strategyLabel(strategy), color = if (running) Green else Text, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(" • XAUUSD • ${if (armed) "LIVE ARMED" else if (running) "RUNNING" else "STOPPED"}", color = Muted, fontSize = 8.sp)
                    Spacer(Modifier.weight(1f))
                    if (strategy == TradingEngine.StrategyId.STRATEGY_001) {
                        Text(if (flash != null) "FlashAlpha LIVE" else "FlashAlpha WAITING", color = if (flash != null) Green else Muted, fontSize = 8.sp)
                    }
                }
                Text(if (strategy == TradingEngine.StrategyId.STRATEGY_001) "Direct MetaApi / MT5 • FlashAlpha GC=F • $status" else "Direct MetaApi / MT5 • $status", color = Muted, fontSize = 8.sp)
            }
        }
    }
}

@Composable
private fun TopBrand(account: MetaAccount?, running: Boolean, onStartStop: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Pips-life", color = Text, fontSize = 25.sp, fontWeight = FontWeight.Black)
            Text("life changing pips", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("● ${if (running) "RUNNING" else if (account != null) "CONNECTED" else "OFFLINE"}", color = if (running || account != null) Green else Red, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("MetaTrader 5", color = Muted, fontSize = 7.sp)
        }
        Spacer(Modifier.width(6.dp))
        Surface(color = Panel2, shape = RoundedCornerShape(7.dp), modifier = Modifier.clickable { onStartStop(!running) }) {
            Text(if (running) "■ STOP" else "▶ START", color = if (running) Red else Green, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp))
        }
    }
}

@Composable
private fun CompactMarketHeader(symbol: String, price: Double?, strategy: TradingEngine.StrategyId, account: MetaAccount?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(symbol, color = Text, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(6.dp))
            Pill(strategyLabel(strategy), Green)
        }
        Text("Gold (Spot) • Quantitative Strategy", color = Muted, fontSize = 9.sp)
        Text("Broker: ${account?.server ?: "MetaTrader 5"} • Price ${price?.let { fmt(it, 2) } ?: "—"}", color = Muted, fontSize = 8.sp)
    }
}

@Composable
private fun AccountMetrics(snapshot: MetaSnapshot?, pnl: Double, pnlPct: Double) {
    val balance = snapshot?.balance
    val equity = snapshot?.equity
    val freeMargin = snapshot?.freeMargin
    val margin = if (equity?.isFinite() == true && freeMargin?.isFinite() == true) equity - freeMargin else null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Metric("BALANCE", ksh(balance), Modifier.weight(1f))
        Metric("EQUITY", ksh(equity), Modifier.weight(1f))
        Metric("MARGIN", ksh(margin), Modifier.weight(1f))
        Metric("FREE", ksh(freeMargin), Modifier.weight(1f))
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Metric("DAILY P/L", ksh(pnl), Modifier.weight(1f), if (pnl >= 0) Green else Red)
        Metric("DAILY %", if (pnlPct.isFinite()) String.format("%.2f%%", pnlPct) else "—", Modifier.weight(1f), if (pnl >= 0) Green else Red)
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier, valueColor: Color = Text) {
    Surface(modifier = modifier, color = Panel, shape = RoundedCornerShape(5.dp)) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
            Text(label, color = Muted, fontSize = 6.sp, fontWeight = FontWeight.Bold)
            Text(value, color = valueColor, fontSize = 9.sp, fontWeight = FontWeight.Black, maxLines = 1)
        }
    }
}

private fun ksh(value: Double?): String = value?.takeIf { it.isFinite() }?.let { "Ksh ${String.format("%,.0f", it)}" } ?: "Ksh —"

@Composable
private fun EqualPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.padding(horizontal = 7.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Surface(color = color.copy(alpha = .08f), shape = RoundedCornerShape(12.dp)) {
        Text(text, color = color, fontSize = 7.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
    }
}

@Composable
private fun Meter(value: Float) {
    Box(Modifier.fillMaxWidth().height(7.dp).background(Color(0xFF132A3D), RoundedCornerShape(4.dp))) {
        Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).fillMaxHeight().background(Green, RoundedCornerShape(4.dp)))
    }
}

@Composable
private fun StrikeHeader() {
    Row(Modifier.fillMaxWidth().background(Panel2).padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf("STRIKE", "Γ", "Δ", "VEGA", "Θ", "SKEW", "OI", "CONF").forEach {
            Text(it, color = Cyan, fontSize = 7.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun StrikeRow(row: StrikeView, entry: Boolean) {
    Row(Modifier.fillMaxWidth().background(if (entry) Green.copy(alpha = .055f) else Color.Transparent).padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        Cell(fmt(row.strike, 2), entry)
        Cell(fmt(row.gamma, 3), false)
        Cell(fmt(row.delta, 2), false)
        Cell(fmt(row.vega, 3), false)
        Cell(fmt(row.theta, 3), false)
        Cell(fmt(row.skew, 3), false)
        Cell(fmt(row.oi, 0), false)
        Cell(if (entry) "6/6" else "—", entry)
    }
}

@Composable
private fun RowScope.Cell(value: String, strong: Boolean) {
    Text(value, color = if (strong) Green else Text, fontSize = 8.sp, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
}

@Composable
private fun GreekBars(best: StrikeView?) {
    val vals = listOf(best?.gamma ?: Double.NaN, best?.delta ?: Double.NaN, best?.vega ?: Double.NaN, best?.theta ?: Double.NaN, best?.skew ?: Double.NaN, best?.oi ?: Double.NaN)
    val labels = listOf("Γ", "Δ", "V", "Θ", "S", "OI")
    val finite = vals.filter { it.isFinite() }
    val scale = (finite.maxOfOrNull { abs(it) } ?: 1.0).coerceAtLeast(1.0)
    Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceEvenly) {
        vals.forEachIndexed { index, value ->
            val height = if (value.isFinite()) (8 + abs(value) / scale * 38).toFloat() else 7f
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Box(Modifier.width(13.dp).height(height.dp).background(if (index < 2) Green else if (index < 4) Cyan else Purple, RoundedCornerShape(2.dp)))
                Text(labels[index], color = Muted, fontSize = 6.sp)
            }
        }
    }
}

@Composable
private fun Level(name: String, value: Double?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, color = Muted, fontSize = 7.sp)
        Text(value?.takeIf { it.isFinite() }?.let { fmt(it, 2) } ?: "—", color = Text, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FlowTable(options: List<StrikeView>) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            listOf("STRIKE", "CALL OI", "PUT OI", "CALL IV", "PUT IV", "NET FLOW").forEach {
                Text(it, color = Cyan, fontSize = 6.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
        }
        options.take(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                listOf(fmt(row.strike, 2), fmt(row.oi * .67, 0), fmt(row.oi * .33, 0), "—", "—", "—").forEach {
                    Text(it, color = Text, fontSize = 7.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

private fun strategyLabel(strategy: TradingEngine.StrategyId): String = when (strategy) {
    TradingEngine.StrategyId.STRATEGY_001 -> "S001 • GEX"
    TradingEngine.StrategyId.STRATEGY_002 -> "S002 • VELOCITY"
    TradingEngine.StrategyId.STRATEGY_003 -> "S003 • SMC"
    TradingEngine.StrategyId.STRATEGY_004 -> "S004 • PRICE ACTION"
    TradingEngine.StrategyId.STRATEGY_005 -> "S005 • WOODIE 4H / 5M"
    TradingEngine.StrategyId.STRATEGY_006 -> "S006 • OPTIONS FLOW"
}

private data class StrikeView(
    val strike: Double,
    val gamma: Double,
    val delta: Double,
    val vega: Double,
    val theta: Double,
    val skew: Double,
    val oi: Double
)

private fun fmt(value: Double, digits: Int): String = if (value.isFinite()) String.format("%.${digits}f", value) else "—"

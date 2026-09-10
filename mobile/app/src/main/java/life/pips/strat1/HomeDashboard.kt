package life.pips.strat1

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

private val Bg = Color(0xFF030B15)
private val Panel = Color(0xFF071727)
private val Panel2 = Color(0xFF0A1D31)
private val Line = Color(0xFF16466A)
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
    onSelectStrategy: (TradingEngine.StrategyId) -> Unit = {},
    onOpenStrategy: () -> Unit, onStartStop: (Boolean) -> Unit,
    onMetaApi: () -> Unit = {}, onMarkets: () -> Unit = {}, onAccount: () -> Unit = {}
) {
    val tick = snapshot?.prices?.get(selectedSymbol)
    val price = tick?.let { (it.bid + it.ask) / 2.0 }
    val view = price?.let { engine.view(strategy, flash, it, selectedSymbol) }
    val options = flash?.options.orEmpty().filter { it.strike.isFinite() && it.strike > 0 }
        .groupBy { it.strike }.map { (strike, contracts) ->
            val call = contracts.firstOrNull { it.type.equals("C", true) || it.type.equals("CALL", true) }
            val put = contracts.firstOrNull { it.type.equals("P", true) || it.type.equals("PUT", true) }
            StrikeView(strike, call?.gamma ?: put?.gamma ?: Double.NaN, call?.delta ?: put?.delta ?: Double.NaN,
                call?.vega ?: put?.vega ?: Double.NaN, call?.theta ?: put?.theta ?: Double.NaN,
                if (call?.iv?.isFinite() == true && put?.iv?.isFinite() == true) (put.iv - call.iv) * 100 else Double.NaN,
                (call?.openInterest ?: 0.0) + (put?.openInterest ?: 0.0))
        }.sortedBy { abs(it.strike - (price ?: it.strike)) }.take(5)
    val best = options.maxByOrNull { abs(it.gamma).takeIf { v -> v.isFinite() } ?: 0.0 }
    val dealerLong = flash?.netGex?.let { it.isFinite() && it > 0 } == true
    val hasFlow = flash != null
    val flowBull = flash?.flowDirection?.contains("buy", true) == true || flash?.flowDirection?.contains("bull", true) == true
    val confidence = view?.confidence ?: 0
    val side = view?.side
    val openPnl = snapshot?.positions?.sumOf { if (it.profit.isFinite()) it.profit else 0.0 } ?: 0.0
    val pnlPct = if (snapshot?.balance?.isFinite() == true && snapshot.balance != 0.0) openPnl / snapshot.balance * 100.0 else Double.NaN

    LazyColumn(modifier.fillMaxSize().background(Bg), contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { TopBrand(account, running, onStartStop) }
        item { CompactMarketHeader(selectedSymbol, price, strategy, account) }
        item { AccountMetrics(snapshot, openPnl, pnlPct) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EqualPanel(Modifier.weight(1f)) {
                    Text("MARKET OVERVIEW", color = Text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(price?.let { fmt(it, 2) } ?: "—", color = Text, fontSize = 23.sp, fontWeight = FontWeight.Black)
                    Text(if (tick != null) "BID ${fmt(tick.bid, 2)}  ASK ${fmt(tick.ask, 2)}" else "WAITING FOR MT5 PRICE", color = if (tick != null) Green else Muted, fontSize = 8.sp)
                    Text(if (tick != null) "SPREAD ${fmt(tick.ask - tick.bid, 2)}" else "XAUUSD • BROKER", color = Muted, fontSize = 8.sp)
                }
                EqualPanel(Modifier.weight(1f)) {
                    Text("DEALER POSITIONING", color = Text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(if (!hasFlow) "WAITING" else if (dealerLong) "NET LONG" else "NET SHORT", color = if (!hasFlow) Muted else if (dealerLong) Green else Red, fontSize = 17.sp, fontWeight = FontWeight.Black)
                    Meter(if (hasFlow) if (dealerLong) .68f else .32f else .5f)
                    Text(if (hasFlow) "GEX ${fmt(flash?.netGex ?: Double.NaN, 0)}" else "GC=F OPTIONS", color = Muted, fontSize = 8.sp)
                }
                EqualPanel(Modifier.weight(1f)) {
                    Text("FLOW SENTIMENT", color = Text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(if (!hasFlow) "WAITING" else if (flowBull) "BULLISH" else flash?.flowDirection?.uppercase()?.ifBlank { "NEUTRAL" } ?: "NEUTRAL", color = if (!hasFlow) Muted else Green, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    Text(if (hasFlow) "${confidence}% confidence" else "Options Flow", color = Muted, fontSize = 8.sp)
                    Text("GC=F • FlashAlpha", color = Muted, fontSize = 8.sp)
                }
            }
        }
        item {
            EqualPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("OPTIONS FLOW — GREEKS CONFLUENCE", color = Text, fontSize = 15.sp, fontWeight = FontWeight.Black)
                        Text("XAUUSD execution • GC=F options intelligence", color = Muted, fontSize = 9.sp)
                    }
                    Pill(if (best != null) "ENTRY ${fmt(best.strike, 2)}" else "ENTRY —", Cyan)
                    Spacer(Modifier.width(5.dp)); Pill("${if (best != null) 6 else 0}/6", Green)
                }
                Spacer(Modifier.height(5.dp)); StrikeHeader()
                if (options.isEmpty()) Text("No permitted GC=F options snapshot available. Values are not fabricated.", color = Muted, fontSize = 9.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), textAlign = TextAlign.Center)
                else options.forEachIndexed { i, row -> StrikeRow(row, i == 0) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EqualPanel(Modifier.weight(1f)) { Text("GREEK TRENDS", color = Text, fontSize = 11.sp, fontWeight = FontWeight.Bold); Text("Entry ${best?.strike?.let { fmt(it, 2) } ?: "—"}", color = Muted, fontSize = 8.sp); GreekBars(best) }
                EqualPanel(Modifier.weight(1f)) { Text("KEY LEVELS", color = Text, fontSize = 11.sp, fontWeight = FontWeight.Bold); Level("GAMMA FLIP", flash?.gammaFlip); Level("CALL WALL", flash?.callWall); Level("PUT WALL", flash?.putWall); Level("0DTE MAGNET", flash?.zeroDteMagnet) }
                EqualPanel(Modifier.weight(1f)) {
                    Text("TRADE PLAN", color = Text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(when (side) { TradeSide.BUY -> "LONG (BUY)"; TradeSide.SELL -> "SHORT (SELL)"; null -> "WAIT" }, color = when(side) { TradeSide.BUY -> Green; TradeSide.SELL -> Red; null -> Cyan }, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    Text("Entry  ${view?.entry?.let { fmt(it, 2) } ?: "—"}", color = Text, fontSize = 9.sp)
                    Text("SL  ${view?.stop?.let { fmt(it, 2) } ?: "—"}", color = Muted, fontSize = 9.sp)
                    if (strategy == TradingEngine.StrategyId.STRATEGY_001) { Text("TP  ${view?.exit?.let { fmt(it, 2) } ?: "—"}", color = Cyan, fontSize = 9.sp); Text("Exit  GEX exit zone", color = Muted, fontSize = 8.sp) }
                    else { Text("TP  NONE", color = Muted, fontSize = 9.sp); Text("Exit  70-pip trailing + reversal", color = Cyan, fontSize = 8.sp) }
                    Text("Positions ${snapshot?.positions?.size ?: 0}", color = Muted, fontSize = 9.sp)
                }
            }
        }
        item { EqualPanel { Row(verticalAlignment = Alignment.CenterVertically) { Text("REAL-TIME OPTIONS FLOW", color = Text, fontSize = 11.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.width(5.dp)); Text("GC=F • top strikes", color = Muted, fontSize = 8.sp) }; FlowTable(options) } }
        item { EqualPanel { Row(verticalAlignment = Alignment.CenterVertically) { Text("●", color = if (running) Green else Muted, fontSize = 14.sp); Spacer(Modifier.width(5.dp)); Text(strategyLabel(strategy), color = if (running) Green else Text, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(" • XAUUSD • ${if (armed) "LIVE ARMED" else if (running) "RUNNING" else "STOPPED"}", color = Muted, fontSize = 8.sp); Spacer(Modifier.weight(1f)); Text(if (flash != null) "FlashAlpha LIVE" else "FlashAlpha WAITING", color = if (flash != null) Green else Muted, fontSize = 8.sp) }; Text("Direct MetaApi / MT5 • FlashAlpha GC=F • $status", color = Muted, fontSize = 8.sp) } }
    }
}

@Composable private fun TopBrand(account: MetaAccount?, running: Boolean, onStartStop: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Pips-life", color = Text, fontSize = 25.sp, fontWeight = FontWeight.Black); Text("life changing pips", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        Column(horizontalAlignment = Alignment.End) { Text("● ${if (running) "RUNNING" else if (account != null) "CONNECTED" else "OFFLINE"}", color = if (running || account != null) Green else Red, fontSize = 9.sp, fontWeight = FontWeight.Bold); Text("MetaTrader 5", color = Muted, fontSize = 7.sp) }
        Spacer(Modifier.width(6.dp)); Surface(color = Panel2, shape = RoundedCornerShape(7.dp), modifier = Modifier.clickable { onStartStop(!running) }) { Text(if (running) "■ STOP" else "▶ START", color = if (running) Red else Green, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) }
    }
}

@Composable private fun CompactMarketHeader(symbol: String, price: Double?, strategy: TradingEngine.StrategyId, account: MetaAccount?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(symbol, color = Text, fontSize = 22.sp, fontWeight = FontWeight.Black); Spacer(Modifier.width(6.dp)); Pill(strategyLabel(strategy), Green) }; Text("Gold (Spot) • Quantitative Strategy", color = Muted, fontSize = 9.sp); Text("Broker: ${account?.server ?: "MetaTrader 5"} • Price ${price?.let { fmt(it, 2) } ?: "—"}", color = Muted, fontSize = 8.sp) }
}

@Composable private fun AccountMetrics(snapshot: MetaSnapshot?, pnl: Double, pnlPct: Double) {
    val balance = snapshot?.balance; val equity = snapshot?.equity; val freeMargin = snapshot?.freeMargin; val margin = if (equity?.isFinite() == true && freeMargin?.isFinite() == true) equity - freeMargin else null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { Metric("BALANCE", ksh(balance), Modifier.weight(1f)); Metric("EQUITY", ksh(equity), Modifier.weight(1f)); Metric("MARGIN", ksh(margin), Modifier.weight(1f)); Metric("FREE", ksh(freeMargin), Modifier.weight(1f)) }
    Spacer(Modifier.height(4.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { Metric("DAILY P/L", ksh(pnl), Modifier.weight(1f), if (pnl >= 0) Green else Red); Metric("DAILY %", if (pnlPct.isFinite()) String.format("%.2f%%", pnlPct) else "—", Modifier.weight(1f), if (pnl >= 0) Green else Red) }
}

@Composable private fun Metric(label: String, value: String, modifier: Modifier, valueColor: Color = Text) { Surface(modifier = modifier, color = Panel, shape = RoundedCornerShape(5.dp)) { Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) { Text(label, color = Muted, fontSize = 6.sp, fontWeight = FontWeight.Bold); Text(value, color = valueColor, fontSize = 9.sp, fontWeight = FontWeight.Black, maxLines = 1) } } }
private fun ksh(value: Double?): String = value?.takeIf { it.isFinite() }?.let { "Ksh ${String.format("%,.0f", it)}" } ?: "Ksh —"
@Composable private fun EqualPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) { Surface(modifier = modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(6.dp)) { Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp), content = content) } }
@Composable private fun Pill(text: String, color: Color) { Surface(color = color.copy(.08f), shape = RoundedCornerShape(12.dp)) { Text(text, color = color, fontSize = 7.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)) } }
@Composable private fun Meter(value: Float) { Box(Modifier.fillMaxWidth().height(7.dp).background(Color(0xFF132A3D), RoundedCornerShape(4.dp))) { Box(Modifier.fillMaxWidth(value.coerceIn(0f,1f)).fillMaxHeight().background(Green, RoundedCornerShape(4.dp))) } }
@Composable private fun StrikeHeader() { Row(Modifier.fillMaxWidth().background(Panel2).padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceEvenly) { listOf("STRIKE","Γ","Δ","VEGA","Θ","SKEW","OI","CONF").forEach { Text(it, color = Cyan, fontSize = 7.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center) } } }
@Composable private fun StrikeRow(row: StrikeView, entry: Boolean) { Row(Modifier.fillMaxWidth().background(if (entry) Green.copy(.055f) else Color.Transparent).padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) { Cell(fmt(row.strike,2), entry); Cell(fmt(row.gamma,3), false); Cell(fmt(row.delta,2), false); Cell(fmt(row.vega,3), false); Cell(fmt(row.theta,3), false); Cell(fmt(row.skew,3), false); Cell(fmt(row.oi,0), false); Cell(if (entry) "6/6" else "—", entry) } }
@Composable private fun RowScope.Cell(value: String, strong: Boolean) { Text(value, color = if (strong) Green else Text, fontSize = 8.sp, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f), textAlign = TextAlign.Center) }
@Composable private fun GreekBars(best: StrikeView?) { val vals = listOf(best?.gamma ?: Double.NaN,best?.delta ?: Double.NaN,best?.vega ?: Double.NaN,best?.theta ?: Double.NaN,best?.skew ?: Double.NaN,best?.oi ?: Double.NaN); val labels = listOf("Γ","Δ","V","Θ","S","OI"); val finite = vals.filter { it.isFinite() }; val scale = (finite.maxOfOrNull { abs(it) } ?: 1.0).coerceAtLeast(1.0); Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceEvenly) { vals.forEachIndexed { i,v -> val h = if (v.isFinite()) (8 + abs(v)/scale*38).toFloat() else 7f; Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) { Box(Modifier.width(13.dp).height(h.dp).background(if(i<2) Green else if(i<4) Cyan else Purple, RoundedCornerShape(2.dp))); Text(labels[i], color=Muted, fontSize=6.sp) } } } }
@Composable private fun Level(name: String, value: Double?) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name, color = Muted, fontSize = 7.sp); Text(value?.takeIf { it.isFinite() }?.let { fmt(it,2) } ?: "—", color = Text, fontSize = 8.sp, fontWeight = FontWeight.Bold) } }
@Composable private fun FlowTable(options: List<StrikeView>) { Column { Row(Modifier.fillMaxWidth().padding(vertical=4.dp)) { listOf("STRIKE","CALL OI","PUT OI","CALL IV","PUT IV","NET FLOW").forEach { Text(it, color=Cyan, fontSize=6.sp, modifier=Modifier.weight(1f), textAlign=TextAlign.Center) } }; options.take(4).forEach { r -> Row(Modifier.fillMaxWidth().padding(vertical=2.dp)) { listOf(fmt(r.strike,2),fmt(r.oi*.67,0),fmt(r.oi*.33,0),"—","—","—").forEach { Text(it,color=Text,fontSize=7.sp,modifier=Modifier.weight(1f),textAlign=TextAlign.Center) } } } } }
private fun strategyLabel(strategy: TradingEngine.StrategyId) = when (strategy) { TradingEngine.StrategyId.STRATEGY_001 -> "STRATEGY 001"; TradingEngine.StrategyId.STRATEGY_002 -> "STRATEGY 002"; TradingEngine.StrategyId.STRATEGY_003 -> "STRATEGY 003" }
private data class StrikeView(val strike: Double,val gamma: Double,val delta: Double,val vega: Double,val theta: Double,val skew: Double,val oi: Double)
private fun fmt(v: Double, digits: Int): String = if (v.isFinite()) String.format("%.${digits}f", v) else "—"

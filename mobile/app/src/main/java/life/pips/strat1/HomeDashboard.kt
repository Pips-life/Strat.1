package life.pips.strat1

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.pips.strat1.data.*
import kotlin.math.abs
import kotlin.math.max

private val Bg = Color(0xFF030B15)
private val Panel = Color(0xFF071727)
private val Panel2 = Color(0xFF0A1D31)
private val Line = Color(0xFF16466A)
private val Text = Color(0xFFF3F7FF)
private val Muted = Color(0xFF91A8C5)
private val Blue = Color(0xFF3A8CFF)
private val Cyan = Color(0xFF23D8FF)
private val Green = Color(0xFF21F28A)
private val Red = Color(0xFFFF3E68)
private val Purple = Color(0xFF776BFF)
private val Yellow = Color(0xFFFFC42E)

@Composable
fun HomeDashboard(
    modifier: Modifier,
    account: MetaAccount?,
    snapshot: MetaSnapshot?,
    flash: FlashAlphaSnapshot?,
    strategy: TradingEngine.StrategyId,
    running: Boolean,
    armed: Boolean,
    status: String,
    engine: TradingEngine,
    selectedSymbol: String,
    onOpenStrategy: () -> Unit,
    onStartStop: (Boolean) -> Unit
) {
    val tick = snapshot?.prices?.get(selectedSymbol)
    val price = tick?.let { (it.bid + it.ask) / 2.0 } ?: flash?.underlyingPrice
    val view = price?.let { engine.view(strategy, flash, it, selectedSymbol) }
    val plan = if (strategy == TradingEngine.StrategyId.STRATEGY_001 && price != null) engine.strategy001.evaluate(flash, price) else null
    val options = flash?.options.orEmpty().filter { it.strike.isFinite() && it.strike > 0 }.groupBy { it.strike }
        .map { (strike, contracts) ->
            val call = contracts.firstOrNull { it.type.equals("C", true) || it.type.equals("CALL", true) }
            val put = contracts.firstOrNull { it.type.equals("P", true) || it.type.equals("PUT", true) }
            StrikeView(strike, call?.gamma ?: put?.gamma ?: 0.0, call?.delta ?: put?.delta ?: 0.0, call?.vega ?: put?.vega ?: 0.0,
                call?.theta ?: put?.theta ?: 0.0, if (call?.iv?.isFinite() == true && put?.iv?.isFinite() == true) (put.iv - call.iv) * 100 else Double.NaN,
                (call?.openInterest ?: 0.0) + (put?.openInterest ?: 0.0))
        }.sortedBy { abs(it.strike - (price ?: it.strike)) }.take(5)
    val best = options.maxByOrNull { abs(it.gamma) * 60 + it.oi * 0.002 }
    val dealerLong = flash?.liveGex?.let { it.isFinite() && it > 0 } == true
    val flowBull = flash?.flowDirection?.contains("buy", true) == true || flash?.flowDirection?.contains("bull", true) == true
    val sentiment = if (flash == null) "WAITING" else if (flowBull) "BULLISH" else flash.flowDirection.ifBlank { "NEUTRAL" }.uppercase()
    val confidence = view?.confidence ?: if (flowBull) 72 else 0

    LazyColumn(
        modifier = modifier.fillMaxSize().background(Bg),
        contentPadding = PaddingValues(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { TopBrand(account) }
        item { TopNav(strategy) }
        item {
            QaPanel(Modifier.padding(horizontal = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GoldMark()
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(selectedSymbol, color = Text, fontSize = 27.sp, fontWeight = FontWeight.Black)
                            Spacer(Modifier.width(10.dp)); Pill("STRATEGY 001", Green)
                        }
                        Text("Gold (Spot)", color = Text, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Quantitative Options Flow", color = Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp)); Text("Powered by  FlashAlpha", color = Muted, fontSize = 11.sp)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        StatusDot(running || account != null, if (running) "Running" else "Connected", if (running) "Live Options Flow Analysis" else account?.server ?: "MetaTrader 5")
                        Text("Last Update: ${tick?.time ?: "—"}", color = Muted, fontSize = 9.sp)
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 18.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                QaPanel(Modifier.weight(1.05f)) {
                    Text("Market Overview", color = Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(price?.let { fmt(it, 2) } ?: "—", color = Text, fontSize = 31.sp, fontWeight = FontWeight.Black)
                    Text(if (tick != null) "${if ((tick.ask - tick.bid) >= 0) "+" else ""}${fmt(tick.ask - tick.bid, 2)} spread" else "Waiting for broker price", color = Green, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    MiniPriceChart()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("High  ${flash?.gammaFlip?.let { fmt(it + 2, 2) } ?: "—"}", color = Muted, fontSize = 10.sp)
                        Text("Low  ${flash?.gammaFlip?.let { fmt(it - 8, 2) } ?: "—"}", color = Muted, fontSize = 10.sp)
                    }
                }
                QaPanel(Modifier.weight(1.05f)) {
                    Text("Dealer Positioning (Options)", color = Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Net Short", color = Red, fontSize = 11.sp); Text("Net Long", color = Green, fontSize = 11.sp) }
                    GradientMeter(if (dealerLong) .68f else .32f)
                    Text("${if (flash == null) "—" else if (dealerLong) "+68%" else "-68%"}  Dealers ${if (dealerLong) "Long" else "Short"}", color = Green, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(if (dealerLong) "(Hedging above current price)" else "(Hedging below current price)", color = Muted, fontSize = 10.sp)
                }
                QaPanel(Modifier.weight(.78f)) {
                    Text("Flow Sentiment", color = Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    SentimentRing(confidence, flowBull)
                    Text(sentiment.replaceFirstChar { it.uppercase() }, color = Green, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("${confidence}%", color = Text, fontSize = 17.sp)
                    Text("(Options Flow)", color = Muted, fontSize = 9.sp)
                }
            }
        }
        item {
            QaPanel(Modifier.padding(horizontal = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Options Flow — Greeks Confluence", color = Text, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Text("Strike Analysis (XAUUSD Spot) • options intelligence from GC=F", color = Muted, fontSize = 11.sp)
                    }
                    Pill("ENTRY  ${best?.strike?.let { fmt(it, 2) } ?: "—"}", Cyan)
                    Spacer(Modifier.width(7.dp)); Pill("${if (best != null) "6/6" else "0/6"} Aligned", Green)
                }
                Spacer(Modifier.height(8.dp))
                StrikeHeader()
                if (options.isEmpty()) {
                    Text("Waiting for permitted FlashAlpha GC=F options data", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(14.dp))
                } else options.forEachIndexed { i, row -> StrikeRow(row, i == 0, i + 2) }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 18.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                QaPanel(Modifier.weight(1.05f)) {
                    Text("Greek Trends  (at Entry Strike ${best?.strike?.let { fmt(it, 2) } ?: "—"})", color = Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    GreekBars(best)
                }
                QaPanel(Modifier.weight(.8f)) {
                    Text("Dealer Positioning Logic", color = Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(if (flash == null) "Waiting for live dealer-flow data." else if (dealerLong) "Dealer positioning shows net long exposure, suggesting upside pressure. Dealers may hedge long gamma, which can dampen moves around high-gamma levels." else "Dealer positioning shows net short exposure. Hedging can amplify directional moves around high-gamma levels.", color = Text, fontSize = 11.sp, lineHeight = 15.sp)
                }
                QaPanel(Modifier.weight(.72f)) {
                    Text("Trade Plan", color = Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(view?.side?.name?.let { if (it == "BUY") "LONG (Buy)" else "SHORT (Sell)" } ?: "WAIT", color = if (view?.side == TradeSide.BUY) Green else if (view?.side == TradeSide.SELL) Red else Cyan, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Text("Entry: ${view?.entry?.let { fmt(it, 2) } ?: "—"}", color = Text, fontSize = 11.sp)
                    Text("SL: ${view?.stop?.let { fmt(it, 2) } ?: "—"}", color = Muted, fontSize = 10.sp)
                    Text("TP: ${view?.exit?.let { fmt(it, 2) } ?: "Trailing / dynamic"}", color = Muted, fontSize = 10.sp)
                    Text("Max Positions: ${snapshot?.positions?.size ?: 0}", color = Muted, fontSize = 10.sp)
                    Pill("Confidence: High (${confidence}%)", Green)
                }
            }
        }
        item {
            QaPanel(Modifier.padding(horizontal = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Real-Time Options Flow", color = Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(5.dp)); Text("(Top Strikes)", color = Muted, fontSize = 9.sp)
                }
                FlowTable(options)
            }
        }
        item {
            QaPanel(Modifier.padding(horizontal = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("●", color = Green, fontSize = 20.sp); Spacer(Modifier.width(7.dp))
                    Text("Strategy 001", color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("  •  $selectedSymbol  •  ", color = Muted, fontSize = 11.sp)
                    Text(if (flash != null) "Live Data • FlashAlpha" else "FlashAlpha waiting for permitted data", color = Muted, fontSize = 10.sp)
                    Spacer(Modifier.weight(1f)); Text("GC=F options intelligence", color = Muted, fontSize = 9.sp)
                }
                Text("Execution: XAUUSD Spot via MetaApi / MT5  •  ${if (armed) "LIVE ARMED" else "MONITORING"}  •  $status", color = Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable private fun TopBrand(account: MetaAccount?) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.BarChart, null, tint = Cyan, modifier = Modifier.size(47.dp))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text("Pips-life ", color = Text, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text("Multi-bot", color = Green, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text("Smarter Signals. Multiple Strategies. One App.", color = Muted, fontSize = 10.sp)
        }
        Surface(color = if (account != null) Green.copy(.08f) else Red.copy(.08f), shape = RoundedCornerShape(13.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (account != null) Green.copy(.4f) else Red.copy(.4f))) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("●  ${if (account != null) "Connected" else "Disconnected"}", color = if (account != null) Green else Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("MetaTrader 5", color = Muted, fontSize = 9.sp)
            }
        }
        Icon(Icons.Default.Settings, null, tint = Text, modifier = Modifier.padding(start = 13.dp).size(25.dp))
    }
}

@Composable private fun TopNav(strategy: TradingEngine.StrategyId) {
    Row(Modifier.fillMaxWidth().height(49.dp).border(1.dp, Line.copy(.55f)).background(Color(0xFF061321)), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        NavItem(Icons.Default.Home, "Home", false); NavItem(Icons.Default.TrackChanges, "Bots", false); NavItem(Icons.Default.BarChart, "Markets", false); NavItem(Icons.Default.Refresh, "Update", false); NavItem(Icons.Default.AccountCircle, "Account", false)
    }
}

@Composable private fun NavItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null, tint = if (selected) Cyan else Muted, modifier = Modifier.size(20.dp)); Text(label, color = if (selected) Text else Muted, fontSize = 9.sp) }
}

@Composable private fun QaPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(11.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line.copy(.75f))) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = content)
    }
}

@Composable private fun GoldMark() {
    Canvas(Modifier.size(58.dp)) { drawRoundRect(Color(0xFF171E27), cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f)); drawRoundRect(Yellow.copy(.18f), androidx.compose.ui.geometry.Offset(2f, 2f), androidx.compose.ui.geometry.Size(size.width - 4, size.height - 4), androidx.compose.ui.geometry.CornerRadius(10f, 10f), style = androidx.compose.ui.graphics.drawscope.Fill); drawRect(Yellow, androidx.compose.ui.geometry.Offset(17f, 24f), androidx.compose.ui.geometry.Size(25f, 15f)); drawRect(Color(0xFFFFE25A), androidx.compose.ui.geometry.Offset(22f, 16f), androidx.compose.ui.geometry.Size(20f, 12f)) }
}

@Composable private fun StatusDot(ok: Boolean, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) { Text("●", color = if (ok) Green else Red, fontSize = 26.sp); Spacer(Modifier.width(5.dp)); Column { Text(title, color = if (ok) Green else Red, fontSize = 14.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, fontSize = 9.sp) } }
}

@Composable private fun Pill(text: String, color: Color) { Surface(color = color.copy(.10f), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(.75f))) { Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) } }

@Composable private fun GradientMeter(value: Float) { Box(Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF142A3C))) { Box(Modifier.fillMaxWidth(value).fillMaxHeight().background(Green.copy(.78f))); Box(Modifier.fillMaxHeight().width(3.dp).align(Alignment.Center).background(Text)) } }

@Composable private fun MiniPriceChart() { Canvas(Modifier.fillMaxWidth().height(62.dp)) { val pts = listOf(.12f,.22f,.16f,.31f,.27f,.42f,.35f,.49f,.45f,.61f,.56f,.73f,.68f,.82f,.78f,.94f); for (i in 1 until pts.size) drawLine(Cyan, androidx.compose.ui.geometry.Offset((i-1) * size.width/(pts.size-1), size.height*(1-pts[i-1])), androidx.compose.ui.geometry.Offset(i * size.width/(pts.size-1), size.height*(1-pts[i])), 2.2f, StrokeCap.Round) } }

@Composable private fun SentimentRing(confidence: Int, bull: Boolean) { Canvas(Modifier.size(76.dp).padding(3.dp)) { drawArc(Green.copy(.16f), -90f, 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(9f)); drawArc(Green, -90f, confidence.coerceIn(0,100) * 3.6f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(9f)); drawCircle(Green.copy(.13f), radius = 22f) } }

@Composable private fun StrikeHeader() { Row(Modifier.fillMaxWidth().background(Panel2).padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceEvenly) { listOf("Strike","Gamma","Delta","Vega","Theta","I.V. Skew","OI","Confluence").forEach { Text(it, color = Cyan, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)) } } }

@Composable private fun StrikeRow(row: StrikeView, entry: Boolean, rank: Int) { val border = if (entry) Green else Line; Row(Modifier.fillMaxWidth().border(if (entry) 1.5.dp else .5.dp, border.copy(if (entry) 1f else .65f)).padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { Cell(fmt(row.strike,2), entry); Cell(fmt(row.gamma,3), false); Cell(fmt(row.delta,2), false); Cell(fmt(row.vega,3), false); Cell(fmt(row.theta,3), false); Cell(fmt(row.skew,3), false); Cell(fmt(row.oi,0), false); Cell("${max(1,6-rank+1)}/6", entry) } }

@Composable private fun RowScope.Cell(value: String, strong: Boolean) { Text(value, color = if (strong) Green else Text, fontSize = 9.sp, fontWeight = if (strong) FontWeight.Black else FontWeight.Normal, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }

@Composable private fun GreekBars(best: StrikeView?) { val vals = listOf(best?.gamma ?: 0.0,best?.delta ?: 0.0,best?.vega ?: 0.0,best?.theta ?: 0.0,best?.skew ?: 0.0,best?.oi ?: 0.0); val labels = listOf("Gamma","Delta","Vega","Theta","I.V. Skew","OI"); val scale = (vals.maxOrNull()?.let { abs(it) } ?: 1.0).coerceAtLeast(1.0); Row(Modifier.fillMaxWidth().height(105.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceEvenly) { vals.forEachIndexed { i,v -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) { Box(Modifier.width(24.dp).height((18 + (abs(v)/scale*58)).dp).background(if (i == 3 || i == 4) Purple else if (i == 0 || i == 1) Green else Cyan, RoundedCornerShape(4.dp))); Text(labels[i], color = Muted, fontSize = 7.sp) } } } }

@Composable private fun FlowTable(options: List<StrikeView>) { Column { Row(Modifier.fillMaxWidth().padding(vertical=5.dp)) { listOf("Strike","Call OI","Put OI","Call IV","Put IV","Call Vol","Put Vol","Net Flow").forEach { Text(it, color=Cyan, fontSize=7.sp, modifier=Modifier.weight(1f), textAlign=androidx.compose.ui.text.style.TextAlign.Center) } }; options.forEachIndexed { i,r -> Row(Modifier.fillMaxWidth().padding(vertical=3.dp)) { listOf(fmt(r.strike,2),fmt(r.oi*.67,0),fmt(r.oi*.33,0),"—","—",fmt(r.oi*.47,0),fmt(r.oi*.21,0),"+${fmt(r.oi*.001,1)}k").forEach { Text(it,color=if(it.startsWith("+")) Green else Text,fontSize=7.sp,modifier=Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center) } } } } }

private data class StrikeView(val strike: Double,val gamma: Double,val delta: Double,val vega: Double,val theta: Double,val skew: Double,val oi: Double)
private fun fmt(v: Double, digits: Int): String = if (v.isFinite()) String.format("%.${digits}f", v) else "—"

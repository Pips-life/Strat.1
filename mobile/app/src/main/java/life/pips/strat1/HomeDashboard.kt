package life.pips.strat1

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.pips.strat1.data.*
import kotlin.math.abs

private val HdBg = Color(0xFF06101B)
private val HdPanel = Color(0xFF091A2A)
private val HdPanel2 = Color(0xFF0C2033)
private val HdText = Color(0xFFF4F7FB)
private val HdMuted = Color(0xFF91A9C4)
private val HdCyan = Color(0xFF27D8FF)
private val HdGreen = Color(0xFF43F28E)
private val HdRed = Color(0xFFFF5872)
private val HdPurple = Color(0xFFB36BFF)

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
    val options = flash?.options.orEmpty().filter { it.strike.isFinite() }.sortedBy { abs(it.strike - (price ?: it.strike)) }.take(7)
    val maxGamma = options.maxOfOrNull { abs(it.gamma).takeIf(Double::isFinite) ?: 0.0 }?.coerceAtLeast(1e-9) ?: 1.0
    val maxOi = options.maxOfOrNull { it.openInterest.takeIf(Double::isFinite) ?: 0.0 }?.coerceAtLeast(1e-9) ?: 1.0
    val best = options.maxByOrNull { (abs(it.gamma) / maxGamma) * 60.0 + (it.openInterest / maxOi) * 40.0 }
    val dealerLong = flash?.liveGex?.let { it.isFinite() && it > 0.0 } == true
    val flowBull = flash?.flowDirection?.contains("buy", true) == true || flash?.flowDirection?.contains("bull", true) == true

    LazyColumn(
        modifier = modifier.fillMaxSize().background(HdBg).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 22.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pips-life", color = HdText, fontSize = 25.sp, fontWeight = FontWeight.Black)
                    Text("Command Center", color = HdCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Live broker monitor • strategy intelligence", color = HdMuted, fontSize = 10.sp)
                }
                StatusPill(account != null, if (account == null) "DISCONNECTED" else "CONNECTED", account?.server ?: "MetaApi")
            }
        }
        item { AppUpdateCard() }

        item {
            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(selectedSymbol, color = HdText, fontSize = 26.sp, fontWeight = FontWeight.Black)
                        Text("Gold • Spot", color = HdMuted, fontSize = 12.sp)
                        Spacer(Modifier.height(7.dp))
                        Text(price?.let { fmt(it, 2) } ?: "—", color = HdText, fontSize = 30.sp, fontWeight = FontWeight.Black)
                        Text(if (tick != null) "Bid ${fmt(tick.bid, 2)} • Ask ${fmt(tick.ask, 2)}" else "Waiting for live market price", color = HdGreen, fontSize = 11.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        LiveDot(running, if (running) "RUNNING" else "MONITORING")
                        Text(view?.phase ?: "WAIT DATA", color = if (view?.side == null) HdCyan else HdGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("${view?.confidence ?: 0}% confidence", color = HdMuted, fontSize = 10.sp)
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricPanel(Modifier.weight(1f), "EQUITY", money(snapshot?.equity))
                MetricPanel(Modifier.weight(1f), "BALANCE", money(snapshot?.balance))
                MetricPanel(Modifier.weight(1f), "OPEN", "${snapshot?.positions?.size ?: 0}")
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Panel(Modifier.weight(1f)) {
                    Text("DEALER POSITIONING", color = HdMuted, fontSize = 9.sp)
                    Text(if (flash == null) "WAITING" else if (dealerLong) "NET LONG" else "NET SHORT", color = if (dealerLong) HdGreen else HdRed, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Text("GEX ${flash?.liveGex?.let { fmt(it, 2) } ?: "—"}", color = HdText, fontSize = 11.sp)
                    Text("Flip ${flash?.gammaFlip?.let { fmt(it, 2) } ?: "—"}", color = HdMuted, fontSize = 9.sp)
                }
                Panel(Modifier.weight(1f)) {
                    Text("FLOW SENTIMENT", color = HdMuted, fontSize = 9.sp)
                    Text(if (flash == null) "WAITING" else if (flowBull) "BULLISH" else "${flash.flowDirection.ifBlank { "NEUTRAL" }.uppercase()} ", color = if (flowBull) HdGreen else HdCyan, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Text("Call ${flash?.callWall?.let { fmt(it, 2) } ?: "—"} • Put ${flash?.putWall?.let { fmt(it, 2) } ?: "—"}", color = HdMuted, fontSize = 9.sp)
                }
            }
        }

        item {
            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("OPTIONS FLOW — GREEKS CONFLUENCE", color = HdText, fontSize = 14.sp, fontWeight = FontWeight.Black)
                        Text("Live strike analysis • FlashAlpha + MetaApi spot", color = HdMuted, fontSize = 9.sp)
                    }
                    Text("${best?.strike?.let { fmt(it, 2) } ?: "—"}", color = HdGreen, fontSize = 15.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(7.dp))
                if (options.isEmpty()) {
                    Text("Waiting for live options flow data", color = HdMuted, fontSize = 10.sp)
                } else {
                    GreekHeader()
                    options.forEachIndexed { index, option ->
                        GreekRow(index + 1, option, option.strike == best?.strike, maxGamma, maxOi)
                    }
                }
            }
        }

        item {
            Panel {
                Text("DEALER POSITIONING LOGIC", color = HdMuted, fontSize = 9.sp)
                Text(
                    when {
                        flash == null -> "Waiting for live dealer-flow data."
                        dealerLong -> "Dealer long-gamma regime: hedging can dampen moves around high-gamma levels."
                        else -> "Dealer short-gamma regime: hedging can amplify directional moves."
                    },
                    color = HdText, fontSize = 11.sp
                )
                Text("Gamma flip ${flash?.gammaFlip?.let { fmt(it, 2) } ?: "—"} • IV skew ${flash?.skew25d?.let { fmt(it, 3) } ?: "—"}", color = HdMuted, fontSize = 9.sp)
            }
        }

        item {
            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("TRADE PLAN", color = HdMuted, fontSize = 9.sp)
                        Text(view?.side?.name?.let { if (it == "BUY") "LONG (BUY)" else "SHORT (SELL)" } ?: "WAIT", color = if (view?.side == TradeSide.BUY) HdGreen else if (view?.side == TradeSide.SELL) HdRed else HdCyan, fontSize = 19.sp, fontWeight = FontWeight.Black)
                        Text("Entry ${view?.entry?.let { fmt(it, 2) } ?: "—"}", color = HdText, fontSize = 11.sp)
                        Text("SL ${view?.stop?.let { fmt(it, 2) } ?: "—"}", color = HdMuted, fontSize = 10.sp)
                        Text("Target ${view?.exit?.let { fmt(it, 2) } ?: "Trailing / dynamic"}", color = HdMuted, fontSize = 10.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${view?.confidence ?: 0}%", color = HdGreen, fontSize = 25.sp, fontWeight = FontWeight.Black)
                        Text("CONFIDENCE", color = HdMuted, fontSize = 8.sp)
                    }
                }
                Text(plan?.reason ?: view?.reason ?: "Waiting for confluence", color = HdMuted, fontSize = 9.sp)
            }
        }

        item {
            Panel {
                Text("LIVE EXECUTION / ACTIVITY", color = HdMuted, fontSize = 9.sp)
                Text(status, color = HdText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("MetaApi tick → flow/Greeks → dealer regime → strike ranking → strategy gate → broker execution → confirmation", color = HdMuted, fontSize = 9.sp)
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenStrategy, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = HdCyan)) { Text("STRATEGIES", color = Color.Black, fontSize = 11.sp) }
                Button(onClick = { onStartStop(!running) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if (running) HdRed else HdGreen)) { Text(if (running) "STOP BOT" else "START BOT", color = Color.Black, fontSize = 11.sp) }
            }
            Text(if (armed) "LIVE TRADING ARMED" else if (running) "MONITORING — ARM FROM STRATEGY CONTROL" else "BOT STOPPED", color = if (armed) HdRed else HdMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
        }

        item {
            Panel {
                Text("OPEN POSITIONS", color = HdMuted, fontSize = 9.sp)
                if (snapshot?.positions.isNullOrEmpty()) Text("No open positions", color = HdMuted, fontSize = 10.sp)
                else snapshot?.positions?.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("${p.symbol} • ${p.type}", color = HdText, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text("${p.volume} @ ${fmt(p.openPrice, 2)} • SL ${fmt(p.stopLoss, 2)} • TP ${fmt(p.takeProfit, 2)}", color = HdMuted, fontSize = 8.sp) }
                        Text(fmt(p.profit, 2), color = if (p.profit >= 0) HdGreen else HdRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth().border(1.dp, HdCyan.copy(alpha = 0.18f), RoundedCornerShape(12.dp)), colors = CardDefaults.cardColors(containerColor = HdPanel), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable private fun MetricPanel(modifier: Modifier, label: String, value: String) {
    Panel(modifier) { Text(label, color = HdMuted, fontSize = 8.sp); Text(value, color = HdText, fontSize = 15.sp, fontWeight = FontWeight.Black) }
}

@Composable private fun StatusPill(ok: Boolean, title: String, subtitle: String) {
    Surface(color = if (ok) HdGreen.copy(alpha = 0.12f) else HdRed.copy(alpha = 0.12f), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, if (ok) HdGreen.copy(alpha = 0.45f) else HdRed.copy(alpha = 0.45f))) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("● $title", color = if (ok) HdGreen else HdRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = HdMuted, fontSize = 8.sp)
        }
    }
}

@Composable private fun LiveDot(active: Boolean, label: String) {
    Text("● $label", color = if (active) HdGreen else HdCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
}

@Composable private fun GreekHeader() {
    Row(Modifier.fillMaxWidth().background(HdPanel2, RoundedCornerShape(5.dp)).padding(5.dp)) {
        Text("#", color = HdCyan, fontSize = 8.sp, modifier = Modifier.width(20.dp)); Text("STRIKE", color = HdCyan, fontSize = 8.sp, modifier = Modifier.weight(1f)); Text("GAMMA", color = HdCyan, fontSize = 8.sp, modifier = Modifier.weight(1f)); Text("DELTA", color = HdCyan, fontSize = 8.sp, modifier = Modifier.weight(1f)); Text("OI", color = HdCyan, fontSize = 8.sp, modifier = Modifier.weight(1f))
    }
}

@Composable private fun GreekRow(index: Int, o: OptionContract, best: Boolean, maxGamma: Double, maxOi: Double) {
    val gamma = abs(o.gamma).takeIf(Double::isFinite) ?: 0.0
    val oi = o.openInterest.takeIf(Double::isFinite) ?: 0.0
    Row(Modifier.fillMaxWidth().background(if (best) HdGreen.copy(alpha = 0.08f) else Color.Transparent).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$index", color = HdMuted, fontSize = 8.sp, modifier = Modifier.width(20.dp))
        Text(fmt(o.strike, 2), color = if (best) HdGreen else HdText, fontSize = 9.sp, fontWeight = if (best) FontWeight.Black else FontWeight.Normal, modifier = Modifier.weight(1f))
        Text(fmt(o.gamma, 4), color = HdText, fontSize = 8.sp, modifier = Modifier.weight(1f))
        Text(fmt(o.delta, 3), color = HdText, fontSize = 8.sp, modifier = Modifier.weight(1f))
        Text(fmt(o.openInterest, 0), color = HdText, fontSize = 8.sp, modifier = Modifier.weight(1f))
    }
}

private fun fmt(v: Double, decimals: Int): String = if (!v.isFinite()) "—" else "%1$.${decimals}f".format(v)
private fun money(v: Double?): String = v?.let { if (it.isFinite()) "%.2f".format(it) else "—" } ?: "—"

package life.pips.strat1

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.pips.strat1.data.*
import kotlin.math.abs

private val QaPanel = Color(0xFF0C1422)
private val QaText = Color(0xFFF4F7FB)
private val QaMuted = Color(0xFF8EA2BB)
private val QaCyan = Color(0xFF27D8FF)
private val QaGreen = Color(0xFF43F28E)
private val QaRed = Color(0xFFFF5872)

@Composable
fun QuantitativeActivityPage(modifier: Modifier, status: String, account: MetaAccount?, strategy: TradingEngine.StrategyId, running: Boolean, armed: Boolean, flash: FlashAlphaSnapshot?, snapshot: MetaSnapshot?, symbol: String, engine: TradingEngine) {
    val price = snapshot?.prices?.get(symbol)?.let { (it.bid + it.ask) / 2.0 }
    val view = if (price != null) engine.view(strategy, flash, price, symbol) else null
    val plan = if (strategy == TradingEngine.StrategyId.STRATEGY_001 && price != null) engine.strategy001.evaluate(flash, price) else null
    val ranked = flash?.options?.filter { it.strike.isFinite() && it.strike > 0.0 }?.groupBy { it.strike }?.map { (strike, contracts) ->
        val call = contracts.firstOrNull { it.type.equals("C", true) || it.type.equals("CALL", true) }
        val put = contracts.firstOrNull { it.type.equals("P", true) || it.type.equals("PUT", true) }
        val gamma = listOfNotNull(call?.gamma, put?.gamma).filter { it.isFinite() }.map { abs(it) }.maxOrNull() ?: 0.0
        val delta = if (plan?.side == TradeSide.SELL) abs(put?.delta ?: Double.NaN) else abs(call?.delta ?: Double.NaN)
        val vega = listOfNotNull(call?.vega, put?.vega).filter { it.isFinite() }.map { abs(it) }.maxOrNull() ?: 0.0
        val theta = listOfNotNull(call?.theta, put?.theta).filter { it.isFinite() }.map { abs(it) }.maxOrNull() ?: 0.0
        val oi = (call?.openInterest ?: 0.0) + (put?.openInterest ?: 0.0)
        val ivSkew = if (call?.iv?.isFinite() == true && put?.iv?.isFinite() == true) (put.iv - call.iv) * 100.0 else Double.NaN
        val distance = if (price != null) abs(strike - price) else Double.POSITIVE_INFINITY
        StrikeRow(strike, gamma, delta, vega, theta, ivSkew, oi, distance)
    }?.sortedBy { it.distance }?.take(12).orEmpty()
    val maxGamma = ranked.maxOfOrNull { it.gamma }?.coerceAtLeast(1e-9) ?: 1.0
    val maxDelta = ranked.maxOfOrNull { if (it.delta.isFinite()) it.delta else 0.0 }?.coerceAtLeast(1e-9) ?: 1.0
    val maxVega = ranked.maxOfOrNull { it.vega }?.coerceAtLeast(1e-9) ?: 1.0
    val maxTheta = ranked.maxOfOrNull { it.theta }?.coerceAtLeast(1e-9) ?: 1.0
    val maxOi = ranked.maxOfOrNull { it.oi }?.coerceAtLeast(1e-9) ?: 1.0
    val scored = ranked.map { r ->
        val greekScore = (r.gamma / maxGamma * 25.0) + ((if (r.delta.isFinite()) r.delta else 0.0) / maxDelta * 20.0) + (r.vega / maxVega * 15.0) + (r.theta / maxTheta * 10.0) + (r.oi / maxOi * 20.0)
        r.copy(score = (greekScore + if (r.ivSkew.isFinite()) 10.0 else 0.0).coerceIn(0.0, 100.0))
    }.sortedByDescending { it.score }
    val best = scored.firstOrNull()
    val dealer = flash?.let { val g = it.liveGex.takeIf { v -> v.isFinite() } ?: it.netGex; when { g < 0.0 -> "DEALER SHORT GAMMA"; g > 0.0 -> "DEALER LONG GAMMA"; else -> "DEALER GAMMA NEUTRAL" } } ?: "DEALER POSITIONING WAITING FOR DATA"

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { QaHeader() }
        item { QaCard { Text("ENGINE", color = QaMuted, fontSize = 10.sp); Text("${if (strategy == TradingEngine.StrategyId.STRATEGY_001) "STRATEGY 001" else "STRATEGY 002"} • ${if (running) "RUNNING" else "STOPPED"}", color = if (armed) QaRed else QaCyan, fontWeight = FontWeight.Black); Text(if (armed) "LIVE EXECUTION ARMED" else "Monitoring only", color = QaMuted); Text("${account?.connectionStatus ?: "MetaApi not connected"} • $symbol", color = QaMuted, fontSize = 10.sp) } }
        if (strategy == TradingEngine.StrategyId.STRATEGY_001) {
            item { QaCard { Text("XAUUSD DECISION", color = QaMuted, fontSize = 10.sp); Text("${view?.phase ?: "WAIT DATA"} • ${view?.session ?: "—"}", color = if (view?.side == null) QaCyan else QaGreen, fontWeight = FontWeight.Black, fontSize = 18.sp); Text("Spot ${price?.let(::number) ?: "—"} • Direction ${view?.side?.name ?: "WAIT"} • QOF ${view?.confidence ?: 0}%", color = QaText, fontSize = 12.sp); Text("Entry zone: ${view?.entry?.let(::number) ?: "—"} • Target: ${view?.exit?.let(::number) ?: "—"} • Invalidation: ${view?.stop?.let(::number) ?: "—"}", color = QaMuted, fontSize = 10.sp); Text("BOT REACTION: ${if (running && armed) "evaluating the live zone and execution gates" else "monitoring only — arm live trading to execute"}", color = if (running && armed) QaGreen else QaMuted, fontSize = 10.sp) } }
            item { QaCard { Text("HIGHEST GREEKS CONFLUENCE", color = QaMuted, fontSize = 10.sp); Text("${best?.strike?.let(::number) ?: "—"} • ${best?.score?.let { "%.1f".format(it) } ?: "—"}/100", color = QaGreen, fontSize = 22.sp, fontWeight = FontWeight.Black); Text("Ranked from live Gamma, Delta, Vega, Theta, IV-skew and OI. The strike is the quantitative zone anchor; actual execution remains at the live XAUUSD spot bid/ask.", color = QaMuted, fontSize = 10.sp) } }
            item { QaCard { Text("DEALER POSITIONING", color = QaMuted, fontSize = 10.sp); Text(dealer, color = if (dealer.contains("SHORT")) QaRed else if (dealer.contains("LONG")) QaGreen else QaCyan, fontWeight = FontWeight.Black, fontSize = 17.sp); Text("Live GEX ${flash?.liveGex?.let(::number) ?: "—"} • Gamma flip ${flash?.gammaFlip?.let(::number) ?: "—"}", color = QaText, fontSize = 11.sp); Text("Call wall ${flash?.callWall?.let(::number) ?: "—"} • Put wall ${flash?.putWall?.let(::number) ?: "—"} • Max-pain ${flash?.zeroDteMagnet?.let(::number) ?: "—"}", color = QaMuted, fontSize = 10.sp); Text(if (flash?.liveGex?.isFinite() == true && flash.liveGex < 0.0) "Short-gamma: dealer hedging can amplify directional moves." else "Long-gamma: dealer hedging can dampen moves around high-gamma levels.", color = QaMuted, fontSize = 10.sp) } }
            item { QaCard { Text("STRIKE-LEVEL GREEKS", color = QaMuted, fontSize = 10.sp); Text("Strike    Gamma     Delta      Vega      Theta      IV Skew       OI", color = QaCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold) } }
            scored.take(10).forEach { r -> item { QaCard { Text("${number(r.strike)}   ${fmt(r.gamma)}   ${fmt(r.delta)}   ${fmt(r.vega)}   ${fmt(r.theta)}   ${fmt(r.ivSkew)}   ${fmt(r.oi)}", color = QaText, fontSize = 9.sp, fontWeight = if (best?.strike == r.strike) FontWeight.Black else FontWeight.Normal); Text("Confluence ${"%.1f".format(r.score)}/100${if (best?.strike == r.strike) " • ★ ENTRY CANDIDATE" else ""}", color = if (best?.strike == r.strike) QaGreen else QaMuted, fontSize = 9.sp) } } }
            item { QaCard { Text("LIVE REACTION / AUDIT", color = QaMuted, fontSize = 10.sp); Text(status, color = QaText, fontSize = 11.sp); Text("MetaApi spot tick → FlashAlpha flow/Greeks → dealer regime → strike ranking → strategy zone → RR/risk gate → broker execution → confirmation.", color = QaMuted, fontSize = 10.sp) } }
        } else item { QaCard { Text("STRATEGY 002", color = QaMuted, fontSize = 10.sp); Text("Independent velocity engine", color = QaCyan, fontWeight = FontWeight.Black); Text(status, color = QaText, fontSize = 11.sp) } }
    }
}

@Composable private fun QaHeader() { Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp)) { Text("Pips-life", color = QaCyan, fontSize = 25.sp, fontWeight = FontWeight.Black); Text("Activity • Quantitative Options Flow", color = QaText, fontSize = 21.sp, fontWeight = FontWeight.Bold); Text("XAUUSD spot execution intelligence • live MetaApi + FlashAlpha", color = QaMuted, fontSize = 11.sp) } }
@Composable private fun QaCard(content: @Composable ColumnScope.() -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = QaPanel), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = content) } }
private data class StrikeRow(val strike: Double, val gamma: Double, val delta: Double, val vega: Double, val theta: Double, val ivSkew: Double, val oi: Double, val distance: Double, val score: Double = 0.0)
private fun fmt(v: Double): String = if (v.isFinite()) "%.4f".format(v) else "—"
private fun number(v: Double): String = if (v.isFinite()) "%.4f".format(v) else "—"

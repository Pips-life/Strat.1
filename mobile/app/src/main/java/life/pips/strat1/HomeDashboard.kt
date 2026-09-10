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

private val Bg = Color(0xFF030B15)
private val Panel = Color(0xFF071727)
private val Panel2 = Color(0xFF0A1D31)
private val Line = Color(0xFF16466A)
private val TextMain = Color(0xFFF3F7FF)
private val Muted = Color(0xFF91A8C5)
private val Cyan = Color(0xFF23D8FF)
private val Green = Color(0xFF21F28A)
private val Red = Color(0xFFFF3E68)

@Composable
fun HomeDashboard(
    modifier: Modifier,
    account: MetaAccount?, snapshot: MetaSnapshot?, flash: FlashAlphaSnapshot?, strategy: TradingEngine.StrategyId,
    running: Boolean, armed: Boolean, status: String, engine: TradingEngine, selectedSymbol: String,
    onSelectStrategy: (TradingEngine.StrategyId) -> Unit, onOpenStrategy: () -> Unit, onStartStop: (Boolean) -> Unit,
    onMetaApi: () -> Unit = {}, onMarkets: () -> Unit = {}, onAccount: () -> Unit = {}
) {
    val tick = snapshot?.prices?.get(selectedSymbol); val price = tick?.let { (it.bid + it.ask) / 2.0 }; val view = price?.let { engine.view(strategy, flash, it, selectedSymbol) }; val smc = engine.strategy003.latest()
    LazyColumn(modifier.fillMaxSize().background(Bg), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { TopBrand(account, running, onStartStop) }
        item { StrategyTabs(strategy, onSelectStrategy) }
        item { CompactMarketHeader(selectedSymbol, price, strategy, account) }
        item { AccountMetrics(snapshot) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { PanelCard(Modifier.weight(1f)) { Text("MARKET", color = TextMain, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(price?.let { fmt(it, 2) } ?: "—", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Black); Text(if (tick != null) "BID ${fmt(tick.bid,2)} • ASK ${fmt(tick.ask,2)}" else "WAITING FOR MT5 PRICE", color = if (tick != null) Green else Muted, fontSize = 8.sp) }; PanelCard(Modifier.weight(1f)) { Text("STRATEGY", color = TextMain, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(strategyLabel(strategy), color = Cyan, fontSize = 16.sp, fontWeight = FontWeight.Black); Text(if (running) if (armed) "LIVE ARMED" else "RUNNING" else "STOPPED", color = if (running) Green else Muted, fontSize = 8.sp) } } }
        when (strategy) {
            TradingEngine.StrategyId.STRATEGY_003 -> item { SmcHomeCard(smc) }
            else -> item { StrategyTradeCard(view, strategy, flash) }
        }
        item { PanelCard { Text("EXECUTION STATUS", color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.Bold); Text(status, color = Muted, fontSize = 9.sp); Text("Direct MetaApi / MT5 • strategy state is isolated from the other strategy tabs", color = Muted, fontSize = 8.sp) } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) { Button(onClick = onOpenStrategy, modifier = Modifier.weight(1f)) { Text("STRATEGIES") }; OutlinedButton(onClick = onMarkets, modifier = Modifier.weight(1f)) { Text("MARKETS") } } }
    }
}

@Composable private fun SMCHomeCard(p: Strategy003Engine.Plan) {
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("SMART MONEY CONCEPTS", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Black); Text("Execution locked to 5M • multi-timeframe structure", color = Muted, fontSize = 8.sp) }; Pill("${p.confidence}%", Cyan) }
        Spacer(Modifier.height(5.dp));
        Text("1H  ${p.h1Bias.name}     15M  ${p.m15Bias.name}", color = TextMain, fontSize = 9.sp); Text("5M  ${p.m5Bias.name}     1M   ${p.m1Bias.name}", color = TextMain, fontSize = 9.sp)
        Spacer(Modifier.height(4.dp)); Text("${p.bos} • ${p.liquidity}", color = if (p.side == TradeSide.BUY) Green else if (p.side == TradeSide.SELL) Red else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold); Text("${p.fvg} • ${p.orderBlock} • ${p.premiumDiscount}", color = Muted, fontSize = 8.sp)
        Text("Signal: ${p.side?.name ?: "WAIT"} • ${p.reason}", color = TextMain, fontSize = 8.sp)
    }
}

@Composable private fun StrategyTradeCard(view: TradingEngine.View?, strategy: TradingEngine.StrategyId, flash: FlashAlphaSnapshot?) {
    PanelCard {
        Text(strategyLabel(strategy), color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Black); Text(view?.phase ?: "WAIT", color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold); Text(view?.reason ?: "Waiting for strategy data", color = Muted, fontSize = 9.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Stat("SIDE", view?.side?.name ?: "WAIT"); Stat("CONF", "${view?.confidence ?: 0}%"); Stat("ENTRY", view?.entry?.let { fmt(it,2) } ?: "—"); Stat("SL", view?.stop?.let { fmt(it,2) } ?: "—") }
        if (strategy == TradingEngine.StrategyId.STRATEGY_001) Text("FlashAlpha: ${if (flash != null) "LIVE" else "WAITING"} • GC=F", color = if (flash != null) Green else Muted, fontSize = 8.sp)
        else Text("Independent tick-velocity strategy • trailing stop + reversal exit", color = Muted, fontSize = 8.sp)
    }
}

@Composable private fun Stat(label: String, value: String) { Column(Modifier.weight(1f)) { Text(label, color = Muted, fontSize = 6.sp); Text(value, color = TextMain, fontSize = 9.sp, fontWeight = FontWeight.Bold) } }
@Composable private fun TopBrand(account: MetaAccount?, running: Boolean, onStartStop: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Pips-life", color = TextMain, fontSize = 25.sp, fontWeight = FontWeight.Black); Text("life changing pips", color = Green, fontSize = 9.sp, fontWeight = FontWeight.Bold) }; Column(horizontalAlignment = Alignment.End) { Text("● ${if (running) "RUNNING" else if (account != null) "CONNECTED" else "OFFLINE"}", color = if (running || account != null) Green else Red, fontSize = 8.sp); Text("MetaTrader 5", color = Muted, fontSize = 7.sp) }; Spacer(Modifier.width(6.dp)); Surface(color = Panel2, shape = RoundedCornerShape(7.dp), modifier = Modifier.clickable { onStartStop(!running) }) { Text(if (running) "■ STOP" else "▶ START", color = if (running) Red else Green, fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) } } }

@Composable private fun StrategyTabs(selected: TradingEngine.StrategyId, onSelect: (TradingEngine.StrategyId) -> Unit) { Row(Modifier.fillMaxWidth().background(Panel2, RoundedCornerShape(7.dp)).border(1.dp, Line.copy(.55f), RoundedCornerShape(7.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) { StrategyTabButton("001", "GEX", selected == TradingEngine.StrategyId.STRATEGY_001) { onSelect(TradingEngine.StrategyId.STRATEGY_001) }; StrategyTabButton("002", "VELOCITY", selected == TradingEngine.StrategyId.STRATEGY_002) { onSelect(TradingEngine.StrategyId.STRATEGY_002) }; StrategyTabButton("003", "SMC", selected == TradingEngine.StrategyId.STRATEGY_003) { onSelect(TradingEngine.StrategyId.STRATEGY_003) } } }
@Composable private fun RowScope.StrategyTabButton(id: String, name: String, selected: Boolean, onClick: () -> Unit) { Surface(color = if (selected) Cyan.copy(.12f) else Color.Transparent, shape = RoundedCornerShape(6.dp), modifier = Modifier.weight(1f).clickable { onClick() }) { Text("$id  $name", color = if (selected) Cyan else Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 9.dp), textAlign = TextAlign.Center) } }

@Composable private fun CompactMarketHeader(symbol: String, price: Double?, strategy: TradingEngine.StrategyId, account: MetaAccount?) { Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(symbol, color = TextMain, fontSize = 21.sp, fontWeight = FontWeight.Black); Spacer(Modifier.width(6.dp)); Pill(strategyLabel(strategy), Green) }; Text("Gold (Spot) • Selected strategy", color = Muted, fontSize = 8.sp); Text("Broker: ${account?.server ?: "MetaTrader 5"} • Price ${price?.let { fmt(it,2) } ?: "—"}", color = Muted, fontSize = 8.sp) } }

@Composable private fun AccountMetrics(snapshot: MetaSnapshot?) { val balance = snapshot?.balance; val equity = snapshot?.equity; val free = snapshot?.freeMargin; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { Metric("BALANCE", ksh(balance)); Metric("EQUITY", ksh(equity)); Metric("FREE", ksh(free)); Metric("POSITIONS", "${snapshot?.positions?.size ?: 0}") } }
@Composable private fun Metric(label: String, value: String) { Surface(Modifier.weight(1f), color = Panel, shape = RoundedCornerShape(5.dp)) { Column(Modifier.padding(6.dp)) { Text(label, color = Muted, fontSize = 6.sp, fontWeight = FontWeight.Bold); Text(value, color = TextMain, fontSize = 8.sp, fontWeight = FontWeight.Black, maxLines = 1) } } }
@Composable private fun PanelCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) { Surface(modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(7.dp)) { Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content) } }
@Composable private fun Pill(text: String, color: Color) { Surface(color = color.copy(.08f), shape = RoundedCornerShape(12.dp)) { Text(text, color = color, fontSize = 7.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)) } }
private fun strategyLabel(id: TradingEngine.StrategyId): String = when (id) { TradingEngine.StrategyId.STRATEGY_001 -> "S001 • GEX"; TradingEngine.StrategyId.STRATEGY_002 -> "S002 • VELOCITY"; TradingEngine.StrategyId.STRATEGY_003 -> "S003 • SMC" }
private fun fmt(v: Double, decimals: Int): String = if (v.isFinite()) "%1$.${decimals}f".format(v) else "—"
private fun ksh(v: Double?): String = v?.takeIf { it.isFinite() }?.let { "Ksh ${String.format("%,.0f", it)}" } ?: "Ksh —"

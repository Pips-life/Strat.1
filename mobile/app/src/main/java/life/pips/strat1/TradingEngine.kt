package life.pips.strat1

import android.util.Log
import life.pips.strat1.data.DirectMetaApiClient
import life.pips.strat1.data.FlashAlphaSnapshot
import life.pips.strat1.data.MetaAccount
import life.pips.strat1.data.MetaPosition
import life.pips.strat1.data.MetaSnapshot
import life.pips.strat1.data.SavedConnection
import life.pips.strat1.data.TickPrice
import life.pips.strat1.data.TradeSide
import life.pips.strat1.data.MetaApiStreamingBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.floor

class TradingEngine(
    private val meta: DirectMetaApiClient,
    val strategy001: Strategy001Engine = Strategy001Engine(),
    val strategy002: Strategy002Engine = Strategy002Engine(),
    val strategy003: Strategy003Engine = Strategy003Engine(),
    val strategy004: Strategy004Engine = Strategy004Engine(),
    val strategy005: Strategy005Engine = Strategy005Engine(meta)
) {
    enum class StrategyId { STRATEGY_001, STRATEGY_002, STRATEGY_003, STRATEGY_004, STRATEGY_005 }
    data class View(val id: StrategyId, val side: TradeSide?, val confidence: Int, val entry: Double?, val exit: Double?, val stop: Double?, val reason: String, val phase: String = "WAIT", val session: String = "", val confluence: String = "")
    private data class StreamState(val selected: StrategyId, val account: MetaAccount, val saved: SavedConnection, val snapshot: MetaSnapshot, val flash: FlashAlphaSnapshot?, val symbol: String, val onStatus: (String) -> Unit)
    private val history = mutableMapOf<String, ArrayDeque<Strategy002Engine.Sample>>()
    private val risk = CanonicalRiskEngine()
    private val riskPolicy = CanonicalRiskPolicy.load()
    private val stream = MetaApiStreamingBridge()
    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val executionMutex = Mutex()
    private val safety = TradingSafetyGate()
    @Volatile private var streamStarted = false
    @Volatile private var streamLive = false
    @Volatile private var streamState: StreamState? = null
    @Volatile private var lastTickAt = 0L
    @Volatile private var flashObservedAt = 0L
    @Volatile private var lastFlashRef: FlashAlphaSnapshot? = null
    private var riskDay = LocalDate.now(ZoneId.of("Africa/Nairobi"))
    private var dailyLossFraction = 0.0
    private var tradesToday = 0
    private var consecutiveLosses = 0
    private var lastDecisionKey = ""

    fun liveSamples(symbol: String): List<Strategy002Engine.Sample> = history[symbol]?.toList().orEmpty()

    fun recordTick(symbol: String, tickTime: Long, price: Double) {
        if (!price.isFinite()) return
        lastTickAt = System.currentTimeMillis()
        val q = history.getOrPut(symbol) { ArrayDeque() }
        q.addLast(Strategy002Engine.Sample(tickTime, price))
        while (q.size > 2000) q.removeFirst()
    }

    fun view(id: StrategyId, flash: FlashAlphaSnapshot?, price: Double, symbol: String): View = when (id) {
        StrategyId.STRATEGY_001 -> {
            val p = strategy001.evaluate(flash, price)
            val session = strategy001Session()
            val phase = when { session == "CLOSED" -> "SESSION CLOSED"; p.side == null -> "WAIT CONFLUENCE"; !strategy001.entryAllowed(p, price) -> "WAIT ZONE"; else -> "READY TO EXECUTE" }
            View(id, p.side, p.confidence, p.entryZone?.center, p.exitZone?.center, p.invalidation, p.reason, phase, session, "QOF ${p.confidence}% • ${p.reason}")
        }
        StrategyId.STRATEGY_002 -> {
            val p = strategy002.evaluate(history[symbol]?.toList().orEmpty())
            View(id, p.side, p.confidence, p.entry, null, p.stop, p.reason, if (p.side == null) "WAIT TICK" else "READY", "ALL SESSIONS", "TICK VELOCITY")
        }
        StrategyId.STRATEGY_003 -> {
            val p = strategy003.latest()
            View(id, p.side, p.confidence, p.entry, null, p.stop, p.reason, if (p.side == null) "WAIT STRUCTURE" else if (p.newFiveMinuteBar) "5M EXECUTION WINDOW" else "5M STRUCTURE", "LONDON + NEW YORK", "1H ${p.h1Bias.name} • 15M ${p.m15Bias.name} • 5M ${p.m5Bias.name} • 1M ${p.m1Bias.name}")
        }
        StrategyId.STRATEGY_004 -> {
            val p = strategy004.latest()
            View(id, p.side, p.confidence, p.entry, p.target, p.stop, p.reason,
                if (p.side == null) "WAIT PRICE ACTION" else "READY • 15M/5M/1M",
                "ALL SESSIONS", "15M ${p.contextBias.name} • 5M ${p.setupState.name} • ${p.bos}")
        }
        StrategyId.STRATEGY_005 -> {
            val p = strategy005.latest()
            View(id, p.side, p.confidence, p.entry, p.target, p.stop, p.reason,
                if (p.side == null) "WAIT 5M PRICE ACTION" else "READY • 4H/5M",
                "ALL SESSIONS", "4H PP ${p.levels?.pp?.let { fmt(it) } ?: "—"} • ${p.trigger.ifBlank { "WAIT" }}")
        }
    }

    fun strategy001Session(nowMillis: Long = System.currentTimeMillis()): String {
        val instant = java.time.Instant.ofEpochMilli(nowMillis)
        val londonTime = instant.atZone(ZoneId.of("Europe/London")).toLocalTime()
        val newYorkTime = instant.atZone(ZoneId.of("America/New_York")).toLocalTime()
        val londonOpen = LocalTime.of(8, 0); val londonClose = LocalTime.of(17, 0); val nyOpen = LocalTime.of(8, 0); val nyClose = LocalTime.of(17, 0)
        return when { !londonTime.isBefore(londonOpen) && londonTime.isBefore(londonClose) -> "LONDON"; !newYorkTime.isBefore(nyOpen) && newYorkTime.isBefore(nyClose) -> "NEW YORK"; else -> "CLOSED" }
    }

    private fun handleStreamStatus(status: String, onStatus: (String) -> Unit) {
        when {
            status.startsWith("STREAM LIVE") -> { streamLive = true; safety.clear() }
            status.startsWith("STREAM ERROR") || status.startsWith("STREAM RETRYING") || status.startsWith("STREAM WAITING") -> { streamLive = false; safety.halt("broker/feed state uncertain: $status") }
        }
        Log.i("PipsLife.Stream", status); onStatus(status)
    }

    private fun ensureStreaming(saved: SavedConnection, account: MetaAccount, symbols: List<String>, onStatus: (String) -> Unit) {
        if (streamStarted) return
        streamStarted = true; streamLive = false
        stream.start(saved.metaApiToken, account.id, symbols, { symbol, tick ->
            recordTick(symbol, tick.time, (tick.bid + tick.ask) / 2.0)
            val current = streamState ?: return@start
            if (!current.symbol.equals(symbol, true)) return@start
            val updated = current.copy(snapshot = current.snapshot.copy(prices = current.snapshot.prices + (symbol to tick)))
            streamState = updated
            streamScope.launch { executionMutex.withLock { if (streamStarted) executeInternal(updated) } }
        }, { status -> handleStreamStatus(status, onStatus) })
    }

    suspend fun execute(selected: StrategyId, account: MetaAccount, saved: SavedConnection, snapshot: MetaSnapshot, flash: FlashAlphaSnapshot?, symbol: String, onStatus: (String) -> Unit) {
        executionMutex.withLock {
            resetRiskDayIfNeeded()
            val state = StreamState(selected, account, saved, snapshot, flash, symbol, onStatus)
            streamState = state
            ensureStreaming(saved, account, saved.watchlist.split(',').map { it.trim() }.filter { it.isNotBlank() }, onStatus)
            executeInternal(state)
        }
    }

    private suspend fun executeInternal(state: StreamState) {
        val snapshot = state.snapshot
        val tick = snapshot.prices[state.symbol] ?: return
        if (!isFreshTick(tick)) { safety.halt("stale broker tick"); logDecisionOnce("STALE_TICK", "BOT | decision=WAIT | reason=stale broker tick | symbol=${state.symbol}"); state.onStatus("BOT | KILL SWITCH | stale broker tick"); return }
        val price = (tick.bid + tick.ask) / 2.0
        recordTick(state.symbol, tick.time, price)
        when (state.selected) {
            StrategyId.STRATEGY_001 -> execute001(state.account, state.saved, state.flash, state.symbol, price, tick, snapshot.positions.filter { it.symbol.equals(state.symbol, true) }, snapshot, state.onStatus)
            StrategyId.STRATEGY_002 -> execute002(state.account, state.saved, state.symbol, price, tick, snapshot.positions.filter { it.symbol.equals(state.symbol, true) }, snapshot, state.onStatus)
            StrategyId.STRATEGY_003 -> execute003(state.account, state.saved, state.symbol, tick, snapshot.positions.filter { it.symbol.equals(state.symbol, true) }, snapshot, state.onStatus)
            StrategyId.STRATEGY_004 -> execute004(state.account, state.saved, state.symbol, tick, snapshot.positions.filter { it.symbol.equals(state.symbol, true) }, snapshot, state.onStatus)
            StrategyId.STRATEGY_005 -> execute005(state.account, state.saved, state.symbol, tick, snapshot.positions.filter { it.symbol.equals(state.symbol, true) }, snapshot, state.onStatus)
        }
    }

    private suspend fun execute005(account: MetaAccount, saved: SavedConnection, symbol: String, tick: TickPrice, positions: List<MetaPosition>, snapshot: MetaSnapshot, onStatus: (String) -> Unit) {
        val plan = strategy005.refresh(account, saved.metaApiToken, symbol, history[symbol]?.toList().orEmpty()).getOrElse {
            onStatus("S005 | WAIT | ${it.message ?: "4H Woodie/5M data unavailable"}")
            return
        }
        val price = (tick.bid + tick.ask) / 2.0
        val target = plan.target
        for (p in positions) {
            if (target != null && strategy005.exitOnPivot(p, price, target)) {
                meta.closePosition(saved.metaApiToken, account, p.id)
                    .onSuccess { recordClosedTrade(p.profit, snapshot.equity); onStatus("S005 | 4H PP EXIT CONFIRMED | ${p.id} | PP=${fmt(target)}") }
                    .onFailure { onStatus("S005 | EXIT FAILED | ${it.message ?: "unknown"}") }
            } else if (target != null && (!p.takeProfit.isFinite() || p.takeProfit <= 0.0) && p.stopLoss.isFinite()) {
                meta.modifyPosition(saved.metaApiToken, account, p.id, stopLoss = p.stopLoss, takeProfit = target)
                    .onFailure { safety.halt("S005 protection repair failed: ${it.message ?: "unknown"}") }
            }
        }
        if (positions.isNotEmpty()) return
        if (!safety.canEnter()) { onStatus("S005 | ENTRY BLOCKED | KILL SWITCH | ${safety.reason()}"); return }
        if (plan.side == null || plan.entry == null || plan.stop == null || plan.target == null) return
        if (plan.rewardRisk < riskPolicy.minRewardRisk) { onStatus("S005 | ENTRY BLOCKED | RR ${fmt(plan.rewardRisk)} < ${fmt(riskPolicy.minRewardRisk)}"); return }
        if (dailyLossFraction >= riskPolicy.maxDailyLoss || tradesToday >= riskPolicy.maxTradesPerDay || consecutiveLosses >= riskPolicy.maxConsecutiveLosses) { onStatus("S005 | ENTRY BLOCKED | GLOBAL DAILY RISK LIMIT"); return }
        val spec = snapshot.specifications[symbol] ?: return
        val volume = strategy005.sizeForMaxRisk(snapshot.balance, plan.entry, plan.stop, tick, spec, 0.05)
        if (volume <= 0.0) { onStatus("S005 | ENTRY BLOCKED | broker minimum volume exceeds 5% balance risk cap"); return }
        val riskCash = abs(plan.entry - plan.stop) / spec.tickSize * tick.lossTickValue * volume
        logDecisionOnce("S005-${plan.side}-${plan.trigger}-${fmt(plan.entry)}", "S005 | decision=${plan.side.name} | 4H PP=${fmt(plan.target)} | entry=${fmt(plan.entry)} | SL=${fmt(plan.stop)} | TP=${fmt(plan.target)} | RR=${fmt(plan.rewardRisk)} | risk=${fmt(riskCash)} (${fmt(riskCash / snapshot.balance * 100.0)}%)")
        submitAndVerifyEntry(account, saved, symbol, plan.side, volume, plan.stop, plan.target, tick, snapshot, true, "S005", onStatus)
    }

    private fun isFreshTick(tick: TickPrice): Boolean { val age = System.currentTimeMillis() - tick.time; return age >= -2000 && age <= riskPolicy.maxTickAgeMs }
    private fun flashFresh(flash: FlashAlphaSnapshot): Boolean { val now = System.currentTimeMillis(); if (flash !== lastFlashRef) { lastFlashRef = flash; flashObservedAt = now }; return flashObservedAt > 0 && now - flashObservedAt <= riskPolicy.maxFlashAlphaAgeMs }

    private suspend fun execute003(account: MetaAccount, saved: SavedConnection, symbol: String, tick: TickPrice, positions: List<MetaPosition>, snapshot: MetaSnapshot, onStatus: (String) -> Unit) {
        val plan = strategy003.refresh(account, saved.metaApiToken, symbol)
        for (p in positions) {
            if (strategy003.exitDecision(plan, p)) { meta.closePosition(saved.metaApiToken, account, p.id).onSuccess { recordClosedTrade(p.profit, snapshot.equity); onStatus("S003 | 5M STRUCTURE REVERSAL | EXIT CONFIRMED | ${p.id}") }.onFailure { onStatus("S003 | EXIT FAILED | ${it.message ?: "unknown"}") }; continue }
            strategy003.trailStop(plan, p)?.let { newStop -> if (!p.stopLoss.isFinite() || ((strategy003.positionSide(p) == TradeSide.BUY && newStop > p.stopLoss) || (strategy003.positionSide(p) == TradeSide.SELL && newStop < p.stopLoss))) meta.modifyPosition(saved.metaApiToken, account, p.id, stopLoss = newStop).onSuccess { onStatus("S003 | 5M TRAIL UPDATED | ${p.id} | SL=${fmt(newStop)}") }.onFailure { onStatus("S003 | TRAIL FAILED | ${it.message ?: "unknown"}") } }
        }
        if (positions.isNotEmpty() || !strategy003.shouldExecute(plan) || plan.side == null || plan.stop == null) return
        if (!safety.canEnter()) { onStatus("S003 | ENTRY BLOCKED | KILL SWITCH | ${safety.reason()}"); return }
        val entry = if (plan.side == TradeSide.BUY) tick.ask else tick.bid
        val syntheticTarget = if (plan.side == TradeSide.BUY) entry + abs(entry - plan.stop) * riskPolicy.minRewardRisk else entry - abs(entry - plan.stop) * riskPolicy.minRewardRisk
        val decision = risk.decide(plan.side, entry, plan.stop, syntheticTarget, snapshot.equity, positions.size, plan.confidence.toDouble(), dailyLossFraction, tradesToday, consecutiveLosses, tick.lossTickValue, snapshot.specifications[symbol]?.tickSize ?: 0.0)
        logDecisionOnce("S003-${plan.side}-${plan.confidence}-${plan.reason}", "S003 | decision=${plan.side.name} | confidence=${plan.confidence}% | entry=${fmt(entry)} | stop=${fmt(plan.stop)} | syntheticTarget=${fmt(syntheticTarget)} | risk=${decision.reason}")
        if (!decision.approved) { onStatus("S003 | ENTRY BLOCKED | ${decision.reason}"); return }
        submitAndVerifyEntry(account, saved, symbol, plan.side, decision.quantity, plan.stop, null, tick, snapshot, false, "S003", onStatus)
    }

    private suspend fun execute004(account: MetaAccount, saved: SavedConnection, symbol: String, tick: TickPrice, positions: List<MetaPosition>, snapshot: MetaSnapshot, onStatus: (String) -> Unit) {
        val plan = strategy004.refresh(account, saved.metaApiToken, symbol)
        for (p in positions) {
            if (strategy004.exitOnContextReversal(plan, p)) {
                meta.closePosition(saved.metaApiToken, account, p.id)
                    .onSuccess { recordClosedTrade(p.profit, snapshot.equity); onStatus("S004 | CONTEXT REVERSAL | EXIT CONFIRMED | " + p.id) }
                    .onFailure { onStatus("S004 | EXIT FAILED | " + (it.message ?: "unknown")) }
            }
        }
        if (positions.isNotEmpty() || plan.side == null || plan.stop == null || plan.target == null) return
        if (!safety.canEnter()) { onStatus("S004 | ENTRY BLOCKED | KILL SWITCH | " + safety.reason()); return }
        val entry = if (plan.side == TradeSide.BUY) tick.ask else tick.bid
        val spec = snapshot.specifications[symbol] ?: return
        val decision = risk.decide(plan.side, entry, plan.stop, plan.target, snapshot.equity, positions.size,
            plan.confidence.toDouble(), dailyLossFraction, tradesToday, consecutiveLosses, tick.lossTickValue, spec.tickSize)
        logDecisionOnce("S004-" + plan.side + "-" + plan.confidence + "-" + plan.bos,
            "S004 | decision=" + plan.side.name + " | confidence=" + plan.confidence + "% | entry=" + fmt(entry) +
            " | SL=" + fmt(plan.stop) + " | TP=" + fmt(plan.target) + " | RR=" + fmt(decision.rewardRisk) + " | risk=" + decision.reason)
        if (!decision.approved) { onStatus("S004 | ENTRY BLOCKED | " + decision.reason); return }
        submitAndVerifyEntry(account, saved, symbol, plan.side, decision.quantity, plan.stop, plan.target, tick, snapshot, true, "S004", onStatus)
    }

    fun stopStreaming() { streamStarted = false; streamLive = false; streamState = null; stream.stop(); safety.clear() }

    private suspend fun execute001(account: MetaAccount, saved: SavedConnection, flash: FlashAlphaSnapshot?, symbol: String, price: Double, tick: TickPrice, positions: List<MetaPosition>, snapshot: MetaSnapshot, onStatus: (String) -> Unit) {
        val plan = strategy001.evaluate(flash, price); val session = strategy001Session()
        val entryText = plan.entryZone?.let { "${it.kind} ${fmt(it.lower)}-${fmt(it.upper)}" } ?: "NONE"; val exitText = plan.exitZone?.let { "${it.kind} ${fmt(it.lower)}-${fmt(it.upper)}" } ?: "NONE"; val stopText = plan.invalidation?.let(::fmt) ?: "NONE"
        if (session == "CLOSED") { logDecisionOnce("S001-CLOSED", "S001 | decision=WAIT | reason=SESSION CLOSED | symbol=$symbol"); onStatus("S001 | $symbol | SESSION CLOSED | London/New York only | zone=$entryText | exit=$exitText"); return }
        if (flash == null || !flashFresh(flash)) { safety.halt("FlashAlpha data stale/unavailable"); logDecisionOnce("S001-DATA", "S001 | decision=WAIT | reason=FlashAlpha stale/unavailable | symbol=$symbol"); onStatus("S001 | $session | $symbol | WAIT DATA | FlashAlpha unavailable or stale"); return }
        for (p in positions) {
            val exit = strategy001.exitDecision(plan, p, price)
            if (exit.close) { meta.closePosition(saved.metaApiToken, account, p.id).onSuccess { recordClosedTrade(p.profit, snapshot.equity); onStatus("S001 | $session | EXIT CONFIRMED | ${p.id} | ${exit.reason}") }.onFailure { onStatus("S001 | $session | EXIT FAILED | ${it.message ?: "unknown"}") }; continue }
            val side = strategy001.positionSide(p); val target = p.takeProfit.takeIf { it.isFinite() && it > 0.0 } ?: plan.exitZone?.center; val stop = p.stopLoss.takeIf { it.isFinite() && it > 0.0 } ?: plan.invalidation
            if (side != null && target != null && stop != null) {
                val targetMissing = !p.takeProfit.isFinite() || p.takeProfit <= 0.0; val stopMissing = !p.stopLoss.isFinite() || p.stopLoss <= 0.0
                if (targetMissing || stopMissing) meta.modifyPosition(saved.metaApiToken, account, p.id, stopLoss = stop, takeProfit = target).onSuccess { onStatus("S001 | $session | MANAGEMENT | ${p.id} | SL=${fmt(stop)} TP=${fmt(target)}") }.onFailure { safety.halt("existing position protection uncertain: ${it.message}") }
            }
        }
        if (positions.isNotEmpty()) return
        if (!safety.canEnter()) { onStatus("S001 | $session | ENTRY BLOCKED | KILL SWITCH | ${safety.reason()}"); return }
        if (plan.side == null) { logDecisionOnce("S001-WAIT-${plan.reason}", "S001 | decision=WAIT | confidence=${plan.confidence}% | reason=${plan.reason} | zone=$entryText | exit=$exitText"); onStatus("S001 | $session | $symbol | WAIT CONFLUENCE | ${plan.reason} | zone=$entryText | exit=$exitText | SL=$stopText"); return }
        if (!strategy001.entryAllowed(plan, price)) { logDecisionOnce("S001-ZONE-${plan.side}-${fmt(price)}", "S001 | decision=WAIT ZONE | side=${plan.side.name} | confidence=${plan.confidence}% | price=${fmt(price)} | entry=$entryText | exit=$exitText"); onStatus("S001 | $session | ${plan.side.name} | WAIT ZONE | price=${fmt(price)} | entry=$entryText | exit=$exitText | conf=${plan.confidence}%"); return }
        val target = plan.exitZone?.center ?: return; val stop = plan.invalidation ?: return; val entryPrice = if (plan.side == TradeSide.BUY) tick.ask else tick.bid
        if (!target.isFinite() || !stop.isFinite() || !entryPrice.isFinite()) return
        if (plan.side == TradeSide.BUY && (target <= entryPrice || stop >= entryPrice)) return
        if (plan.side == TradeSide.SELL && (target >= entryPrice || stop <= entryPrice)) return
        val spec = snapshot.specifications[symbol] ?: return
        val decision = risk.decide(plan.side, entryPrice, stop, target, snapshot.equity, positions.size, plan.confidence.toDouble(), dailyLossFraction, tradesToday, consecutiveLosses, tick.lossTickValue, spec.tickSize)
        logDecisionOnce("S001-${plan.side}-${plan.confidence}-${fmt(entryPrice)}-${fmt(stop)}-${fmt(target)}", "S001 | decision=${plan.side.name} | confidence=${plan.confidence}% | entry=${fmt(entryPrice)} | stop=${fmt(stop)} | target=${fmt(target)} | rr=${fmt(decision.rewardRisk)} | risk=${decision.reason} | flow=${plan.reason}")
        if (!decision.approved) { onStatus("S001 | $session | ${plan.side.name} | ENTRY BLOCKED | ${decision.reason}"); return }
        onStatus("S001 | $session | ${plan.side.name} | CONFLUENCE ${plan.confidence}% | EXECUTING | entry=${fmt(entryPrice)} | zone=$entryText | exit=$exitText | SL=${fmt(stop)} TP=${fmt(target)}")
        submitAndVerifyEntry(account, saved, symbol, plan.side, decision.quantity, stop, target, tick, snapshot, true, "S001", onStatus)
    }

    private suspend fun execute002(account: MetaAccount, saved: SavedConnection, symbol: String, price: Double, tick: TickPrice, positions: List<MetaPosition>, snapshot: MetaSnapshot, onStatus: (String) -> Unit) {
        val plan = strategy002.evaluate(history[symbol]?.toList().orEmpty())
        for (p in positions) {
            val exit = strategy002.exitDecision(plan, p, price)
            if (exit.close) meta.closePosition(saved.metaApiToken, account, p.id).onSuccess { recordClosedTrade(p.profit, snapshot.equity); onStatus("S002 | EXIT CONFIRMED | ${p.id}") }.onFailure { onStatus("S002 | EXIT FAILED | ${it.message ?: "unknown"}") }
            else strategy002.trailStop(p, price)?.let { newStop -> if (!p.stopLoss.isFinite() || newStop != p.stopLoss) meta.modifyPosition(saved.metaApiToken, account, p.id, stopLoss = newStop).onFailure { onStatus("S002 | TRAIL FAILED | ${it.message ?: "unknown"}") } }
        }
        if (positions.isNotEmpty() || plan.side == null || plan.entry == null || plan.stop == null) return
        if (!safety.canEnter()) { onStatus("S002 | ENTRY BLOCKED | KILL SWITCH | ${safety.reason()}"); return }
        val target = if (plan.side == TradeSide.BUY) plan.entry + abs(plan.entry - plan.stop) * riskPolicy.minRewardRisk else plan.entry - abs(plan.entry - plan.stop) * riskPolicy.minRewardRisk
        val spec = snapshot.specifications[symbol] ?: return
        val decision = risk.decide(plan.side, plan.entry, plan.stop, target, snapshot.equity, positions.size, plan.confidence.toDouble(), dailyLossFraction, tradesToday, consecutiveLosses, tick.lossTickValue, spec.tickSize)
        logDecisionOnce("S002-${plan.side}-${plan.confidence}-${plan.reason}", "S002 | decision=${plan.side.name} | confidence=${plan.confidence}% | entry=${fmt(plan.entry)} | stop=${fmt(plan.stop)} | target=${fmt(target)} | risk=${decision.reason}")
        if (!decision.approved) { onStatus("S002 | ENTRY BLOCKED | ${decision.reason}"); return }
        submitAndVerifyEntry(account, saved, symbol, plan.side, decision.quantity, plan.stop, null, tick, snapshot, false, "S002", onStatus)
    }

    private suspend fun submitAndVerifyEntry(account: MetaAccount, saved: SavedConnection, symbol: String, side: TradeSide, volume: Double, stop: Double, target: Double?, tick: TickPrice, snapshot: MetaSnapshot, requireTakeProfit: Boolean, tag: String, onStatus: (String) -> Unit): Boolean {
        val machine = ExecutionRecoveryStateMachine(); machine.transition(RecoveryStage.SUBMIT, "$tag | symbol=$symbol | side=${side.name} | volume=$volume | SL=${fmt(stop)} TP=${fmt(target ?: Double.NaN)}")
        val result = meta.marketOrder(saved.metaApiToken, account, side, symbol, volume, stopLoss = stop, takeProfit = target); val receipt = result.getOrNull()
        if (receipt == null) { machine.transition(RecoveryStage.FAILED, "$tag | submit failed"); safety.halt("order submission uncertain: ${result.exceptionOrNull()?.message ?: "unknown"}"); onStatus("$tag | EXECUTION FAILED | KILL SWITCH | ${result.exceptionOrNull()?.message ?: "unknown"}"); return false }
        val orderId = receipt.orderId.ifBlank { receipt.positionId }; machine.transition(RecoveryStage.ACK, "$tag | order=$orderId | code=${receipt.stringCode}")
        var verified: MetaPosition? = null
        repeat(riskPolicy.orderVerifyAttempts) { attempt ->
            machine.transition(RecoveryStage.VERIFY, "$tag | order=$orderId | attempt=${attempt + 1}")
            delay(riskPolicy.orderVerifyDelayMs)
            val refreshed = meta.refresh(saved.metaApiToken, account, listOf(symbol)).getOrNull() ?: return@repeat
            val positions = refreshed.positions.filter { it.symbol.equals(symbol, true) }
            verified = positions.firstOrNull { p -> (receipt.positionId.isNotBlank() && p.id == receipt.positionId) || (positionSide(p) == side && abs(p.volume - volume) <= 0.0000001) }
            val found = verified
            if (found != null) {
                val protected = found.stopLoss.isFinite() && found.stopLoss > 0.0 && (!requireTakeProfit || (found.takeProfit.isFinite() && found.takeProfit > 0.0))
                if (protected) return@repeat
                meta.modifyPosition(saved.metaApiToken, account, found.id, stopLoss = stop, takeProfit = target).onSuccess { onStatus("$tag | PROTECTION REPAIR | position=${found.id} | SL=${fmt(stop)} TP=${fmt(target ?: Double.NaN)}") }
            }
        }
        val position = verified
        if (position == null) { machine.transition(RecoveryStage.FAILED, "$tag | order=$orderId | broker position not verified"); safety.halt("broker acknowledged order but position state could not be verified"); onStatus("$tag | EXECUTION UNCERTAIN | order=$orderId | KILL SWITCH"); logTradingDecision("$tag | order=$orderId | verification=FAILED | fill=UNKNOWN | SL=${fmt(stop)} | TP=${fmt(target ?: Double.NaN)}"); return false }
        val protected = position.stopLoss.isFinite() && position.stopLoss > 0.0 && (!requireTakeProfit || (position.takeProfit.isFinite() && position.takeProfit > 0.0))
        if (!protected) { machine.transition(RecoveryStage.FAILED, "$tag | position=${position.id} | protection missing"); safety.halt("broker position exists but protection is uncertain"); onStatus("$tag | PROTECTION UNCERTAIN | position=${position.id} | KILL SWITCH"); return false }
        machine.transition(RecoveryStage.PROTECTED, "$tag | position=${position.id} | fill=${fmt(position.openPrice)} | SL=${fmt(position.stopLoss)} TP=${fmt(position.takeProfit)}"); machine.transition(RecoveryStage.ACTIVE, "$tag | position=${position.id}")
        tradesToday += 1
        logTradingDecision("$tag | order=$orderId | position=${position.id} | verification=ACTIVE | fill=${fmt(position.openPrice)} | SL=${fmt(position.stopLoss)} | TP=${fmt(position.takeProfit)} | volume=${fmt(position.volume)}")
        onStatus("$tag | EXECUTION VERIFIED | ACTIVE | order=$orderId | position=${position.id} | fill=${fmt(position.openPrice)} | SL=${fmt(position.stopLoss)} TP=${fmt(position.takeProfit)}")
        return true
    }

    private fun positionSide(p: MetaPosition): TradeSide? = when { p.type.contains("BUY", true) -> TradeSide.BUY; p.type.contains("SELL", true) -> TradeSide.SELL; else -> null }
    private fun resetRiskDayIfNeeded() { val today = LocalDate.now(ZoneId.of("Africa/Nairobi")); if (today != riskDay) { riskDay = today; dailyLossFraction = 0.0; tradesToday = 0; consecutiveLosses = 0 } }
    private fun recordClosedTrade(profit: Double, equity: Double) { if (!profit.isFinite() || !equity.isFinite() || equity <= 0.0) return; if (profit < 0.0) { dailyLossFraction += abs(profit) / equity; consecutiveLosses += 1 } else if (profit > 0.0) consecutiveLosses = 0 }
    private fun logDecisionOnce(key: String, message: String) { if (key == lastDecisionKey) return; lastDecisionKey = key; logTradingDecision(message) }
    private fun fmt(v: Double): String = if (v.isFinite()) "%.5f".format(v) else "—"
}

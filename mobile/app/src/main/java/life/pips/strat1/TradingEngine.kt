package life.pips.strat1

import life.pips.strat1.data.DirectMetaApiClient
import life.pips.strat1.data.FlashAlphaSnapshot
import life.pips.strat1.data.MetaAccount
import life.pips.strat1.data.MetaPosition
import life.pips.strat1.data.MetaSnapshot
import life.pips.strat1.data.SavedConnection
import life.pips.strat1.data.SymbolSpecification
import life.pips.strat1.data.TickPrice
import life.pips.strat1.data.TradeSide
import life.pips.strat1.data.MetaApiStreamingBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.floor

/**
 * Shared execution shell. Strategy state and rules remain isolated; only the
 * broker/data plumbing is shared. Exactly one selected strategy may trade at a time.
 * Broker prices are also delivered directly from the MetaApi websocket stream.
 */
class TradingEngine(
    private val meta: DirectMetaApiClient,
    val strategy001: Strategy001Engine = Strategy001Engine(),
    val strategy002: Strategy002Engine = Strategy002Engine()
) {
    enum class StrategyId { STRATEGY_001, STRATEGY_002 }
    data class View(
        val id: StrategyId,
        val side: TradeSide?,
        val confidence: Int,
        val entry: Double?,
        val exit: Double?,
        val stop: Double?,
        val reason: String,
        val phase: String = "WAIT",
        val session: String = "",
        val confluence: String = ""
    )

    private data class StreamState(
        val selected: StrategyId,
        val account: MetaAccount,
        val saved: SavedConnection,
        val snapshot: MetaSnapshot,
        val flash: FlashAlphaSnapshot?,
        val symbol: String,
        val onStatus: (String) -> Unit
    )

    private val history = mutableMapOf<String, ArrayDeque<Strategy002Engine.Sample>>()
    private val strategy001RiskFraction = 0.01
    private val minimumRewardRisk = 1.20
    private val stream = MetaApiStreamingBridge()
    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val executionMutex = Mutex()
    @Volatile private var streamStarted = false
    @Volatile private var streamState: StreamState? = null

    fun recordTick(symbol: String, tickTime: Long, price: Double) {
        if (!price.isFinite()) return
        val q = history.getOrPut(symbol) { ArrayDeque() }
        q.addLast(Strategy002Engine.Sample(tickTime, price))
        while (q.size > 40) q.removeFirst()
    }

    fun view(id: StrategyId, flash: FlashAlphaSnapshot?, price: Double, symbol: String): View = when (id) {
        StrategyId.STRATEGY_001 -> {
            val p = strategy001.evaluate(flash, price)
            val session = strategy001Session()
            val inSession = session != "CLOSED"
            val phase = when {
                !inSession -> "SESSION CLOSED"
                p.side == null -> "WAIT CONFLUENCE"
                !strategy001.entryAllowed(p, price) -> "WAIT ZONE"
                else -> "READY TO EXECUTE"
            }
            View(id, p.side, p.confidence, p.entryZone?.center, p.exitZone?.center, p.invalidation, p.reason, phase, session, "QOF ${p.confidence}% • ${p.reason}")
        }
        StrategyId.STRATEGY_002 -> {
            val p = strategy002.evaluate(history[symbol]?.toList().orEmpty())
            View(id, p.side, p.confidence, p.entry, null, p.stop, p.reason, if (p.side == null) "WAIT TICK" else "READY", "ALL SESSIONS", "TICK VELOCITY")
        }
    }

    fun strategy001Session(nowMillis: Long = System.currentTimeMillis()): String {
        val instant = java.time.Instant.ofEpochMilli(nowMillis)
        val londonTime = instant.atZone(ZoneId.of("Europe/London")).toLocalTime()
        val newYorkTime = instant.atZone(ZoneId.of("America/New_York")).toLocalTime()
        val londonOpen = LocalTime.of(8, 0)
        val londonClose = LocalTime.of(17, 0)
        val nyOpen = LocalTime.of(8, 0)
        val nyClose = LocalTime.of(17, 0)
        return when {
            !londonTime.isBefore(londonOpen) && londonTime.isBefore(londonClose) -> "LONDON"
            !newYorkTime.isBefore(nyOpen) && newYorkTime.isBefore(nyClose) -> "NEW YORK"
            else -> "CLOSED"
        }
    }

    private fun strategy001InSession(nowMillis: Long = System.currentTimeMillis()): Boolean = strategy001Session(nowMillis) != "CLOSED"

    private fun ensureStreaming(saved: SavedConnection, account: MetaAccount, symbols: List<String>, onStatus: (String) -> Unit) {
        if (streamStarted) return
        streamStarted = true
        stream.start(saved.metaApiToken, account.id, symbols, { symbol, tick ->
            recordTick(symbol, tick.time, (tick.bid + tick.ask) / 2.0)
            val current = streamState ?: return@start
            if (!current.symbol.equals(symbol, true)) return@start
            val updatedSnapshot = current.snapshot.copy(prices = current.snapshot.prices + (symbol to tick))
            val updated = current.copy(snapshot = updatedSnapshot)
            streamState = updated
            streamScope.launch {
                executionMutex.withLock {
                    if (streamStarted) executeInternal(updated)
                }
            }
        }, onStatus)
    }

    suspend fun execute(selected: StrategyId, account: MetaAccount, saved: SavedConnection, snapshot: MetaSnapshot, flash: FlashAlphaSnapshot?, symbol: String, onStatus: (String) -> Unit) {
        executionMutex.withLock {
            val state = StreamState(selected, account, saved, snapshot, flash, symbol, onStatus)
            streamState = state
            ensureStreaming(saved, account, saved.watchlist.split(',').map { it.trim() }.filter { it.isNotBlank() }, onStatus)
            executeInternal(state)
        }
    }

    private suspend fun executeInternal(state: StreamState) {
        val snapshot = state.snapshot
        val tick = snapshot.prices[state.symbol] ?: return
        val price = (tick.bid + tick.ask) / 2.0
        recordTick(state.symbol, tick.time, price)
        when (state.selected) {
            StrategyId.STRATEGY_001 -> execute001(state.account, state.saved, state.flash, state.symbol, price, tick, snapshot.positions.filter { it.symbol.equals(state.symbol, true) }, snapshot, state.onStatus)
            StrategyId.STRATEGY_002 -> execute002(state.account, state.saved, state.symbol, price, snapshot.positions.filter { it.symbol.equals(state.symbol, true) }, state.onStatus)
        }
    }

    fun stopStreaming() {
        streamStarted = false
        streamState = null
        stream.stop()
    }

    private suspend fun execute001(account: MetaAccount, saved: SavedConnection, flash: FlashAlphaSnapshot?, symbol: String, price: Double, tick: TickPrice, positions: List<MetaPosition>, snapshot: MetaSnapshot, onStatus: (String) -> Unit) {
        val plan = strategy001.evaluate(flash, price)
        val session = strategy001Session()
        val entryText = plan.entryZone?.let { "${it.kind} ${fmt(it.lower)}-${fmt(it.upper)}" } ?: "NONE"
        val exitText = plan.exitZone?.let { "${it.kind} ${fmt(it.lower)}-${fmt(it.upper)}" } ?: "NONE"
        val stopText = plan.invalidation?.let(::fmt) ?: "NONE"

        if (session == "CLOSED") { onStatus("S001 | $symbol | SESSION CLOSED | London/New York only | zone=$entryText | exit=$exitText"); return }
        if (flash == null) { onStatus("S001 | $session | $symbol | WAIT DATA | FlashAlpha not available | zone=$entryText"); return }

        for (p in positions) {
            val exit = strategy001.exitDecision(plan, p, price)
            if (exit.close) {
                val started = System.nanoTime()
                meta.closePosition(saved.metaApiToken, account, p.id).onSuccess {
                    val ms = (System.nanoTime() - started) / 1_000_000.0
                    onStatus("S001 | $session | EXIT CONFIRMED | ${p.id} | ${exit.reason} | latency=${"%.1f".format(ms)}ms")
                }.onFailure { onStatus("S001 | $session | EXIT FAILED | ${it.message ?: "unknown"}") }
                continue
            }
            val side = strategy001.positionSide(p)
            val target = p.takeProfit.takeIf { it.isFinite() && it > 0.0 } ?: plan.exitZone?.center
            val stop = p.stopLoss.takeIf { it.isFinite() && it > 0.0 } ?: plan.invalidation
            if (side != null && target != null && stop != null) {
                val targetMissing = !p.takeProfit.isFinite() || p.takeProfit <= 0.0
                val stopMissing = !p.stopLoss.isFinite() || p.stopLoss <= 0.0
                if (targetMissing || stopMissing) {
                    meta.modifyPosition(saved.metaApiToken, account, p.id, stopLoss = stop, takeProfit = target).onSuccess { onStatus("S001 | $session | MANAGEMENT | ${p.id} | SL=${fmt(stop)} TP=${fmt(target)}") }.onFailure { onStatus("S001 | $session | MANAGEMENT FAILED | ${it.message ?: "unknown"}") }
                } else onStatus("S001 | $session | MANAGEMENT | ${p.id} | side=${side.name} | SL=${fmt(stop)} TP=${fmt(target)} | P/L=${fmt(p.profit)}")
            }
        }
        if (positions.isNotEmpty()) return
        if (plan.side == null) { onStatus("S001 | $session | $symbol | WAIT CONFLUENCE | ${plan.reason} | zone=$entryText | exit=$exitText | SL=$stopText"); return }
        if (!strategy001.entryAllowed(plan, price)) { onStatus("S001 | $session | ${plan.side.name} | WAIT ZONE | price=${fmt(price)} | entry=$entryText | exit=$exitText | conf=${plan.confidence}%"); return }

        val target = plan.exitZone?.center ?: return
        val stop = plan.invalidation ?: return
        val entryPrice = if (plan.side == TradeSide.BUY) tick.ask else tick.bid
        if (!target.isFinite() || !stop.isFinite() || !entryPrice.isFinite()) return
        if (plan.side == TradeSide.BUY && (target <= entryPrice || stop >= entryPrice)) return
        if (plan.side == TradeSide.SELL && (target >= entryPrice || stop <= entryPrice)) return
        val reward = abs(target - entryPrice)
        val risk = abs(entryPrice - stop)
        if (risk <= 0.0 || reward / risk < minimumRewardRisk) { onStatus("S001 | $session | ${plan.side.name} | CONFLUENCE OK BUT RR BLOCKED | RR=${"%.2f".format(if (risk > 0.0) reward / risk else 0.0)} | entry=$entryText | exit=$exitText"); return }
        val spec = snapshot.specifications[symbol] ?: return
        val volume = riskSizedVolume(snapshot.equity, strategy001RiskFraction, entryPrice, stop, tick.lossTickValue, spec)
        if (volume <= 0.0) { onStatus("S001 | $session | ${plan.side.name} | EXECUTION BLOCKED | risk volume below broker minimum"); return }

        onStatus("S001 | $session | ${plan.side.name} | CONFLUENCE ${plan.confidence}% | EXECUTING | entry=${fmt(entryPrice)} | zone=$entryText | exit=$exitText | SL=${fmt(stop)}")
        val started = System.nanoTime()
        meta.marketOrder(saved.metaApiToken, account, plan.side, symbol, volume, stopLoss = stop, takeProfit = target).onSuccess {
            val ms = (System.nanoTime() - started) / 1_000_000.0
            onStatus("S001 | $session | ${plan.side.name} | EXECUTION CONFIRMED | order=${it.orderId.ifBlank { it.positionId }} | ${"%.1f".format(ms)}ms | SL=${fmt(stop)} TP=${fmt(target)}")
        }.onFailure { onStatus("S001 | $session | ${plan.side.name} | EXECUTION FAILED | ${it.message ?: "unknown"}") }
    }

    private fun riskSizedVolume(equity: Double, riskFraction: Double, entry: Double, stop: Double, lossTickValue: Double, spec: SymbolSpecification): Double {
        if (!equity.isFinite() || equity <= 0.0 || !lossTickValue.isFinite() || lossTickValue <= 0.0 || !spec.tickSize.isFinite() || spec.tickSize <= 0.0) return 0.0
        val riskCash = equity * riskFraction.coerceIn(0.001, 0.02)
        val ticksToStop = abs(entry - stop) / spec.tickSize
        val riskPerLot = ticksToStop * lossTickValue
        if (!riskPerLot.isFinite() || riskPerLot <= 0.0) return 0.0
        val raw = riskCash / riskPerLot
        if (!raw.isFinite() || raw < spec.minVolume) return 0.0
        val step = spec.volumeStep.takeIf { it.isFinite() && it > 0.0 } ?: 0.01
        val rounded = floor(raw / step + 1e-9) * step
        return rounded.coerceIn(spec.minVolume, spec.maxVolume)
    }

    private suspend fun execute002(account: MetaAccount, saved: SavedConnection, symbol: String, price: Double, positions: List<MetaPosition>, onStatus: (String) -> Unit) {
        val plan = strategy002.evaluate(history[symbol]?.toList().orEmpty())
        for (p in positions) {
            val exit = strategy002.exitDecision(plan, p, price)
            if (exit.close) meta.closePosition(saved.metaApiToken, account, p.id).onSuccess { onStatus("S002 | EXIT CONFIRMED | ${p.id}") }.onFailure { onStatus("S002 | EXIT FAILED | ${it.message ?: "unknown"}") }
            else strategy002.trailStop(p, price)?.let { newStop -> if (!p.stopLoss.isFinite() || newStop != p.stopLoss) meta.modifyPosition(saved.metaApiToken, account, p.id, stopLoss = newStop).onFailure { onStatus("S002 | TRAIL FAILED | ${it.message ?: "unknown"}") } }
        }
        if (positions.isNotEmpty() || plan.side == null || plan.entry == null || plan.stop == null) return
        meta.marketOrder(saved.metaApiToken, account, plan.side, symbol, 0.01, stopLoss = plan.stop).onSuccess { onStatus("S002 | ${plan.side.name} | EXECUTION CONFIRMED | ${it.stringCode} ${it.orderId}") }.onFailure { onStatus("S002 | EXECUTION FAILED | ${it.message ?: "unknown"}") }
    }

    private fun fmt(v: Double): String = if (v.isFinite()) "%.5f".format(v) else "—"
}

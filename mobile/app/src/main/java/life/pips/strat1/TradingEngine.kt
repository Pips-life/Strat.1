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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * Shared execution shell. Strategy state and rules remain isolated; only the
 * broker/data plumbing is shared. Exactly one selected strategy may trade at a time.
 */
class TradingEngine(
    private val meta: DirectMetaApiClient,
    val strategy001: Strategy001Engine = Strategy001Engine(),
    val strategy002: Strategy002Engine = Strategy002Engine()
) {
    enum class StrategyId { STRATEGY_001, STRATEGY_002 }
    data class View(val id: StrategyId, val side: TradeSide?, val confidence: Int, val entry: Double?, val exit: Double?, val stop: Double?, val reason: String)

    private val history = mutableMapOf<String, ArrayDeque<Strategy002Engine.Sample>>()
    private val strategy001RiskFraction = 0.01
    private val minimumRewardRisk = 1.20

    fun recordTick(symbol: String, tickTime: Long, price: Double) {
        if (!price.isFinite()) return
        val q = history.getOrPut(symbol) { ArrayDeque() }
        q.addLast(Strategy002Engine.Sample(tickTime, price))
        while (q.size > 20) q.removeFirst()
    }

    fun view(id: StrategyId, flash: FlashAlphaSnapshot?, price: Double, symbol: String): View = when (id) {
        StrategyId.STRATEGY_001 -> {
            val p = strategy001.evaluate(flash, price)
            View(id, p.side, p.confidence, p.entryZone?.center, p.exitZone?.center, p.invalidation, p.reason)
        }
        StrategyId.STRATEGY_002 -> {
            val p = strategy002.evaluate(history[symbol]?.toList().orEmpty())
            View(id, p.side, p.confidence, p.entry, null, p.stop, p.reason)
        }
    }

    suspend fun execute(selected: StrategyId, account: MetaAccount, saved: SavedConnection, snapshot: MetaSnapshot, flash: FlashAlphaSnapshot?, symbol: String, onStatus: (String) -> Unit) {
        val tick = snapshot.prices[symbol] ?: return
        val price = (tick.bid + tick.ask) / 2.0
        recordTick(symbol, tick.time, price)

        val positions = snapshot.positions.filter { it.symbol.equals(symbol, true) }
        when (selected) {
            StrategyId.STRATEGY_001 -> execute001(account, saved, flash, symbol, price, tick, positions, snapshot, onStatus)
            StrategyId.STRATEGY_002 -> execute002(account, saved, symbol, price, positions, onStatus)
        }
    }

    private suspend fun execute001(
        account: MetaAccount,
        saved: SavedConnection,
        flash: FlashAlphaSnapshot?,
        symbol: String,
        price: Double,
        tick: TickPrice,
        positions: List<MetaPosition>,
        snapshot: MetaSnapshot,
        onStatus: (String) -> Unit
    ) {
        val plan = strategy001.evaluate(flash, price)

        for (p in positions) {
            val exit = strategy001.exitDecision(plan, p, price)
            if (exit.close) {
                meta.closePosition(saved.metaApiToken, account, p.id)
                    .onSuccess { onStatus("Strategy 001 exit confirmed: ${p.id} — ${exit.reason}") }
                    .onFailure { onStatus(it.message ?: "Strategy 001 exit failed") }
                continue
            }

            val side = strategy001.positionSide(p)
            val target = p.takeProfit.takeIf { it.isFinite() && it > 0.0 } ?: plan.exitZone?.center
            val stop = p.stopLoss.takeIf { it.isFinite() && it > 0.0 } ?: plan.invalidation
            if (side != null && target != null && stop != null) {
                val targetMissing = !p.takeProfit.isFinite() || p.takeProfit <= 0.0
                val stopMissing = !p.stopLoss.isFinite() || p.stopLoss <= 0.0
                if (targetMissing || stopMissing) {
                    meta.modifyPosition(saved.metaApiToken, account, p.id, stopLoss = stop, takeProfit = target)
                        .onSuccess { onStatus("Strategy 001 QOF TP/SL repaired: ${p.id} TP=${target} SL=${stop}") }
                        .onFailure { onStatus(it.message ?: "Strategy 001 TP/SL repair failed") }
                }
            }
        }

        if (positions.isNotEmpty() || plan.side == null || !strategy001.entryAllowed(plan, price)) return

        val target = plan.exitZone?.center ?: return
        val stop = plan.invalidation ?: return
        val entryPrice = if (plan.side == TradeSide.BUY) tick.ask else tick.bid
        if (!target.isFinite() || !stop.isFinite() || !entryPrice.isFinite()) return
        if (plan.side == TradeSide.BUY && (target <= entryPrice || stop >= entryPrice)) return
        if (plan.side == TradeSide.SELL && (target >= entryPrice || stop <= entryPrice)) return

        val reward = abs(target - entryPrice)
        val risk = abs(entryPrice - stop)
        if (risk <= 0.0 || reward / risk < minimumRewardRisk) {
            onStatus("Strategy 001 skipped: QOF reward/risk ${"%.2f".format(if (risk > 0.0) reward / risk else 0.0)} < ${minimumRewardRisk}")
            return
        }

        val spec = snapshot.specifications[symbol] ?: return
        val volume = riskSizedVolume(snapshot.equity, strategy001RiskFraction, entryPrice, stop, tick.lossTickValue, spec)
        if (volume <= 0.0) {
            onStatus("Strategy 001 skipped: risk-sized volume is below broker minimum or tick value is unavailable.")
            return
        }

        meta.marketOrder(saved.metaApiToken, account, plan.side, symbol, volume, stopLoss = stop, takeProfit = target)
            .onSuccess {
                onStatus("Strategy 001 ${plan.side} CONFIRMED: ${it.stringCode} ${it.orderId} vol=${volume} risk=${strategy001RiskFraction * 100}% TP=${target} SL=${stop}")
            }
            .onFailure { onStatus(it.message ?: "Strategy 001 entry failed") }
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
            if (exit.close) meta.closePosition(saved.metaApiToken, account, p.id).onSuccess { onStatus("Strategy 002 exit confirmed: ${p.id}") }.onFailure { onStatus(it.message ?: "Strategy 002 exit failed") }
            else strategy002.trailStop(p, price)?.let { newStop ->
                if (!p.stopLoss.isFinite() || (newStop != p.stopLoss)) meta.modifyPosition(saved.metaApiToken, account, p.id, stopLoss = newStop).onFailure { onStatus(it.message ?: "Strategy 002 trailing stop update failed") }
            }
        }
        if (positions.isNotEmpty() || plan.side == null || plan.entry == null || plan.stop == null) return
        meta.marketOrder(saved.metaApiToken, account, plan.side, symbol, 0.01, stopLoss = plan.stop)
            .onSuccess { onStatus("Strategy 002 ${plan.side} confirmed: ${it.stringCode} ${it.orderId}") }
            .onFailure { onStatus(it.message ?: "Strategy 002 entry failed") }
    }
}

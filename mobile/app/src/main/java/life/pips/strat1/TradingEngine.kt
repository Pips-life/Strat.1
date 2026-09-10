package life.pips.strat1

import life.pips.strat1.data.DirectMetaApiClient
import life.pips.strat1.data.FlashAlphaSnapshot
import life.pips.strat1.data.MetaAccount
import life.pips.strat1.data.MetaPosition
import life.pips.strat1.data.MetaSnapshot
import life.pips.strat1.data.SavedConnection
import life.pips.strat1.data.TradeSide

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
            StrategyId.STRATEGY_001 -> execute001(account, saved, snapshot, flash, symbol, price, tick.bid, tick.ask, positions, onStatus)
            StrategyId.STRATEGY_002 -> execute002(account, saved, symbol, price, positions, onStatus)
        }
    }

    private suspend fun execute001(account: MetaAccount, saved: SavedConnection, snapshot: MetaSnapshot, flash: FlashAlphaSnapshot?, symbol: String, price: Double, bid: Double, ask: Double, positions: List<MetaPosition>, onStatus: (String) -> Unit) {
        val plan = strategy001.evaluate(flash, price)
        for (p in positions) {
            val exit = strategy001.exitDecision(plan, p, price)
            if (exit.close) meta.closePosition(saved.metaApiToken, account, p.id).onSuccess { onStatus("Strategy 001 exit confirmed: ${p.id}") }.onFailure { onStatus(it.message ?: "Strategy 001 exit failed") }
        }
        if (positions.isNotEmpty() || plan.side == null || !strategy001.entryAllowed(plan, price)) return
        val target = plan.exitZone?.center ?: return
        val stop = plan.invalidation ?: return
        val entryPrice = if (plan.side == TradeSide.BUY) ask else bid
        if (plan.side == TradeSide.BUY && target <= entryPrice) return
        if (plan.side == TradeSide.SELL && target >= entryPrice) return
        meta.marketOrder(saved.metaApiToken, account, plan.side, symbol, 0.01, stopLoss = stop, takeProfit = target)
            .onSuccess { onStatus("Strategy 001 ${plan.side} confirmed: ${it.stringCode} ${it.orderId}") }
            .onFailure { onStatus(it.message ?: "Strategy 001 entry failed") }
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

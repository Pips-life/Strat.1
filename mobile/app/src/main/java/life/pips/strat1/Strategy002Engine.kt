package life.pips.strat1

import life.pips.strat1.data.MetaPosition
import life.pips.strat1.data.TickPrice
import life.pips.strat1.data.TradeSide
import kotlin.math.abs

/** Strategy 002: instant tick-to-tick Velocity Expansion. Independent of Strategy 001. */
class Strategy002Engine(private val trailPips: Double = 70.0, private val pipSize: Double = 0.01) {
    data class Sample(val time: Long, val price: Double)
    data class Plan(val side: TradeSide?, val confidence: Int, val entry: Double?, val stop: Double?, val reason: String, val velocity: Double)
    data class ExitDecision(val close: Boolean, val reason: String)

    fun evaluate(samples: List<Sample>): Plan {
        if (samples.size < 2) return Plan(null, 0, null, null, "Waiting for live tick movement.", 0.0)
        val a = samples[samples.lastIndex - 1]
        val b = samples.last()
        val dt = (b.time - a.time).coerceAtLeast(1L) / 1000.0
        val velocity = (b.price - a.price) / dt
        if (!velocity.isFinite() || abs(velocity) <= 0.0) return Plan(null, 0, b.price, null, "No directional tick movement.", velocity)
        val side = if (velocity > 0) TradeSide.BUY else TradeSide.SELL
        val distance = trailPips * pipSize
        val stop = if (side == TradeSide.BUY) b.price - distance else b.price + distance
        return Plan(side, 100, b.price, stop, "Strategy 002 live tick velocity detected — immediate directional entry.", velocity)
    }

    fun exitDecision(plan: Plan, position: MetaPosition, price: Double): ExitDecision {
        val side = when {
            position.type.uppercase().contains("BUY") || position.type.uppercase().contains("LONG") -> TradeSide.BUY
            position.type.uppercase().contains("SELL") || position.type.uppercase().contains("SHORT") -> TradeSide.SELL
            else -> null
        } ?: return ExitDecision(false, "Unknown position side")
        if (plan.side != null && plan.side != side) return ExitDecision(true, "Strategy 002 tick velocity reversed direction.")
        val stop = position.stopLoss
        if (stop.isFinite() && ((side == TradeSide.BUY && price <= stop) || (side == TradeSide.SELL && price >= stop))) return ExitDecision(true, "Strategy 002 trailing stop reached.")
        return ExitDecision(false, "Strategy 002 velocity remains aligned.")
    }

    fun trailStop(position: MetaPosition, price: Double): Double? {
        val side = when {
            position.type.uppercase().contains("BUY") || position.type.uppercase().contains("LONG") -> TradeSide.BUY
            position.type.uppercase().contains("SELL") || position.type.uppercase().contains("SHORT") -> TradeSide.SELL
            else -> null
        } ?: return null
        val distance = trailPips * pipSize
        val candidate = if (side == TradeSide.BUY) price - distance else price + distance
        return if (!position.stopLoss.isFinite()) candidate else if (side == TradeSide.BUY) maxOf(position.stopLoss, candidate) else minOf(position.stopLoss, candidate)
    }
}

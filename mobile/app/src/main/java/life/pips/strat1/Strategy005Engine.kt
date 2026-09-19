package life.pips.strat1

import life.pips.strat1.data.DirectMetaApiClient
import life.pips.strat1.data.HistoricalCandle
import life.pips.strat1.data.MetaAccount
import life.pips.strat1.data.MetaPosition
import life.pips.strat1.data.SymbolSpecification
import life.pips.strat1.data.TickPrice
import life.pips.strat1.data.TradeSide
import kotlin.math.abs
import kotlin.math.floor

/** Strategy 005: independent Woodie 4H pivots with 5M price-action entries. */
class Strategy005Engine(private val meta: DirectMetaApiClient) {
    data class Levels(val pp: Double, val r1: Double, val s1: Double, val r2: Double, val s2: Double)
    data class Plan(
        val side: TradeSide?,
        val confidence: Int,
        val entry: Double?,
        val target: Double?,
        val stop: Double?,
        val reason: String,
        val trigger: String,
        val levels: Levels?,
        val rewardRisk: Double
    )

    private var levels: Levels? = null
    private var pivotSourceTime = ""
    private var lastPivotFetch = 0L
    @Volatile private var latestPlan = Plan(null, 0, null, null, null, "Waiting for Strategy 005 data.", "", null, 0.0)

    private fun woodie(c: HistoricalCandle): Levels {
        val p = (c.high + c.low + 2.0 * c.close) / 4.0
        return Levels(p, 2.0 * p - c.low, 2.0 * p - c.high, p + c.high - c.low, p - c.high + c.low)
    }

    suspend fun refresh(account: MetaAccount, token: String, symbol: String, samples: List<Strategy002Engine.Sample>): Result<Plan> = runCatching {
        val now = System.currentTimeMillis()
        if (levels == null || now - lastPivotFetch >= 60_000L) {
            val candles = meta.historicalCandles(token, account, symbol, "4h", 3).getOrThrow()
                .filter { it.high.isFinite() && it.low.isFinite() && it.close.isFinite() }
            require(candles.size >= 2) { "Strategy 005 waiting for two 4H candles." }
            val source = candles[candles.lastIndex - 1]
            if (source.time != pivotSourceTime) {
                levels = woodie(source)
                pivotSourceTime = source.time
            }
            lastPivotFetch = now
        }
        evaluateFromTicks(levels, samples).also { latestPlan = it }
    }

    fun evaluateFromTicks(current: Levels?, samples: List<Strategy002Engine.Sample>): Plan {
        if (current == null) return Plan(null, 0, null, null, null, "Waiting for closed 4H Woodie pivot.", "", null, 0.0)
        val bars = fiveMinuteBars(samples)
        if (bars.size < 3) return Plan(null, 0, null, current.pp, null, "Waiting for closed 5M price action.", "", current, 0.0)

        // bars.last() is the currently forming 5M candle. Entries use the
        // last fully closed candle only, preventing intrabar signal churn.
        val candle = bars[bars.lastIndex - 1]
        val prior = bars[bars.lastIndex - 2]
        val range = maxOf(current.r2 - current.pp, current.pp - current.s2, 1e-9)
        val tolerance = range * 0.15
        var side: TradeSide? = null
        var trigger = ""
        if (buyRejection(candle, current.s1, tolerance) && prior.close < current.pp) { side = TradeSide.BUY; trigger = "S1 REJECTION" }
        else if (buyRejection(candle, current.s2, tolerance) && prior.close < current.pp) { side = TradeSide.BUY; trigger = "S2 REJECTION" }
        else if (sellRejection(candle, current.r1, tolerance) && prior.close > current.pp) { side = TradeSide.SELL; trigger = "R1 REJECTION" }
        else if (sellRejection(candle, current.r2, tolerance) && prior.close > current.pp) { side = TradeSide.SELL; trigger = "R2 REJECTION" }

        if (side == null) return Plan(null, 0, candle.close, current.pp, null, "No confirmed 5M Woodie rejection.", "", current, 0.0)

        val stop = if (side == TradeSide.BUY)
            candle.low - abs(candle.close - candle.low) * 0.10
        else
            candle.high + abs(candle.high - candle.close) * 0.10
        val risk = abs(candle.close - stop)
        val reward = abs(current.pp - candle.close)
        val rr = if (risk > 0.0) reward / risk else 0.0
        if (rr < 1.0) return Plan(null, 0, candle.close, current.pp, stop, "5M rejection found, but PP target has insufficient room.", trigger, current, rr)
        return Plan(side, 90, candle.close, current.pp, stop, "5M $trigger confirmed; target is the fixed 4H Woodie PP.", trigger, current, rr)
    }

    fun latest(): Plan = latestPlan

    fun exitOnPivot(position: MetaPosition, price: Double, target: Double): Boolean {
        val side = positionSide(position) ?: return false
        return (side == TradeSide.BUY && price >= target) || (side == TradeSide.SELL && price <= target)
    }

    fun positionSide(position: MetaPosition): TradeSide? = when {
        position.type.contains("BUY", true) || position.type.contains("LONG", true) -> TradeSide.BUY
        position.type.contains("SELL", true) || position.type.contains("SHORT", true) -> TradeSide.SELL
        else -> null
    }

    fun sizeForMaxRisk(balance: Double, entry: Double, stop: Double, tick: TickPrice, spec: SymbolSpecification, maxRiskFraction: Double = 0.05): Double {
        if (!balance.isFinite() || balance <= 0.0 || !entry.isFinite() || !stop.isFinite()) return 0.0
        if (!spec.tickSize.isFinite() || spec.tickSize <= 0.0 || !tick.lossTickValue.isFinite() || tick.lossTickValue <= 0.0) return 0.0
        if (spec.volumeStep <= 0.0 || spec.minVolume <= 0.0 || spec.maxVolume < spec.minVolume) return 0.0
        val stopDistance = abs(entry - stop)
        val riskBudget = balance * maxRiskFraction.coerceIn(0.0, 0.05)
        val lossPerVolume = (stopDistance / spec.tickSize) * tick.lossTickValue
        if (stopDistance <= 0.0 || lossPerVolume <= 0.0 || riskBudget <= 0.0) return 0.0
        val raw = riskBudget / lossPerVolume
        val volume = floor(minOf(raw, spec.maxVolume) / spec.volumeStep) * spec.volumeStep
        return if (volume >= spec.minVolume) volume else 0.0
    }

    private fun buyRejection(c: Bar, level: Double, tolerance: Double): Boolean {
        val range = (c.high - c.low).coerceAtLeast(1e-9)
        return c.low <= level + tolerance && c.close > level && c.close > c.open && (minOf(c.open, c.close) - c.low) / range >= 0.25
    }

    private fun sellRejection(c: Bar, level: Double, tolerance: Double): Boolean {
        val range = (c.high - c.low).coerceAtLeast(1e-9)
        return c.high >= level - tolerance && c.close < level && c.close < c.open && (c.high - maxOf(c.open, c.close)) / range >= 0.25
    }

    private data class Bar(val open: Double, val high: Double, val low: Double, val close: Double)

    private fun fiveMinuteBars(samples: List<Strategy002Engine.Sample>): List<Bar> {
        if (samples.isEmpty()) return emptyList()
        val sorted = samples.sortedBy { it.time }
        val grouped = sorted.groupBy { Math.floorDiv(it.time, 300_000L) }
        return grouped.toSortedMap().values.mapNotNull { bucket ->
            val first = bucket.firstOrNull() ?: return@mapNotNull null
            val prices = bucket.map { it.price }.filter { it.isFinite() && it > 0.0 }
            if (prices.isEmpty()) null else Bar(prices.first(), prices.maxOrNull()!!, prices.minOrNull()!!, prices.last())
        }
    }
}

package life.pips.strat1

import life.pips.strat1.data.FlashAlphaSnapshot
import life.pips.strat1.data.GexStrike
import life.pips.strat1.data.MetaPosition
import life.pips.strat1.data.TradeSide
import kotlin.math.abs
import kotlin.math.max

/**
 * Strategy 001 only.
 *
 * Entries/exits are derived from the options-flow/GEX market map. No 1-minute
 * candle reversal is used to decide an entry or an exit. Candles belong to
 * other strategies and are deliberately not part of this engine's inputs.
 */
class Strategy001Engine {
    data class Zone(val kind: Kind, val lower: Double, val upper: Double, val center: Double, val strength: Double, val source: String) {
        enum class Kind { SUPPORT, RESISTANCE }
        fun contains(price: Double): Boolean = price >= lower && price <= upper
    }

    data class Plan(
        val side: TradeSide?,
        val confidence: Int,
        val entryZone: Zone?,
        val exitZone: Zone?,
        val invalidation: Double?,
        val reason: String,
        val zones: List<Zone>
    )

    data class ExitDecision(val close: Boolean, val reason: String)

    fun buildMarketMap(f: FlashAlphaSnapshot, price: Double): List<Zone> {
        val points = mutableListOf<Zone>()
        val sorted = f.strikes.filter { it.strike.isFinite() }.sortedBy { it.strike }
        val spacing = medianSpacing(sorted).coerceAtLeast(price * 0.0001)
        val halfWidth = spacing * 0.50

        fun add(center: Double, kind: Zone.Kind, strength: Double, source: String) {
            if (center.isFinite() && center > 0.0) points += Zone(kind, center - halfWidth, center + halfWidth, center, strength.coerceIn(0.0, 100.0), source)
        }

        if (f.putWall.isFinite()) add(f.putWall, Zone.Kind.SUPPORT, 100.0, "LIVE_PUT_WALL")
        if (f.callWall.isFinite()) add(f.callWall, Zone.Kind.RESISTANCE, 100.0, "LIVE_CALL_WALL")
        if (f.gammaFlip.isFinite()) {
            val kind = if (price >= f.gammaFlip) Zone.Kind.SUPPORT else Zone.Kind.RESISTANCE
            add(f.gammaFlip, kind, 90.0, "LIVE_GAMMA_FLIP")
        }
        if (f.zeroDteMagnet.isFinite()) {
            val kind = if (f.zeroDteMagnet <= price) Zone.Kind.SUPPORT else Zone.Kind.RESISTANCE
            add(f.zeroDteMagnet, kind, 75.0, "LIVE_MAX_PAIN_MAGNET")
        }

        val strongest = sorted.sortedByDescending { abs(it.netGex) }.take(8)
        strongest.forEach { s ->
            val kind = when {
                s.strike < price -> Zone.Kind.SUPPORT
                s.strike > price -> Zone.Kind.RESISTANCE
                else -> if (s.netGex >= 0) Zone.Kind.SUPPORT else Zone.Kind.RESISTANCE
            }
            val strength = (50.0 + 50.0 * abs(s.netGex) / max(1.0, abs(strongest.maxOfOrNull { it.netGex } ?: 1.0))).coerceIn(50.0, 95.0)
            add(s.strike, kind, strength, "GEX_STRIKE")
        }
        return points.distinctBy { "${it.kind}:${"%.5f".format(it.center)}" }.sortedBy { it.center }
    }

    fun evaluate(f: FlashAlphaSnapshot?, price: Double): Plan {
        if (f == null || !price.isFinite()) return Plan(null, 0, null, null, null, "Waiting for GEX data and live price.", emptyList())
        val zones = buildMarketMap(f, price)
        val support = zones.filter { it.kind == Zone.Kind.SUPPORT && it.center <= price }.maxByOrNull { it.center }
        val resistance = zones.filter { it.kind == Zone.Kind.RESISTANCE && it.center >= price }.minByOrNull { it.center }
        if (support == null && resistance == null) return Plan(null, 0, null, null, null, "No GEX-derived support/resistance zones are available.", zones)

        val flow = f.flowDirection.uppercase()
        val negativeGamma = f.liveGex.isFinite() && f.liveGex < 0.0
        val aboveFlip = f.gammaFlip.isFinite() && price > f.gammaFlip
        val belowFlip = f.gammaFlip.isFinite() && price < f.gammaFlip
        val bullishFlow = flow.contains("LONG") || flow.contains("CALL") || flow.contains("BULL")
        val bearishFlow = flow.contains("SHORT") || flow.contains("PUT") || flow.contains("BEAR")

        val longScore = score(true, bullishFlow, bearishFlow, negativeGamma, aboveFlip, support, resistance, price)
        val shortScore = score(false, bullishFlow, bearishFlow, negativeGamma, belowFlip, support, resistance, price)
        val side = when {
            longScore >= 70 && longScore >= shortScore + 8 -> TradeSide.BUY
            shortScore >= 70 && shortScore >= longScore + 8 -> TradeSide.SELL
            else -> null
        }
        if (side == null) return Plan(null, max(longScore, shortScore), null, null, null, "GEX zones exist, but Strategy 001 directional confluence is not strong enough.", zones)

        val entry = if (side == TradeSide.BUY) support else resistance
        val target = if (side == TradeSide.BUY) resistance else support
        if (entry == null || target == null) return Plan(null, max(longScore, shortScore), entry, target, null, "Strategy 001 requires both a data-derived entry zone and opposing exit zone.", zones)
        val invalidation = if (side == TradeSide.BUY) entry.lower else entry.upper
        val score = if (side == TradeSide.BUY) longScore else shortScore
        val reason = if (side == TradeSide.BUY) "GEX support entry zone → opposing GEX resistance exit zone." else "GEX resistance entry zone → opposing GEX support exit zone."
        return Plan(side, score, entry, target, invalidation, reason, zones)
    }

    fun entryAllowed(plan: Plan, price: Double): Boolean = plan.side != null && plan.entryZone?.contains(price) == true

    fun exitDecision(plan: Plan, position: MetaPosition, price: Double): ExitDecision {
        val side = positionSide(position) ?: return ExitDecision(false, "Unknown position side")
        val target = plan.exitZone
        val invalidation = plan.invalidation
        if (target != null && ((side == TradeSide.BUY && price >= target.lower) || (side == TradeSide.SELL && price <= target.upper))) {
            return ExitDecision(true, "Strategy 001 data-derived exit zone reached (${target.source}).")
        }
        if (invalidation != null && ((side == TradeSide.BUY && price <= invalidation) || (side == TradeSide.SELL && price >= invalidation))) {
            return ExitDecision(true, "Strategy 001 data-derived entry-zone invalidation reached.")
        }
        if (plan.side != null && plan.side != side && plan.confidence >= 70) {
            return ExitDecision(true, "Strategy 001 GEX/flow map has changed to the opposite directional setup.")
        }
        return ExitDecision(false, "Position remains inside the Strategy 001 data-derived plan.")
    }

    fun positionSide(p: MetaPosition): TradeSide? = when {
        p.type.uppercase().contains("BUY") || p.type.uppercase().contains("LONG") -> TradeSide.BUY
        p.type.uppercase().contains("SELL") || p.type.uppercase().contains("SHORT") -> TradeSide.SELL
        else -> null
    }

    private fun score(long: Boolean, bullish: Boolean, bearish: Boolean, negativeGamma: Boolean, flipAligned: Boolean, support: Zone?, resistance: Zone?, price: Double): Int {
        var s = 50
        if (if (long) bullish else bearish) s += 20
        if (if (long) bearish else bullish) s -= 20
        if (negativeGamma) s += 10
        if (flipAligned) s += 10
        val zone = if (long) support else resistance
        if (zone != null) {
            val distance = abs(price - zone.center)
            val width = max(abs(zone.upper - zone.lower), price * 0.0001)
            if (distance <= width * 2.0) s += 10
        }
        return s.coerceIn(0, 100)
    }

    private fun medianSpacing(strikes: List<GexStrike>): Double {
        val diffs = strikes.zipWithNext().map { abs(it.second.strike - it.first.strike) }.filter { it > 0.0 }.sorted()
        if (diffs.isEmpty()) return 1.0
        return diffs[diffs.size / 2]
    }
}

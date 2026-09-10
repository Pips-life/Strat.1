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
 * Full QOF chain:
 * live GEX + options flow + IV/skew + Greeks/OI -> market map -> directional
 * confluence -> entry zone -> opposing data-derived target -> broker TP/SL -> loop.
 * No 1-minute candle reversal logic is used here.
 */
class Strategy001Engine {
    data class Zone(val kind: Kind, val lower: Double, val upper: Double, val center: Double, val strength: Double, val source: String) {
        enum class Kind { SUPPORT, RESISTANCE }
        fun contains(price: Double): Boolean = price.isFinite() && price >= lower && price <= upper
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
        val sorted = f.strikes.filter { it.strike.isFinite() && it.strike > 0.0 }.sortedBy { it.strike }
        val spacing = medianSpacing(sorted).coerceAtLeast(price.coerceAtLeast(1.0) * 0.0001)
        val halfWidth = spacing * 0.50

        fun add(center: Double, kind: Zone.Kind, strength: Double, source: String) {
            if (center.isFinite() && center > 0.0) {
                points += Zone(kind, center - halfWidth, center + halfWidth, center, strength.coerceIn(0.0, 100.0), source)
            }
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
        val maxAbsGex = strongest.maxOfOrNull { abs(it.netGex) }?.coerceAtLeast(1.0) ?: 1.0
        strongest.forEach { s ->
            val kind = when {
                s.strike < price -> Zone.Kind.SUPPORT
                s.strike > price -> Zone.Kind.RESISTANCE
                else -> if (s.netGex >= 0.0) Zone.Kind.SUPPORT else Zone.Kind.RESISTANCE
            }
            val strength = (50.0 + 45.0 * abs(s.netGex) / maxAbsGex).coerceIn(50.0, 95.0)
            add(s.strike, kind, strength, "GEX_STRIKE")
        }
        return points.distinctBy { "${it.kind}:${"%.5f".format(it.center)}" }.sortedBy { it.center }
    }

    fun evaluate(f: FlashAlphaSnapshot?, price: Double): Plan {
        if (f == null || !price.isFinite()) return Plan(null, 0, null, null, null, "Waiting for live GEX, IV skew and options data.", emptyList())
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

        val skew = f.skew25d
        val bearishSkew = skew.isFinite() && skew >= 1.0
        val bullishSkew = skew.isFinite() && skew <= -1.0
        val putCallVolumeBearish = f.putCallVolumeRatio.isFinite() && f.putCallVolumeRatio >= 1.15
        val putCallVolumeBullish = f.putCallVolumeRatio.isFinite() && f.putCallVolumeRatio <= 0.85
        val putCallOiBearish = f.putCallOiRatio.isFinite() && f.putCallOiRatio >= 1.15
        val putCallOiBullish = f.putCallOiRatio.isFinite() && f.putCallOiRatio <= 0.85
        val localSkew = f.optionsAroundPriceSkew(price)
        val localBearishSkew = localSkew != null && localSkew >= 1.0
        val localBullishSkew = localSkew != null && localSkew <= -1.0

        val longScore = score(true, bullishFlow, bearishFlow, negativeGamma, aboveFlip, bullishSkew || localBullishSkew, putCallVolumeBullish, putCallOiBullish, support, resistance, price)
        val shortScore = score(false, bullishFlow, bearishFlow, negativeGamma, belowFlip, bearishSkew || localBearishSkew, putCallVolumeBearish, putCallOiBearish, support, resistance, price)

        val side = when {
            longScore >= 70 && longScore >= shortScore + 8 -> TradeSide.BUY
            shortScore >= 70 && shortScore >= longScore + 8 -> TradeSide.SELL
            else -> null
        }
        if (side == null) return Plan(null, max(longScore, shortScore), null, null, null, "QOF confluence not strong enough: GEX + flow + IV skew + OI/volume are not aligned.", zones)

        val entry = if (side == TradeSide.BUY) support else resistance
        val target = if (side == TradeSide.BUY) resistance else support
        if (entry == null || target == null) return Plan(null, max(longScore, shortScore), entry, target, null, "QOF requires both a data-derived entry zone and opposing exit zone.", zones)

        val invalidation = if (side == TradeSide.BUY) entry.lower else entry.upper
        val score = if (side == TradeSide.BUY) longScore else shortScore
        val skewText = if (skew.isFinite()) "25D skew=${"%.2f".format(skew)}" else "25D skew unavailable"
        val localText = localSkew?.let { ", local strike skew=${"%.2f".format(it)}" } ?: ""
        val reason = if (side == TradeSide.BUY) {
            "QOF BUY: flow/flip/skew/OI-volume confluence; $skewText$localText. Support entry -> opposing resistance target."
        } else {
            "QOF SELL: flow/flip/skew/OI-volume confluence; $skewText$localText. Resistance entry -> opposing support target."
        }
        return Plan(side, score, entry, target, invalidation, reason, zones)
    }

    fun entryAllowed(plan: Plan, price: Double): Boolean = plan.side != null && plan.entryZone != null && plan.exitZone != null && plan.entryZone.contains(price)

    fun exitDecision(plan: Plan, position: MetaPosition, price: Double): ExitDecision {
        val side = positionSide(position) ?: return ExitDecision(false, "Unknown position side")
        if (!price.isFinite()) return ExitDecision(false, "Waiting for valid live price")
        val target = position.takeProfit.takeIf { it.isFinite() && it > 0.0 } ?: plan.exitZone?.center
        val stop = position.stopLoss.takeIf { it.isFinite() && it > 0.0 } ?: plan.invalidation
        if (target != null && ((side == TradeSide.BUY && price >= target) || (side == TradeSide.SELL && price <= target))) return ExitDecision(true, "QOF take-profit reached at ${format(target)}.")
        if (stop != null && ((side == TradeSide.BUY && price <= stop) || (side == TradeSide.SELL && price >= stop))) return ExitDecision(true, "QOF invalidation/stop reached at ${format(stop)}.")
        if (plan.side != null && plan.side != side && plan.confidence >= 80) return ExitDecision(true, "QOF rotated to a high-confidence opposite setup.")
        return ExitDecision(false, "Position remains active toward its fixed QOF target.")
    }

    fun positionSide(p: MetaPosition): TradeSide? = when {
        p.type.uppercase().contains("BUY") || p.type.uppercase().contains("LONG") -> TradeSide.BUY
        p.type.uppercase().contains("SELL") || p.type.uppercase().contains("SHORT") -> TradeSide.SELL
        else -> null
    }

    private fun score(long: Boolean, bullish: Boolean, bearish: Boolean, negativeGamma: Boolean, flipAligned: Boolean, skewAligned: Boolean, volumeAligned: Boolean, oiAligned: Boolean, support: Zone?, resistance: Zone?, price: Double): Int {
        var s = 50
        if (if (long) bullish else bearish) s += 20
        if (if (long) bearish else bullish) s -= 20
        if (negativeGamma) s += 10
        if (flipAligned) s += 15
        if (skewAligned) s += 15
        if (volumeAligned) s += 10
        if (oiAligned) s += 5
        val zone = if (long) support else resistance
        if (zone != null) {
            val distance = abs(price - zone.center)
            val width = max(abs(zone.upper - zone.lower), price.coerceAtLeast(1.0) * 0.0001)
            if (distance <= width * 2.0) s += 5
        }
        val opposingZone = if (long) resistance else support
        if (opposingZone != null) s += 5
        return s.coerceIn(0, 100)
    }

    private fun medianSpacing(strikes: List<GexStrike>): Double {
        val diffs = strikes.zipWithNext().map { abs(it.second.strike - it.first.strike) }.filter { it > 0.0 }.sorted()
        if (diffs.isEmpty()) return 1.0
        return diffs[diffs.size / 2]
    }

    private fun format(value: Double): String = "%.5f".format(value)
}

private fun FlashAlphaSnapshot.optionsAroundPriceSkew(price: Double): Double? {
    val byStrike = options.filter { it.strike.isFinite() && it.iv.isFinite() && abs(it.strike - price) <= price.coerceAtLeast(1.0) * 0.08 }.groupBy { it.strike }
    val values = byStrike.mapNotNull { (_, contracts) ->
        val call = contracts.firstOrNull { it.type == "C" || it.type.equals("CALL", true) }
        val put = contracts.firstOrNull { it.type == "P" || it.type.equals("PUT", true) }
        if (call != null && put != null) (put.iv - call.iv) * 100.0 else null
    }
    return values.takeIf { it.isNotEmpty() }?.average()
}

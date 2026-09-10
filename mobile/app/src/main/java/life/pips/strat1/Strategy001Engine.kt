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
 * QOF confluence chain:
 * GEX regime + gamma flip + 25D IV skew + local strike skew + options flow
 * + put/call volume + put/call OI + data-derived zones -> entry -> opposing
 * zone target -> broker TP/SL. No 1-minute candle reversal logic is used.
 */
class Strategy001Engine {
    data class Zone(val kind: Kind, val lower: Double, val upper: Double, val center: Double, val strength: Double, val source: String) {
        enum class Kind { SUPPORT, RESISTANCE }
        fun contains(price: Double): Boolean = price.isFinite() && price >= lower && price <= upper
    }

    data class Confluence(
        val gex: Int,
        val gammaFlip: Int,
        val skew25d: Int,
        val localSkew: Int,
        val flow: Int,
        val volume: Int,
        val oi: Int,
        val zone: Int,
        val target: Int
    ) {
        val total: Int get() = (gex + gammaFlip + skew25d + localSkew + flow + volume + oi + zone + target).coerceIn(0, 100)
        fun summary(side: TradeSide): String = buildString {
            append(if (side == TradeSide.BUY) "BUY" else "SELL")
            append(" QOF ").append(total).append("/100: ")
            append("GEX ").append(gex).append("/15, ")
            append("Flip ").append(gammaFlip).append("/15, ")
            append("25D skew ").append(skew25d).append("/15, ")
            append("local skew ").append(localSkew).append("/10, ")
            append("flow ").append(flow).append("/15, ")
            append("volume ").append(volume).append("/10, ")
            append("OI ").append(oi).append("/10, ")
            append("entry zone ").append(zone).append("/5, ")
            append("target zone ").append(target).append("/5")
        }
    }

    data class Plan(
        val side: TradeSide?,
        val confidence: Int,
        val entryZone: Zone?,
        val exitZone: Zone?,
        val invalidation: Double?,
        val reason: String,
        val zones: List<Zone>,
        val confluence: Confluence? = null
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
        val bullishFlow = flow.contains("LONG") || flow.contains("CALL") || flow.contains("BULL") || flow.contains("BUY")
        val bearishFlow = flow.contains("SHORT") || flow.contains("PUT") || flow.contains("BEAR") || flow.contains("SELL")

        // FlashAlpha defines 25D skew as put IV - call IV: positive = put skew/downside protection.
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

        val long = score(
            side = TradeSide.BUY,
            bullishFlow = bullishFlow,
            bearishFlow = bearishFlow,
            negativeGamma = negativeGamma,
            flipAligned = aboveFlip,
            skewAligned = bullishSkew,
            localSkewAligned = localBullishSkew,
            volumeAligned = putCallVolumeBullish,
            oiAligned = putCallOiBullish,
            support = support,
            resistance = resistance,
            price = price
        )
        val short = score(
            side = TradeSide.SELL,
            bullishFlow = bullishFlow,
            bearishFlow = bearishFlow,
            negativeGamma = negativeGamma,
            flipAligned = belowFlip,
            skewAligned = bearishSkew,
            localSkewAligned = localBearishSkew,
            volumeAligned = putCallVolumeBearish,
            oiAligned = putCallOiBearish,
            support = support,
            resistance = resistance,
            price = price
        )

        val side = when {
            long.total >= 70 && long.total >= short.total + 8 -> TradeSide.BUY
            short.total >= 70 && short.total >= long.total + 8 -> TradeSide.SELL
            else -> null
        }
        if (side == null) {
            val best = maxOf(long.total, short.total)
            return Plan(null, best, null, null, null, "QOF WAIT: ${best}/100. Need aligned GEX, gamma-flip, IV-skew, flow, OI/volume and zones.", zones, if (long.total >= short.total) long else short)
        }

        val selected = if (side == TradeSide.BUY) long else short
        val entry = if (side == TradeSide.BUY) support else resistance
        val target = if (side == TradeSide.BUY) resistance else support
        if (entry == null || target == null) return Plan(null, selected.total, entry, target, null, "QOF requires both a data-derived entry zone and opposing exit zone.", zones, selected)

        val invalidation = if (side == TradeSide.BUY) entry.lower else entry.upper
        val skewText = if (skew.isFinite()) "25D skew=${"%.2f".format(skew)}" else "25D skew unavailable"
        val localText = localSkew?.let { ", local strike skew=${"%.2f".format(it)}" } ?: ""
        val reason = selected.summary(side) + ". " +
            if (side == TradeSide.BUY) "Bullish confluence: flow/flip/skew/OI-volume align; $skewText$localText. Support entry -> opposing resistance target."
            else "Bearish confluence: flow/flip/skew/OI-volume align; $skewText$localText. Resistance entry -> opposing support target."
        return Plan(side, selected.total, entry, target, invalidation, reason, zones, selected)
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

    private fun score(
        side: TradeSide,
        bullishFlow: Boolean,
        bearishFlow: Boolean,
        negativeGamma: Boolean,
        flipAligned: Boolean,
        skewAligned: Boolean,
        localSkewAligned: Boolean,
        volumeAligned: Boolean,
        oiAligned: Boolean,
        support: Zone?,
        resistance: Zone?,
        price: Double
    ): Confluence {
        val flowScore = when {
            side == TradeSide.BUY && bullishFlow -> 15
            side == TradeSide.SELL && bearishFlow -> 15
            side == TradeSide.BUY && bearishFlow -> 0
            side == TradeSide.SELL && bullishFlow -> 0
            else -> 5
        }
        val gexScore = if (negativeGamma) 15 else 5
        val flipScore = if (flipAligned) 15 else 0
        val skewScore = if (skewAligned) 15 else 0
        val localScore = if (localSkewAligned) 10 else 0
        val volumeScore = if (volumeAligned) 10 else 0
        val oiScore = if (oiAligned) 10 else 0
        val entryZone = if (side == TradeSide.BUY) support else resistance
        val targetZone = if (side == TradeSide.BUY) resistance else support
        val zoneScore = if (entryZone != null && zoneNearPrice(entryZone, price)) 5 else 0
        val targetScore = if (targetZone != null) 5 else 0
        return Confluence(gexScore, flipScore, skewScore, localScore, flowScore, volumeScore, oiScore, zoneScore, targetScore)
    }

    private fun zoneNearPrice(zone: Zone, price: Double): Boolean {
        val distance = abs(price - zone.center)
        val width = max(abs(zone.upper - zone.lower), price.coerceAtLeast(1.0) * 0.0001)
        return distance <= width * 2.0
    }

    private fun medianSpacing(strikes: List<GexStrike>): Double {
        val diffs = strikes.zipWithNext().map { abs(it.second.strike - it.first.strike) }.filter { it > 0.0 }.sorted()
        if (diffs.isEmpty()) return 1.0
        return diffs[diffs.size / 2]
    }

    private fun format(value: Double): String = "%.5f".format(value)
}

private fun FlashAlphaSnapshot.optionsAroundPriceSkew(price: Double): Double? {
    val byStrike = options.filter {
        it.strike.isFinite() && it.iv.isFinite() &&
            abs(it.strike - price) <= price.coerceAtLeast(1.0) * 0.08
    }.groupBy { it.strike }
    val values = byStrike.mapNotNull { (_, contracts) ->
        val call = contracts.firstOrNull { it.type == "C" || it.type.equals("CALL", true) }
        val put = contracts.firstOrNull { it.type == "P" || it.type.equals("PUT", true) }
        if (call != null && put != null) (put.iv - call.iv) * 100.0 else null
    }
    return values.takeIf { it.isNotEmpty() }?.average()
}

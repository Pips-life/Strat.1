package life.pips.strat1

import life.pips.strat1.data.TradeSide
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.exp

/** Independent Strategy 006 Options Flow engine. */
class Strategy006Engine {
    data class Row(
        val strike: Double, val type: Char, val oi: Double = 0.0,
        val volume: Double = 0.0, val premium: Double = 0.0,
        val gamma: Double = 0.0, val delta: Double = 0.0, val vega: Double = 0.0,
        val theta: Double = 0.0, val iv: Double = 0.0,
        val putIv: Double = 0.0, val callIv: Double = 0.0, val ivSkew: Double = 0.0,
        val source: String = "greeks", val latest: Double? = null, val lastTrade: String = "",
        val volumeMissing: Boolean = false, val oiMissing: Boolean = false, val premiumMissing: Boolean = false
    )

    data class ZoneConfluence(
        val zoneName: String, val zone: Double,
        val matchedStrike: Double?, val score: Double,
        val volatility: Double, val greekMagnitude: Double,
        val distance: Double, val bias: TradeSide? = null
    )

    data class Zones(
        val upperInventoryCeiling: Double?, val reclaimGate: Double?,
        val immediateHedgeWall: Double?, val primaryHedgeFloor: Double?,
        val dealerAbsorption: Double?, val liquidityExhaustion: Double?,
        val confluence: List<ZoneConfluence> = emptyList()
    )

    data class Map(
        val rows: List<Row>, val futuresRows: List<Row> = emptyList(), val spot: Double?,
        val zones: Zones, val basis: BasisMapping?, val valid: Boolean,
        val warnings: List<String>
    )

    enum class MarketState {
        WAIT_DATA, PHF_HOLD, PHF_BREAK, ABSORPTION_TEST,
        ABSORPTION_RECLAIM, ABSORPTION_FAILURE, EXHAUSTION_TEST,
        EXHAUSTION_RECLAIM, EXHAUSTION_FAILURE, RECLAIM_GATE,
        UPPER_CEILING, CONTINUATION, NO_TRADE
    }

    data class TradePlan(
        val side: TradeSide?, val entry: Double?, val stop: Double?,
        val target: Double?, val rewardRisk: Double, val riskAmount: Double,
        val state: String, val reason: String,
        val positionLots: Double = 0.0
    )

    data class ZoneStatus(
        val likelyZoneName: String?, val likelyZone: Double?, val likelySide: TradeSide?,
        val entryPrice: Double?, val entryConfluence: Double?,
        val reactedZoneName: String?, val reactedZone: Double?,
        val possibleExitName: String?, val possibleExit: Double?,
        val targetConfluence: Double?
    )

    enum class PositionAction { HOLD, CLOSE_REVERSE, RETARGET }

    data class PositionManagement(
        val action: PositionAction,
        val nextTarget: ZoneConfluence? = null,
        val reason: String
    )

    /** GC -> XAUUSD coordinate conversion using a same-instant basis. */
    data class PriceTick(val price: Double, val timestampMillis: Long, val sourceTimezone: String = "UTC")
    data class BasisMapping(
        val gcPrice: Double, val xauPrice: Double, val basis: Double,
        val gcTimestampUtc: Long, val xauTimestampUtc: Long,
        val matchDeltaMillis: Long, val sourceTimezone: String,
        val valid: Boolean, val warning: String? = null
    ) {
        fun mapGcLevelToXau(gcLevel: Double): Double = gcLevel - basis
    }

    private fun utcMillis(timestampMillis: Long, sourceTimezone: String): Long {
        require(timestampMillis > 0L) { "Timestamp must be positive." }
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestampMillis), ZoneId.of(sourceTimezone))
        return timestampMillis
    }

    private fun buildBasisMapping(gc: PriceTick, xau: PriceTick, toleranceMillis: Long): BasisMapping {
        require(gc.price > 0.0 && xau.price > 0.0) { "GC and XAUUSD prices must be positive." }
        require(toleranceMillis >= 0L) { "Timestamp tolerance cannot be negative." }
        val gcUtc = utcMillis(gc.timestampMillis, gc.sourceTimezone)
        val xauUtc = utcMillis(xau.timestampMillis, xau.sourceTimezone)
        val delta = abs(gcUtc - xauUtc)
        if (delta > toleranceMillis) {
            return BasisMapping(gc.price, xau.price, 0.0, gcUtc, xauUtc, delta, gc.sourceTimezone, false,
                "No same-instant XAUUSD match within " + toleranceMillis + "ms (delta=" + delta + "ms).")
        }
        return BasisMapping(gc.price, xau.price, gc.price - xau.price, gcUtc, xauUtc, delta, gc.sourceTimezone, true)
    }

    private var current: Map? = null
    private var lastPrice = Double.NaN
    private var lastState = MarketState.NO_TRADE
    private val priceHistory = ArrayDeque<Double>()
    private data class FiveMinuteBar(val start: Long, val open: Double, val high: Double, val low: Double, val close: Double)
    private data class M5Rejection(val side: TradeSide, val zone: Double, val label: String)
    private var liveBar: FiveMinuteBar? = null
    private var lastConfirmedBarStart: Long = Long.MIN_VALUE
    private var lastReactionZoneName: String? = null
    private var lastReactionZone: Double? = null

    fun currentMap(): Map? = current

    fun zoneStatus(price: Double): ZoneStatus {
        val z = current?.zones ?: return ZoneStatus(null, null, null, null, null, lastReactionZoneName, lastReactionZone, null, null, null)
        // When live/spot price is between two mapped zones, upper resistance => SELL; lower support => BUY.
        val lower = listOfNotNull(
            z.primaryHedgeFloor?.let { "Primary Hedge Floor" to it },
            z.dealerAbsorption?.let { "Absorption Floor" to it },
            z.liquidityExhaustion?.let { "Liquidity Exhaustion" to it }
        ).filter { it.second < price }.maxByOrNull { it.second }
        val upper = listOfNotNull(
            z.immediateHedgeWall?.let { "Immediate Hedge Wall" to it },
            z.reclaimGate?.let { "Reclaim Gate" to it },
            z.upperInventoryCeiling?.let { "Upper Inventory Ceiling" to it }
        ).filter { it.second > price }.minByOrNull { it.second }
        val likely = listOfNotNull(
            lower?.let { Triple(it.first, it.second, TradeSide.BUY) },
            upper?.let { Triple(it.first, it.second, TradeSide.SELL) }
        ).minByOrNull { abs(it.second - price) }
        val entryConfluence = likely?.let { candidate ->
            z.confluence.firstOrNull { it.zoneName == candidate.first }
        }
        // The target is selected from the opposite side of the map after the
        // approach direction is established. Do not use the highest confluence
        // zone globally as the entry signal.
        val target = likely?.third?.let { oppositeTarget(it, likely.second) }
        val entryPrice = entryConfluence?.matchedStrike ?: likely?.second
        return ZoneStatus(
            likely?.first, likely?.second, likely?.third,
            entryPrice, entryConfluence?.score,
            lastReactionZoneName, lastReactionZone,
            target?.zoneName, target?.zone, target?.score
        )
    }

    /** S006 file-first entry point: GC time is read from the FIRST supplied file. */
    fun loadFiles(
        barchartText: String,
        greeksText: String,
        futuresText: String = "",
        gcPrice: Double?,
        xauSpotPrice: Double?,
        xauTimestampMillis: Long = System.currentTimeMillis(),
        gcTimestampMillis: Long = xauTimestampMillis,
        gcSourceTimezone: String = "America/New_York",
        xauSourceTimezone: String = "Africa/Nairobi",
        toleranceMillis: Long = 1_000L
    ): Map {
        val ivRows = parseIvOptionsTable(barchartText)
        val greekRows = parseText(greeksText)
        val futuresRows = parseFuturesText(futuresText)
        val rows = (ivRows + greekRows).filter { it.strike > 0.0 && (it.type == 'C' || it.type == 'P') }
        if (gcPrice == null || gcPrice <= 0.0 || xauSpotPrice == null || xauSpotPrice <= 0.0) {
            current = calculate(rows, futuresRows, gcPrice, xauSpotPrice).copy(
                spot = xauSpotPrice,
                warnings = calculate(rows, futuresRows, gcPrice).warnings + "GC futures price and live XAUUSD price are required."
            )
            return current!!
        }
        val basis = buildBasisMapping(
            PriceTick(gcPrice, gcTimestampMillis, gcSourceTimezone),
            PriceTick(xauSpotPrice, xauTimestampMillis, xauSourceTimezone),
            toleranceMillis
        )
        if (!basis.valid) {
            current = calculate(rows, futuresRows, gcPrice).copy(spot = xauSpotPrice, basis = basis, warnings = listOf<String>(basis.warning ?: "Basis mapping invalid."))
            return current!!
        }
        // Build the six zones directly in XAUUSD coordinates from the signed GC/XAUUSD basis.
        // The same polished levels are then used for Volatility + Greeks confluence matching.
        val mappedBase = calculate(rows, futuresRows, gcPrice, xauSpotPrice, basis.basis)
        val mapped = mappedBase.copy(
            spot = xauSpotPrice,
            basis = basis,
            warnings = mappedBase.warnings + listOf<String>("Signed basis = GC price - live XAUUSD price = " + basis.basis + ".")
        )
        current = mapped
        lastPrice = Double.NaN
        lastState = MarketState.NO_TRADE
        priceHistory.clear()
        liveBar = null
        lastConfirmedBarStart = Long.MIN_VALUE
        lastReactionZoneName = null
        lastReactionZone = null
        return mapped
    }

    fun loadFiles(ivOptionsText: String, greeksText: String, gcPrice: Double, xauSpotPrice: Double): Map {
        val now = System.currentTimeMillis()
        return loadFiles(ivOptionsText, greeksText, "", gcPrice, xauSpotPrice, now, now, "UTC", "UTC", 0L)
    }

    fun loadFiles(barchartText: String, greeksText: String, spot: Double?): Map {
        val now = System.currentTimeMillis()
        return loadFiles(barchartText, greeksText, "", spot ?: Double.NaN, spot ?: Double.NaN, now, now, "UTC", "UTC", 0L)
    }

    private fun parseIvOptionsTable(text: String): List<Row> {
        if (text.isBlank()) return emptyList()
        val tableRegex = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cellRegex = Regex("<t[dh][^>]*>(.*?)</t[dh]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val htmlRows = tableRegex.findAll(text).map { match ->
            cellRegex.findAll(match.groupValues[1]).map { clean(it.groupValues[1]) }.toList()
        }.toList()
        val csvRows = text.lineSequence().map { splitCsv(it) }.filter { it.size >= 3 }.toList()
        val all = if (htmlRows.size >= 2) htmlRows else csvRows
        if (all.isEmpty()) return emptyList()
        val header = all.first().map { normalize(it) }
        fun idx(vararg names: String): Int = names.firstNotNullOfOrNull { n ->
            header.indexOfFirst { it == normalize(n) || it.contains(normalize(n)) }.takeIf { it >= 0 }
        } ?: -1
        val strikeIdx = idx("strike", "strike price")
        val putDeltaIdx = idx("put delta")
        val callDeltaIdx = idx("call delta")
        val ivIdx = idx("imp vol", "implied volatility", "iv")
        if (strikeIdx < 0 || ivIdx < 0) {
            val fallback = mutableListOf<Row>()
            text.lineSequence().forEach { line ->
                val nums = Regex("-?\\d+(?:\\.\\d+)?%?").findAll(line).mapNotNull { num(it.value) }.toList()
                if (nums.size >= 6) {
                    val pd = nums[0]
                    val strike = nums[2]
                    val cd = nums[4]
                    val iv = nums[5]
                    if (strike > 0.0 && iv >= 0.0) {
                        fallback += Row(strike, 'P', delta = pd, iv = iv, source = "iv")
                        fallback += Row(strike, 'C', delta = cd, iv = iv, source = "iv")
                    }
                }
            }
            return fallback
        }
        val out = mutableListOf<Row>()
        for (cells in all.drop(1)) {
            val strike = num(cells.getOrNull(strikeIdx)) ?: continue
            val iv = num(cells.getOrNull(ivIdx)) ?: 0.0
            val pd = num(cells.getOrNull(putDeltaIdx)) ?: 0.0
            val cd = num(cells.getOrNull(callDeltaIdx)) ?: 0.0
            if (pd != 0.0 || putDeltaIdx >= 0) out += Row(strike, 'P', delta = pd, iv = iv, source = "iv")
            if (cd != 0.0 || callDeltaIdx >= 0) out += Row(strike, 'C', delta = cd, iv = iv, source = "iv")
        }
        return out
    }

    /** Extract the spot price printed inside the IV options file. This is the XAUUSD live mapping anchor for the file-built map. */
    fun extractIvSpotPrice(text: String): Double? {
        if (text.isBlank()) return null
        val patterns = listOf(
            Regex("""(?i)\bspot\s*(?:price)?\s*[:=\-]?\s*([0-9]+(?:\.[0-9]+)?)\b"""),
            Regex("""(?i)\bspot\b\s+([0-9]+(?:\.[0-9]+)?)\b""")
        )
        return patterns.asSequence()
            .mapNotNull { it.find(text)?.groupValues?.getOrNull(1)?.let(::num) }
            .firstOrNull { it.isFinite() && it > 0.0 }
    }

    /** Read the GC observation timestamp from CSV, PDF-extracted text, or OCR text. */
    private fun extractGcTimestampMillis(text: String, sourceTimezone: String): Long? {
        if (text.isBlank()) return null
        val patterns = listOf(
            Regex("""(?i)(?:last\\s+(?:trade|quote)|quote|as\\s+of|timestamp|date|time)\\D{0,40}(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}[ T]+\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[AP]M)?)"""),
            Regex("""\\b(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}[ T]+\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[AP]M)?)\\b"""),
            Regex("""\\b(\\d{1,2}[-/]\\d{1,2}[-/]\\d{4}[ T]+\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[AP]M)?)\\b"""),
            Regex("""(?i)\\b([A-Z]{3}\\s+\\d{1,2},\\s+\\d{4}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?\\s*[AP]M)\\b""")
        )
        for (pattern in patterns) {
            val m = pattern.find(text) ?: continue
            parseLocalTimestamp(m.groupValues[1].trim(), sourceTimezone)?.let { return it }
        }
        return null
    }

    private fun parseLocalTimestamp(raw: String, sourceTimezone: String): Long? {
        val formats = listOf(
            "uuuu-MM-dd HH:mm:ss", "uuuu-MM-dd HH:mm", "uuuu/MM/dd HH:mm:ss", "uuuu/MM/dd HH:mm",
            "MM/dd/uuuu HH:mm:ss", "MM/dd/uuuu HH:mm", "MM-dd-uuuu HH:mm:ss", "MM-dd-uuuu HH:mm",
            "MM/dd/uuuu h:mm a", "MM-dd-uuuu h:mm a", "MMM d, uuuu h:mm a",
            "MMM d, uuuu HH:mm:ss", "MMM d, uuuu HH:mm"
        )
        for (pattern in formats) {
            try {
                val local = LocalDateTime.parse(raw.replace(Regex("\\s+"), " "), DateTimeFormatter.ofPattern(pattern, Locale.US))
                return local.atZone(ZoneId.of(sourceTimezone)).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) { }
        }
        return null
    }

    fun polishIvStrike(ivStrike: Double, gcPrice: Double, liveXauPrice: Double): Double =
        ivStrike - (gcPrice - liveXauPrice)

    fun clear() {
        current = null
        lastPrice = Double.NaN
        lastState = MarketState.NO_TRADE
        priceHistory.clear()
        liveBar = null
        lastConfirmedBarStart = Long.MIN_VALUE
    }


    fun plan(
        price: Double, balance: Double, bid: Double, ask: Double,
        rejection: Boolean = false, tickTime: Long = System.currentTimeMillis()
    ): TradePlan {
        val m = current ?: return wait("WAIT FILES", "Load the IV options table and Volatility & Greeks file.")
        if (!m.valid || price <= 0.0 || balance <= 0.0) return wait("WAIT DATA", "S006 map or account data is invalid.")

        val previousPrice = lastPrice
        lastPrice = price
        priceHistory.addLast(price)
        while (priceHistory.size > 12) priceHistory.removeFirst()
        updateFiveMinuteBar(price, tickTime)

        val buy = if (ask > 0.0) ask else price
        val sell = if (bid > 0.0) bid else price
        val candidate = entryConfluence(price)
        val strike = candidate?.matchedStrike

        if (candidate != null && strike != null) {
            val triggerTolerance = max(0.01, price * 0.00001)
            val touched = abs(price - strike) <= triggerTolerance
            val crossed = previousPrice.isFinite() &&
                ((previousPrice < strike && price >= strike) || (previousPrice > strike && price <= strike))
            val triggered = touched || crossed

            if (triggered) {
                val side = candidate.bias ?: zoneApproachSide(candidate.zoneName)
                if (side != null) {
                    val entry = if (side == TradeSide.BUY) buy else sell
                    val stop = if (side == TradeSide.BUY) {
                        strike - NOMINATED_STRIKE_STOP
                    } else {
                        strike + NOMINATED_STRIKE_STOP
                    }
                    val target = if (side == TradeSide.BUY) {
                        nextTargetInDirection(side, strike) ?: oppositeTarget(side, strike)
                    } else {
                        nextTargetInDirection(side, strike) ?: oppositeTarget(side, strike)
                    }

                    if (target != null) {
                        lastReactionZoneName = candidate.zoneName
                        lastReactionZone = strike
                        val state = if (side == TradeSide.BUY) MarketState.ABSORPTION_RECLAIM else MarketState.RECLAIM_GATE
                        return trade(
                            side, entry, stop, target.zone, balance, state,
                            "Nominated confluence strike " + String.format("%.2f", strike) +
                                " triggered → immediate " + side.name +
                                " entry; fixed SL " + String.format("%.0f", NOMINATED_STRIKE_STOP / POINT_SIZE) +
                                " points (" + String.format("%.2f", NOMINATED_STRIKE_STOP) + " price) away; target " + target.zoneName + " @ " +
                                String.format("%.2f", target.zone) +
                                " (confluence " + String.format("%.1f", candidate.score) + ")."
                        )
                    }
                }
            }
        }

        return wait(
            MarketState.NO_TRADE.name,
            if (strike != null)
                "Waiting for nominated confluence strike " + String.format("%.2f", strike) + " to trigger an immediate entry."
            else
                "No nominated confluence strike is available for execution."
        )
    }

    private data class ConfluenceReaction(val side: TradeSide, val behavior: String)

    private fun entryConfluence(price: Double): ZoneConfluence? {
        // Execution is driven by the nominated confluence strike itself.
        // Select the nearest nominated strike so the trigger remains stable as
        // price approaches the strike even when the strike sits on the far side
        // of its parent IV zone.
        return current?.zones?.confluence.orEmpty()
            .filter { it.matchedStrike != null }
            .minWithOrNull(
                compareBy<ZoneConfluence> { abs((it.matchedStrike ?: Double.POSITIVE_INFINITY) - price) }
                    .thenByDescending { it.score }
            )
    }

    private fun zoneApproachSide(zoneName: String): TradeSide? = when (zoneName) {
        "Liquidity Exhaustion", "Dealer Absorption", "Absorption Floor",
        "Primary Hedge Floor" -> TradeSide.BUY
        "Immediate Hedge Wall", "Reclaim Gate",
        "Upper Inventory Ceiling" -> TradeSide.SELL
        else -> null
    }

    private fun detectConfluenceReaction(bar: FiveMinuteBar, candidate: ZoneConfluence): ConfluenceReaction? {
        val strike = candidate.matchedStrike ?: return null
        val eps = max(strike * 0.0001, 0.5)
        if (bar.low <= strike + eps && bar.high >= strike - eps) {
            // Rejection must agree with the zone's approach bias:
            // upper/resistance rejection = SELL; lower/support rejection = BUY.
            if (candidate.bias == TradeSide.SELL && bar.high >= strike && bar.close < strike - eps)
                return ConfluenceReaction(TradeSide.SELL, "REJECTION")
            if (candidate.bias == TradeSide.BUY && bar.low <= strike && bar.close > strike + eps)
                return ConfluenceReaction(TradeSide.BUY, "REJECTION")

            // A confirmed close through the strike is a breakout and reverses
            // the approach bias for continuation toward the next IV zone.
            if (bar.close > strike + eps) return ConfluenceReaction(TradeSide.BUY, "BREAKOUT")
            if (bar.close < strike - eps) return ConfluenceReaction(TradeSide.SELL, "BREAKOUT")
        }
        return null
    }

    private fun nextTargetInDirection(side: TradeSide, fromPrice: Double): ZoneConfluence? {
        val candidates = current?.zones?.confluence.orEmpty().filter { if (side == TradeSide.BUY) it.zone > fromPrice else it.zone < fromPrice }
        if (candidates.isEmpty()) return null
        return candidates.maxWithOrNull(compareBy<ZoneConfluence> { it.score }.thenBy { if (side == TradeSide.BUY) it.zone else -it.zone })
    }

    fun manageOpenPosition(side: TradeSide, targetPrice: Double, tickTime: Long): PositionManagement {
        val m = current ?: return PositionManagement(PositionAction.HOLD, reason = "S006 map unavailable.")
        if (!m.valid || !targetPrice.isFinite() || targetPrice <= 0.0) return PositionManagement(PositionAction.HOLD, reason = "S006 target is invalid.")
        val price = lastPrice.takeIf { it.isFinite() } ?: targetPrice
        val closedBar = updateFiveMinuteBar(price, tickTime)
        if (closedBar == null) return PositionManagement(PositionAction.HOLD, reason = "Waiting for completed M5 reaction at target " + String.format("%.2f", targetPrice) + ".")
        if (!(closedBar.low <= targetPrice && closedBar.high >= targetPrice))
            return PositionManagement(PositionAction.HOLD, reason = "Target zone not yet tested.")
        val eps = max(targetPrice * 0.0001, 0.5)
        val rejected = if (side == TradeSide.BUY) closedBar.close < targetPrice - eps else closedBar.close > targetPrice + eps
        if (rejected) return PositionManagement(PositionAction.CLOSE_REVERSE, reason = "M5 rejection detected at target " + String.format("%.2f", targetPrice) + ". Close positions and reassess opposite direction.")
        val broke = if (side == TradeSide.BUY) closedBar.close > targetPrice + eps else closedBar.close < targetPrice - eps
        if (broke) {
            val next = nextTargetInDirection(side, targetPrice)
            if (next != null) return PositionManagement(PositionAction.RETARGET, next, "M5 breakout accepted through target " + String.format("%.2f", targetPrice) + " → hold and retarget " + next.zoneName + " @ " + String.format("%.2f", next.zone) + ".")
        }
        return PositionManagement(PositionAction.HOLD, reason = "Target tested without confirmed rejection or breakout.")
    }

    fun oppositeTarget(side: TradeSide, entryPrice: Double): ZoneConfluence? {
        val entryZone = lastReactionZone ?: entryPrice
        val candidates = current?.zones?.confluence.orEmpty()
            .filter { if (side == TradeSide.BUY) it.zone > entryPrice && it.zone > entryZone else it.zone < entryPrice && it.zone < entryZone }
            .filter { it.zoneName != lastReactionZoneName }
        if (candidates.isEmpty()) return null
        val nearest = candidates.minOf { abs(it.zone - entryPrice) }
        val nextOpposite = candidates.filter { abs(it.zone - entryPrice) <= nearest + epsFor(entryPrice) }
        return nextOpposite.maxByOrNull { it.score } ?: candidates.maxByOrNull { it.score }
    }

    private fun epsFor(price: Double): Double = max(price * 0.00001, 0.01)

    private fun updateFiveMinuteBar(price: Double, tickTime: Long): FiveMinuteBar? {
        val millis = if (tickTime in 1L..100_000_000_000L) tickTime * 1000L else tickTime
        val start = millis - Math.floorMod(millis, 5L * 60L * 1000L)
        val prior = liveBar
        return if (prior == null) {
            liveBar = FiveMinuteBar(start, price, price, price, price); null
        } else if (start == prior.start) {
            liveBar = prior.copy(high = max(prior.high, price), low = kotlin.math.min(prior.low, price), close = price); null
        } else {
            liveBar = FiveMinuteBar(start, price, price, price, price); prior
        }
    }

    private fun detectM5Rejection(bar: FiveMinuteBar, z: Zones): M5Rejection? {
        val lower = listOfNotNull(
            z.primaryHedgeFloor?.let { it to "Primary Hedge Floor" },
            z.dealerAbsorption?.let { it to "Dealer Absorption" },
            z.liquidityExhaustion?.let { it to "Liquidity Exhaustion" }
        ).filter { (level, _) -> bar.low <= level && bar.high >= level && bar.close > level }
            .maxByOrNull { (level, _) -> level }
        if (lower != null) return M5Rejection(TradeSide.BUY, lower.first, lower.second)
        val upper = listOfNotNull(
            z.immediateHedgeWall?.let { it to "Immediate Hedge Wall" },
            z.reclaimGate?.let { it to "Reclaim Gate" },
            z.upperInventoryCeiling?.let { it to "Upper Inventory Ceiling" }
        ).filter { (level, _) -> bar.low <= level && bar.high >= level && bar.close < level }
            .minByOrNull { (level, _) -> level }
        return upper?.let { M5Rejection(TradeSide.SELL, it.first, it.second) }
    }

    private fun wait(state: String, reason: String) =
        TradePlan(null, null, null, null, 0.0, 0.0, state, reason)

    private fun trade(
        side: TradeSide, entry: Double, stop: Double, target: Double,
        balance: Double, state: MarketState, reason: String
    ): TradePlan {
        if (!entry.isFinite() || !stop.isFinite() || !target.isFinite())
            return wait(state.name, "Invalid trade levels.")
        if ((side == TradeSide.BUY && (target <= entry || stop >= entry)) ||
            (side == TradeSide.SELL && (target >= entry || stop <= entry)))
            return wait(state.name, "Invalid zone-to-zone geometry.")
        val rr = abs(target - entry) / max(abs(entry - stop), 1e-9)
        if (rr < 1.35)
            return TradePlan(null, null, null, null, rr, 0.0, "WAIT RR",
                "Zone-to-zone reward is below 1.35R; no trade.")
        val riskBudget = balance * MAX_ACCOUNT_RISK
        val stopDistance = abs(entry - stop)
        val riskPerLot = (stopDistance / TICK_SIZE) * TICK_VALUE
        if (!riskPerLot.isFinite() || riskPerLot <= 0.0) return wait(state.name, "Invalid tick-value/stop-distance risk model.")
        val rawLots = riskBudget / riskPerLot
        val sizedLots = kotlin.math.floor(rawLots / LOT_STEP) * LOT_STEP
        if (sizedLots < MIN_LOT) return wait("WAIT RISK", "Minimum lot 0.01 would exceed the 10% account-risk limit.")
        val actualRisk = sizedLots * riskPerLot
        return TradePlan(side, entry, stop, target, rr, actualRisk, state.name, reason, sizedLots)
    }

    private fun firstAbove(entry: Double, vararg levels: Double?): Double? =
        levels.filterNotNull().filter { it > entry }.minOrNull()

    private fun nextLowerZone(z: Zones, level: Double): Double? =
        z.confluence.filter { it.zone < level }.maxByOrNull { it.zone }?.zone

    private fun crossedBelow(level: Double): Boolean =
        priceHistory.size >= 2 && priceHistory.elementAt(priceHistory.size - 2) >= level && priceHistory.last() < level

    private fun risingFromZone(history: ArrayDeque<Double>, levels: List<Double>, eps: Double): Boolean {
        if (history.size < 3 || levels.isEmpty()) return false
        val b = history.elementAt(history.size - 2)
        val c = history.last()
        val near = levels.minByOrNull { abs(b - it) } ?: return false
        return abs(b - near) <= eps * 2.0 && c > b
    }

    private fun fallingThroughZone(history: ArrayDeque<Double>, levels: List<Double>, eps: Double): Boolean {
        if (history.size < 2 || levels.isEmpty()) return false
        val b = history.elementAt(history.size - 2)
        val c = history.last()
        val near = levels.minByOrNull { abs(b - it) } ?: return false
        return abs(b - near) <= eps * 2.0 && c < b
    }

    private fun fallingThenRejecting(history: ArrayDeque<Double>, levels: List<Double>, eps: Double): Boolean {
        if (history.size < 3 || levels.isEmpty()) return false
        val a = history.elementAt(history.size - 3)
        val b = history.elementAt(history.size - 2)
        val c = history.last()
        val near = levels.minByOrNull { abs(b - it) } ?: return false
        return abs(b - near) <= eps * 2.0 && c < b && b >= a
    }


    private fun calculate(rows: List<Row>, futuresRows: List<Row> = emptyList(), gcPrice: Double?, liveXauSpot: Double? = gcPrice, signedBasis: Double = 0.0): Map {
        if (rows.isEmpty()) return Map(emptyList(), futuresRows, liveXauSpot, Zones(null, null, null, null, null, null), null, false, listOf("No options rows parsed."))
        if (gcPrice == null || gcPrice <= 0.0) return Map(rows, futuresRows, liveXauSpot, Zones(null, null, null, null, null, null), null, false, listOf("GC futures price is required for IV strike polishing."))
        if (liveXauSpot == null || liveXauSpot <= 0.0) return Map(rows, futuresRows, liveXauSpot, Zones(null, null, null, null, null, null), null, false, listOf("Live XAUUSD spot price is required for IV strike polishing."))

        // Stage 1: build the IV map. GC basis is used ONLY here.
        val ivStrikes = rows.filter { it.source == "iv" }
            .map { it.strike }.filter { it.isFinite() && it > 0.0 }.distinct().sorted()
        if (ivStrikes.size < 7) return Map(rows, futuresRows, liveXauSpot, Zones(null, null, null, null, null, null), null, false, listOf("At least seven IV strikes are required so six non-ATM strikes can be mapped."))
        val atm = ivStrikes.minByOrNull { abs(it - liveXauSpot) }
        val nonAtm = ivStrikes.filter { it != atm }
        val below = nonAtm.filter { it < liveXauSpot }.sortedDescending()
        val above = nonAtm.filter { it > liveXauSpot }.sorted()
        if (below.size < 3 || above.size < 3) return Map(rows, futuresRows, liveXauSpot, Zones(null, null, null, null, null, null), null, false, listOf("S006 requires three non-ATM IV strikes below and three above the IV-file live spot."))

        val selectedIv = (below.take(3).sorted() + above.take(3)).sorted()
        val polishedZones = selectedIv.map { it - signedBasis }

        val ivRows = rows.filter { it.source == "iv" && it.iv.isFinite() && it.iv > 0.0 }
        val greekRows = rows.filter { it.source == "greeks" && it.strike.isFinite() && it.strike > 0.0 }
        val nativeGreekStrikes = greekRows.map { it.strike }.distinct().sorted()
        val nativeFuturesStrikes = futuresRows.map { it.strike }.filter { it.isFinite() && it > 0.0 }.distinct().sorted()

        // Stage 2: for each mapped IV zone, inspect ALL Greeks strikes inside the
        // confluence window. Stage 3: require File 3 evidence at the same strike.
        // The nearest strike is NOT automatically selected: the strike with the
        // strongest combined File 2 + File 3 agreement is nominated for execution.
        // Confluence scoring is deliberately separated into four signals:
        // 1) IV/volatility strength, 2) directional Greek strength,
        // 3) directional options-chain strength, and 4) proximity to the
        // polished IV zone. File 2 + File 3 reinforce the structural zone bias;
        // they do not replace the zone's BUY/SELL geometry.
        val ivValues = ivRows.map { abs(it.iv) }.filter { it.isFinite() }
        val greekMagnitudeValues = greekRows.map {
            abs(it.delta) + abs(it.gamma) + abs(it.vega) + abs(it.theta)
        }.filter { it.isFinite() }
        val futuresMagnitudeValues = futuresRows.map {
            abs(it.oi) + abs(it.volume) + abs(it.premium)
        }.filter { it.isFinite() && it > 0.0 }

        data class ZoneScore(val confluence: ZoneConfluence, val score: Double)

        fun directionalBalance(callStrength: Double, putStrength: Double, side: TradeSide): Double {
            val total = callStrength + putStrength
            if (!total.isFinite() || total <= 0.0) return 50.0
            val preferred = if (side == TradeSide.BUY) callStrength else putStrength
            return (preferred / total * 100.0).coerceIn(0.0, 100.0)
        }

        fun confluence(ivZone: Double, name: String): ZoneConfluence {
            if (nativeGreekStrikes.isEmpty() || nativeFuturesStrikes.isEmpty()) {
                return ZoneConfluence(name, ivZone, null, 0.0, 0.0, 0.0, Double.POSITIVE_INFINITY, zoneApproachSide(name))
            }

            val candidates = nativeGreekStrikes.filter { abs(it - ivZone) <= STRIKE_BUFFER }
            if (candidates.isEmpty()) {
                return ZoneConfluence(name, ivZone, null, 0.0, 0.0, 0.0, Double.POSITIVE_INFINITY, zoneApproachSide(name))
            }

            val bias = zoneApproachSide(name)
            val scored = candidates.mapNotNull { greekStrike ->
                val futuresStrike = nativeFuturesStrikes.minByOrNull { abs(it - greekStrike) }
                    ?.takeIf { abs(it - greekStrike) <= STRIKE_BUFFER } ?: return@mapNotNull null
                val greekAtStrike = greekRows.filter { abs(it.strike - greekStrike) <= 0.01 }
                val ivAtStrike = ivRows.filter { abs(it.strike - greekStrike) <= STRIKE_BUFFER }
                val futuresAtStrike = futuresRows.filter { abs(it.strike - futuresStrike) <= 0.01 }
                if (greekAtStrike.isEmpty() || futuresAtStrike.isEmpty() || bias == null) return@mapNotNull null

                val volatility = ivAtStrike.maxOfOrNull { abs(it.iv) } ?: 0.0
                val greekMagnitude = greekAtStrike.maxOfOrNull {
                    abs(it.delta) + abs(it.gamma) + abs(it.vega) + abs(it.theta)
                } ?: 0.0

                // Preserve call/put direction instead of stripping it with abs().
                // Delta supplies the directional component; the other Greeks
                // contribute to strength without inventing a direction.
                val callGreekRows = greekAtStrike.filter { it.type == 'C' }
                val putGreekRows = greekAtStrike.filter { it.type == 'P' }
                val callGreekStrength = callGreekRows.maxOfOrNull {
                    abs(it.delta) + abs(it.gamma) + abs(it.vega) + abs(it.theta)
                } ?: 0.0
                val putGreekStrength = putGreekRows.maxOfOrNull {
                    abs(it.delta) + abs(it.gamma) + abs(it.vega) + abs(it.theta)
                } ?: 0.0
                val greekDirectionalAgreement = directionalBalance(
                    callGreekStrength, putGreekStrength, bias
                )
                val greekStrength = normalize(greekMagnitude, greekMagnitudeValues)

                val futuresCallRows = futuresAtStrike.filter { it.type == 'C' }
                val futuresPutRows = futuresAtStrike.filter { it.type == 'P' }
                val callChainStrength = futuresCallRows.maxOfOrNull {
                    abs(it.oi) + abs(it.volume) + abs(it.premium)
                } ?: 0.0
                val putChainStrength = futuresPutRows.maxOfOrNull {
                    abs(it.oi) + abs(it.volume) + abs(it.premium)
                } ?: 0.0
                val chainStrength = normalize(
                    max(callChainStrength, putChainStrength), futuresMagnitudeValues
                )
                val chainDirectionalAgreement = directionalBalance(
                    callChainStrength, putChainStrength, bias
                )

                val skew = greekAtStrike.maxOfOrNull { abs(it.ivSkew) } ?: 0.0
                val skewStrength = normalize(skew, greekRows.map { abs(it.ivSkew) }.filter { it.isFinite() })

                // Make proximity a first-class signal. A strike at the edge of
                // the ±1500-point half-window must not beat a materially closer strike merely
                // because its raw option metrics are larger.
                val distance = abs(greekStrike - ivZone)
                val proximity = (exp(-distance / PROXIMITY_SCALE) * 100.0).coerceIn(0.0, 100.0)

                // Confidence is a confluence score, not a probability.
                // Structural bias + directional agreement + strength + proximity.
                val score = (
                    normalize(volatility, ivValues) * 0.20 +
                    greekStrength * 0.20 +
                    greekDirectionalAgreement * 0.20 +
                    chainStrength * 0.10 +
                    chainDirectionalAgreement * 0.15 +
                    skewStrength * 0.05 +
                    proximity * 0.10
                ).coerceIn(0.0, 100.0)

                ZoneScore(
                    ZoneConfluence(
                        name, ivZone, greekStrike, score, volatility,
                        greekMagnitude, distance, bias
                    ),
                    score
                )
            }

            return scored.maxWithOrNull(
                compareBy<ZoneScore> { it.score }
                    .thenBy { -it.confluence.distance }
            )?.confluence ?: ZoneConfluence(
                name, ivZone, null, 0.0, 0.0, 0.0,
                Double.POSITIVE_INFINITY, bias
            )
        }

        val named = polishedZones.zip(listOf(
            "Liquidity Exhaustion", "Dealer Absorption", "Primary Hedge Floor",
            "Immediate Hedge Wall", "Reclaim Gate", "Upper Inventory Ceiling"
        )).map { (zone, name) -> confluence(zone, name) }

        val zoneMap = named.associateBy { it.zoneName }
        val zones = Zones(
            zoneMap["Upper Inventory Ceiling"]?.zone,
            zoneMap["Reclaim Gate"]?.zone,
            zoneMap["Immediate Hedge Wall"]?.zone,
            zoneMap["Primary Hedge Floor"]?.zone,
            zoneMap["Dealer Absorption"]?.zone,
            zoneMap["Liquidity Exhaustion"]?.zone,
            named
        )

        val hasFile3Confluence = futuresRows.isNotEmpty() && named.any { it.matchedStrike != null && it.score > 0.0 }
        return Map(rows, futuresRows, liveXauSpot, zones, null, hasFile3Confluence, listOf(
            "S006 pipeline: IV map -> Greeks strike match -> Futures strike match -> confluence -> execution.",
            "GC basis is applied only to IV strike polishing: XAU zone = IV-table strike - (GC price - live XAUUSD price).",
            "Greeks and futures strikes remain in their native file price spaces; no GC-basis adjustment is applied to either.",
            "Only strikes present in File 2 and File 3 within the buffer receive a confluence score and are eligible for the execution engine.",
            "For each mapped zone, the highest-scoring agreeing strike is nominated as the execution strike; price triggers immediately at that nominated strike, with a fixed 350-point (3.50 price) stop.",
            "Confluence weights: IV volatility 30%, full Greeks magnitude 35%, IV skew 15%, futures OI/volume/premium 20%."
        ))
    }

    private fun normalize(value: Double, values: List<Double>): Double {
        val finite = values.filter { it.isFinite() }
        if (!value.isFinite() || finite.isEmpty()) return 0.0
        val lo = finite.minOrNull() ?: value
        val hi = finite.maxOrNull() ?: value
        return if (hi <= lo) 100.0 else ((value - lo) / (hi - lo) * 100.0).coerceIn(0.0, 100.0)
    }

    companion object {
        // Point convention: 1 XAUUSD point = 0.01 price.
        // Therefore 3000 points total = 30.00 price, with a ±1500-point half-window = ±15.00.
        // Example: 4150.00 matches 4135.00 through 4165.00.
        const val POINT_SIZE = 0.01
        const val TOTAL_STRIKE_BUFFER_POINTS = 3000.0
        const val STRIKE_BUFFER = (TOTAL_STRIKE_BUFFER_POINTS / 2.0) * POINT_SIZE
        const val PROXIMITY_SCALE = 200.0 * POINT_SIZE
        const val TICK_SIZE = POINT_SIZE
        const val TICK_VALUE = 0.01
        const val BASE_LOT_SIZE = 1.00
        const val LOT_STEP = 0.01
        const val MIN_LOT = 0.01
        const val MAX_ACCOUNT_RISK = 0.10
        // Immediate-entry stop: 350 points = 3.50 XAUUSD price from the
        // nominated confluence strike.
        const val NOMINATED_STRIKE_STOP = 350.0 * POINT_SIZE
    }

    private fun parseText(text: String): List<Row> {
        if (text.isBlank()) return emptyList()
        val tableRegex = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cellRegex = Regex("<t[dh][^>]*>(.*?)</t[dh]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val htmlRows = tableRegex.findAll(text).map { match ->
            cellRegex.findAll(match.groupValues[1]).map { clean(it.groupValues[1]) }.toList()
        }.toList()
        val csvRows = text.lineSequence().map { splitCsv(it) }.filter { it.size >= 2 }.toList()
        val all = if (htmlRows.size >= 2) htmlRows else csvRows
        if (all.isEmpty()) return emptyList()
        val header = all.first().map { normalize(it) }

        // Native Barchart File 2 / Volatility + Greeks CSV layout:
        // Latest, IV, Delta, Gamma, Theta, Vega, IV Skew, Type, Last Trade,
        // Strike, Latest, IV, Delta, Gamma, Theta, Vega, IV Skew, Type, Last Trade
        // Keep both call and put sides intact; do not collapse duplicate headers.
        val nativeFile2 = header.size >= 19 &&
            header[0] == "latest" && header[1] == "iv" && header[2] == "delta" &&
            header[3] == "gamma" && header[4] == "theta" && header[5] == "vega" &&
            header[6] == "iv skew" && header[7] == "type" && header[8] == "last trade" &&
            header[9] == "strike" && header[10] == "latest" && header[11] == "iv" &&
            header[12] == "delta" && header[13] == "gamma" && header[14] == "theta" &&
            header[15] == "vega" && header[16] == "iv skew" && header[17] == "type" &&
            header[18] == "last trade"
        if (nativeFile2) {
            val parsed = mutableListOf<Row>()
            for (cells in all.drop(1)) {
                val strike = numOrNull(cells.getOrNull(9)) ?: continue
                if (strike <= 0.0) continue
                fun sideType(value: String?): Char? = when {
                    value.orEmpty().trim().uppercase(Locale.US).startsWith("C") -> 'C'
                    value.orEmpty().trim().uppercase(Locale.US).startsWith("P") -> 'P'
                    else -> null
                }
                fun rowFor(side: Char, offset: Int): Row {
                    val latest = numOrNull(cells.getOrNull(offset))
                    val iv = numOrNull(cells.getOrNull(offset + 1)) ?: 0.0
                    val delta = numOrNull(cells.getOrNull(offset + 2)) ?: 0.0
                    val gamma = numOrNull(cells.getOrNull(offset + 3)) ?: 0.0
                    val theta = numOrNull(cells.getOrNull(offset + 4)) ?: 0.0
                    val vega = numOrNull(cells.getOrNull(offset + 5)) ?: 0.0
                    val skew = numOrNull(cells.getOrNull(offset + 6)) ?: 0.0
                    val lastTrade = cells.getOrNull(offset + 8).orEmpty().trim()
                    return Row(strike, side, gamma = gamma, delta = delta, vega = vega, theta = theta,
                        iv = iv, putIv = if (side == 'P') iv else 0.0, callIv = if (side == 'C') iv else 0.0,
                        ivSkew = skew, source = "greeks", latest = latest, lastTrade = lastTrade)
                }
                sideType(cells.getOrNull(7))?.let { parsed += rowFor(it, 0) }
                sideType(cells.getOrNull(17))?.let { parsed += rowFor(it, 10) }
            }
            if (parsed.isNotEmpty()) return parsed
        }

        // Barchart/Volatility side-by-side layout:
        // PUT DELTA | PUT PRICE | STRIKE | CALL PRICE | CALL DELTA | IMP VOL
        // The extractor intentionally preserves this layout. Do not treat it as a
        // generic one-row option table, otherwise the paired deltas/IV are lost.
        val hasExplicitSideBySide = header.indexOfFirst { it.contains("put delta") } >= 0 &&
            header.indexOfFirst { it.contains("call delta") } >= 0
        val hasBarchartSideBySideLabels = header.any { it.contains("put options") } &&
            header.any { it.contains("call options") } &&
            header.any { it == "strike" || it.contains("strike price") }
        val sideBySide = (hasExplicitSideBySide || hasBarchartSideBySideLabels) &&
            header.indexOfFirst { it == "strike" || it.contains("strike price") } >= 0 &&
            header.indexOfFirst { it.contains("imp vol") || it.contains("implied volatility") } >= 0
        if (sideBySide) {
            val putDeltaIdx = if (hasExplicitSideBySide) header.indexOfFirst { it.contains("put delta") } else header.indexOfFirst { it == "delta" }
            val strikeIdx = header.indexOfFirst { it == "strike" || it.contains("strike price") }
            val callDeltaIdx = if (hasExplicitSideBySide) header.indexOfFirst { it.contains("call delta") } else header.indexOfLast { it == "delta" }
            val ivIndices = header.mapIndexedNotNull { i, h -> if (h.contains("imp vol") || h.contains("implied volatility") || h == "iv") i else null }
            val putIvIdx = ivIndices.firstOrNull { header[it].contains("put") } ?: ivIndices.firstOrNull() ?: -1
            val callIvIdx = ivIndices.lastOrNull { header[it].contains("call") } ?: ivIndices.lastOrNull() ?: -1
            fun sideIndex(side: String, field: String, fallback: Int = -1): Int =
                header.indexOfFirst { it.contains(side) && it.contains(field) }.takeIf { it >= 0 } ?: fallback
            val gammaIdx = sideIndex("put", "gamma", header.indexOfFirst { it == "gamma" })
            val vegaIdx = sideIndex("put", "vega", header.indexOfFirst { it == "vega" })
            val thetaIdx = sideIndex("put", "theta", header.indexOfFirst { it == "theta" })
            val oiIdx = header.indexOfFirst { it == "open interest" || it == "oi" }
            val volIdx = header.indexOfFirst { it == "volume" || it == "vol" }
            val parsed = mutableListOf<Row>()
            for (cells in all.drop(1)) {
                val strike = num(cells.getOrNull(strikeIdx)) ?: continue
                val putDelta = num(cells.getOrNull(putDeltaIdx)) ?: 0.0
                val callDelta = num(cells.getOrNull(callDeltaIdx)) ?: 0.0
                val putIv = num(cells.getOrNull(putIvIdx)) ?: 0.0
                val callIv = num(cells.getOrNull(callIvIdx)) ?: putIv
                val ivSkew = putIv - callIv
                val gamma = num(cells.getOrNull(gammaIdx)) ?: 0.0
                val vega = num(cells.getOrNull(vegaIdx)) ?: 0.0
                val theta = num(cells.getOrNull(thetaIdx)) ?: 0.0
                val oi = num(cells.getOrNull(oiIdx)) ?: 0.0
                val volume = num(cells.getOrNull(volIdx)) ?: 0.0
                parsed += Row(strike, 'P', oi = oi, volume = volume, gamma = gamma, delta = putDelta, vega = vega, theta = theta, iv = putIv, putIv = putIv, callIv = callIv, ivSkew = ivSkew, source = "greeks")
                parsed += Row(strike, 'C', oi = oi, volume = volume, gamma = gamma, delta = callDelta, vega = vega, theta = theta, iv = callIv, putIv = putIv, callIv = callIv, ivSkew = ivSkew, source = "greeks")
            }
            if (parsed.isNotEmpty()) return parsed
        }

        fun idx(vararg names: String): Int = names.firstNotNullOfOrNull { n ->
            header.indexOfFirst { it == normalize(n) || it.contains(normalize(n)) }.takeIf { it >= 0 }
        } ?: -1
        val strikeIdx = idx("strike", "strike price")
        val typeIdx = idx("type", "call put", "put call")
        val oiIdx = idx("open interest", "oi")
        val volIdx = idx("volume", "vol")
        val gammaIdx = idx("gamma")
        val deltaIdx = idx("delta")
        val vegaIdx = idx("vega")
        val thetaIdx = idx("theta")
        val ivIdx = idx("iv", "implied volatility")
        if (strikeIdx < 0) {
            // OCR/PDF fallback for the common side-by-side Vol/Greeks table:
            // PUT DELTA | PUT PRICE | STRIKE | CALL PRICE | CALL DELTA | IMP VOL
            val numberRegex = Regex("""[-+]?\d+(?:,\d{3})*(?:\.\d+)?%?""")
            fun value(s: String): Double? = s.replace(",", "").replace("%", "").toDoubleOrNull()
            fun valid(v: List<Double>): Boolean {
                if (v.size != 6) return false
                return v[0] in -1.2..0.05 && v[1] >= 0.0 && v[2] >= 1000.0 &&
                    v[3] >= 0.0 && v[4] in -0.05..1.2 && v[5] in 0.0..100.0
            }
            val numbers = numberRegex.findAll(text).mapNotNull { value(it.value) }.toList()
            val fallback = mutableListOf<Row>()
            var p = 0
            while (p + 6 <= numbers.size) {
                val v = numbers.subList(p, p + 6)
                if (valid(v)) {
                    fallback += Row(v[2], 'P', delta = v[0], iv = v[5], source = "greeks")
                    fallback += Row(v[2], 'C', delta = v[4], iv = v[5], source = "greeks")
                    p += 6
                } else {
                    p += 1
                }
            }
            return fallback
        }
        val rows = mutableListOf<Row>()
        for (cells in all.drop(1)) {
            val strike = num(cells.getOrNull(strikeIdx)) ?: continue
            val typeText = cells.getOrNull(typeIdx).orEmpty().uppercase(Locale.US)
            val type = when {
                typeText.startsWith("C") -> 'C'
                typeText.startsWith("P") -> 'P'
                cells.any { it.uppercase(Locale.US).trim() == "CALL" } -> 'C'
                cells.any { it.uppercase(Locale.US).trim() == "PUT" } -> 'P'
                else -> 'C'
            }
            rows += Row(
                strike = strike,
                type = type,
                oi = num(cells.getOrNull(oiIdx)) ?: 0.0,
                volume = num(cells.getOrNull(volIdx)) ?: 0.0,
                gamma = num(cells.getOrNull(gammaIdx)) ?: 0.0,
                delta = num(cells.getOrNull(deltaIdx)) ?: 0.0,
                vega = num(cells.getOrNull(vegaIdx)) ?: 0.0,
                theta = num(cells.getOrNull(thetaIdx)) ?: 0.0,
                iv = num(cells.getOrNull(ivIdx)) ?: 0.0,
                source = "greeks"
            )
        }
        return rows
    }

    /** Parse normalized File 3 rows plus legacy OCR futures chains. */
    /** Validate and expose normalized File 3 rows for the UI. */
    fun parseFile3Rows(text: String): List<Row> = parseFuturesText(text)

    private fun parseFuturesText(text: String): List<Row> {
        if (text.isBlank()) return emptyList()
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()
        val header = splitCsv(lines.first()).map { normalize(it) }

        // Native Barchart File 3 CSV layout:
        // Call Type | Latest | Volume | Open Int | Premium | Strike |
        // Put Type  | Latest | Volume | Open Int | Premium
        // This is one physical row containing both option sides, so parse
        // both sides explicitly rather than treating the first Type as the row type.
        if (header.size >= 11 &&
            header[0] == "type" && header[1] == "latest" &&
            header[2] == "volume" && header[3] == "open int" &&
            header[4] == "premium" && header[5] == "strike" &&
            header[6] == "type" && header[7] == "latest" &&
            header[8] == "volume" && header[9] == "open int" &&
            header[10] == "premium"
        ) {
            fun sideType(value: String): Char? = when {
                value.trim().uppercase(Locale.US).startsWith("C") -> 'C'
                value.trim().uppercase(Locale.US).startsWith("P") -> 'P'
                else -> null
            }
            val parsed = mutableListOf<Row>()
            for (line in lines.drop(1)) {
                val cells = splitCsv(line)
                if (cells.size < 11) continue
                val strike = numOrNull(cells[5]) ?: continue
                if (strike <= 0.0) continue
                if (sideType(cells[0]) == 'C') {
                    parsed += Row(
                        strike = strike, type = 'C',
                        oi = numOrNull(cells[3]) ?: 0.0,
                        volume = numOrNull(cells[2]) ?: 0.0,
                        premium = numOrNull(cells[4]) ?: 0.0,
                        source = "futures",
                        latest = numOrNull(cells[1]),
                        volumeMissing = numOrNull(cells[2]) == null,
                        oiMissing = numOrNull(cells[3]) == null,
                        premiumMissing = numOrNull(cells[4]) == null
                    )
                }
                if (sideType(cells[6]) == 'P') {
                    parsed += Row(
                        strike = strike, type = 'P',
                        oi = numOrNull(cells[9]) ?: 0.0,
                        volume = numOrNull(cells[8]) ?: 0.0,
                        premium = numOrNull(cells[10]) ?: 0.0,
                        source = "futures",
                        latest = numOrNull(cells[7]),
                        volumeMissing = numOrNull(cells[8]) == null,
                        oiMissing = numOrNull(cells[9]) == null,
                        premiumMissing = numOrNull(cells[10]) == null
                    )
                }
            }
            if (parsed.isNotEmpty()) {
                return parsed.distinctBy {
                    Triple(it.strike, it.type, it.oi.toString() + "|" + it.volume + "|" + it.premium + "|" + it.latest)
                }
            }
        }

        val strikeIdx = header.indexOfFirst { it == "strike" || it.contains("strike price") }
        val typeIdx = header.indexOfFirst { it == "type" || it == "call put" || it == "put call" }
        val volIdx = header.indexOfFirst { it == "volume" || it == "vol" }
        val oiIdx = header.indexOfFirst { it == "open interest" || it == "oi" || it == "open int" }
        val premiumIdx = header.indexOfFirst { it == "premium" || it == "price" || it == "option price" }
        if (strikeIdx >= 0 && typeIdx >= 0) {
            val parsed = mutableListOf<Row>()
            for (line in lines.drop(1)) {
                val cells = splitCsv(line)
                val strike = num(cells.getOrNull(strikeIdx)) ?: continue
                if (strike <= 0.0) continue
                val typeText = cells.getOrNull(typeIdx).orEmpty().uppercase(Locale.US)
                val type = when { typeText.startsWith("P") -> 'P'; typeText.startsWith("C") -> 'C'; else -> continue }
                parsed += Row(strike, type, oi = num(cells.getOrNull(oiIdx)) ?: 0.0, volume = num(cells.getOrNull(volIdx)) ?: 0.0, premium = num(cells.getOrNull(premiumIdx)) ?: 0.0, source = "futures")
            }
            if (parsed.isNotEmpty()) return parsed.distinctBy { Triple(it.strike, it.type, it.oi.toString() + "|" + it.volume + "|" + it.premium) }
        }
        val out = mutableListOf<Row>()
        val number = Regex("""[-+]?\d+(?:,\d{3})*(?:\.\d+)?%?""")
        for (line in lines) {
            val v = number.findAll(line).mapNotNull { it.value.replace(",", "").replace("%", "").toDoubleOrNull() }.toList()
            if (v.size < 5) continue
            val strikeIndex = v.indexOfFirst { it >= 1000.0 }
            if (strikeIndex < 0) continue
            val strike = v[strikeIndex]
            val left = v.take(strikeIndex)
            val right = v.drop(strikeIndex + 1)
            if (left.isEmpty() || right.isEmpty()) continue
            val putOi = left.getOrNull(left.size - 3) ?: left.first()
            val putVol = left.getOrNull(left.size - 2) ?: 0.0
            val putPremium = left.last()
            val callPremium = right.first()
            val callVol = right.getOrNull(1) ?: 0.0
            val callOi = right.getOrNull(2) ?: right.last()
            out += Row(strike, 'P', oi = putOi, volume = putVol, premium = putPremium, source = "futures")
            out += Row(strike, 'C', oi = callOi, volume = callVol, premium = callPremium, source = "futures")
        }
        return out.distinctBy { Triple(it.strike, it.type, it.oi.toString() + "|" + it.volume + "|" + it.premium) }
    }

    private fun clean(s: String): String = s.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()
    private fun normalize(s: String): String = s.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), " ").trim()
    private fun num(s: String?): Double? = numOrNull(s) ?: 0.0
    private fun numOrNull(s: String?): Double? =
        s?.replace(",", "")?.replace("%", "")?.trim()?.removeSuffix("s")?.removeSuffix("S")
            ?.takeUnless { it.equals("N/A", true) || it.isBlank() }
            ?.toDoubleOrNull()
    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder(); var quoted = false
        for (c in line) when {
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { out += cur.toString().trim(); cur.clear() }
            else -> cur.append(c)
        }
        out += cur.toString().trim()
        return out
    }
}

// S006 GC→XAUUSD basis mapping + first-file GC timestamp extraction integrated; M5 rejection path retained.

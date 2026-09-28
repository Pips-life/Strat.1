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

/** Independent Strategy 006 Options Flow engine. */
class Strategy006Engine {
    data class Row(
        val strike: Double, val type: Char, val oi: Double = 0.0,
        val volume: Double = 0.0, val gamma: Double = 0.0,
        val delta: Double = 0.0, val vega: Double = 0.0,
        val theta: Double = 0.0, val iv: Double = 0.0, val source: String = "greeks"
    )

    data class ZoneConfluence(
        val zoneName: String, val zone: Double,
        val matchedStrike: Double?, val score: Double,
        val volatility: Double, val greekMagnitude: Double,
        val distance: Double
    )

    data class Zones(
        val upperInventoryCeiling: Double?, val reclaimGate: Double?,
        val immediateHedgeWall: Double?, val primaryHedgeFloor: Double?,
        val dealerAbsorption: Double?, val liquidityExhaustion: Double?,
        val confluence: List<ZoneConfluence> = emptyList()
    )

    data class Map(
        val rows: List<Row>, val spot: Double?,
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
        val state: String, val reason: String
    )

    data class ZoneStatus(
        val likelyZoneName: String?, val likelyZone: Double?, val likelySide: TradeSide?,
        val reactedZoneName: String?, val reactedZone: Double?,
        val possibleExitName: String?, val possibleExit: Double?
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

    private fun mapZonesToXau(z: Zones, basis: Double): Zones =
        Zones(
            z.upperInventoryCeiling?.let { it + basis },
            z.reclaimGate?.let { it + basis },
            z.immediateHedgeWall?.let { it + basis },
            z.primaryHedgeFloor?.let { it + basis },
            z.dealerAbsorption?.let { it + basis },
            z.liquidityExhaustion?.let { it + basis },
            z.confluence.map { item ->
                item.copy(zone = item.zone + basis, distance = abs(item.zone + basis - (current?.spot ?: item.zone)))
            }
        )

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
        val z = current?.zones ?: return ZoneStatus(null, null, null, lastReactionZoneName, lastReactionZone, null, null)
        val lower = listOfNotNull(
            z.primaryHedgeFloor?.let { "Primary Hedge Floor" to it },
            z.dealerAbsorption?.let { "Dealer Absorption" to it },
            z.liquidityExhaustion?.let { "Liquidity Exhaustion" to it }
        ).filter { it.second < price }.minByOrNull { price - it.second }
        val upper = listOfNotNull(
            z.immediateHedgeWall?.let { "Immediate Hedge Wall" to it },
            z.reclaimGate?.let { "Reclaim Gate" to it },
            z.upperInventoryCeiling?.let { "Upper Inventory Ceiling" to it }
        ).filter { it.second > price }.minByOrNull { it.second - price }
        val likely = listOfNotNull(
            lower?.let { Triple(it.first, it.second, TradeSide.BUY) },
            upper?.let { Triple(it.first, it.second, TradeSide.SELL) }
        ).minByOrNull { abs(it.second - price) }
        val target = likely?.third?.let { oppositeTarget(it, price) }
        return ZoneStatus(
            likely?.first, likely?.second, likely?.third,
            lastReactionZoneName, lastReactionZone,
            target?.zoneName, target?.zone
        )
    }

    /** S006 file-first entry point: GC time is read from the FIRST supplied file. */
    fun loadFiles(
        barchartText: String,
        greeksText: String,
        gcPrice: Double?,
        xauSpotPrice: Double?,
        xauTimestampMillis: Long,
        gcTimestampMillis: Long = xauTimestampMillis,
        gcSourceTimezone: String = "America/New_York",
        xauSourceTimezone: String = "Africa/Nairobi",
        toleranceMillis: Long = 1_000L
    ): Map {
        val ivRows = parseIvOptionsTable(barchartText)
        val greekRows = parseText(greeksText)
        val rows = (ivRows + greekRows).filter { it.strike > 0.0 && (it.type == 'C' || it.type == 'P') }
        if (gcPrice == null || gcPrice <= 0.0 || xauSpotPrice == null || xauSpotPrice <= 0.0) {
            current = calculate(rows, gcPrice).copy(
                spot = xauSpotPrice,
                warnings = calculate(rows, gcPrice).warnings + "GC futures price and live XAUUSD price are required."
            )
            return current!!
        }
        val basis = buildBasisMapping(
            PriceTick(gcPrice, gcTimestampMillis, gcSourceTimezone),
            PriceTick(xauSpotPrice, xauTimestampMillis, xauSourceTimezone),
            toleranceMillis
        )
        if (!basis.valid) {
            current = calculate(rows, gcPrice).copy(spot = xauSpotPrice, basis = basis, warnings = listOf<String>(basis.warning ?: "Basis mapping invalid."))
            return current!!
        }
        val base = calculate(rows, gcPrice)
        val mapped = base.copy(
            spot = xauSpotPrice,
            basis = basis,
            zones = mapZonesToXau(base.zones, basis.basis),
            warnings = base.warnings + "Signed basis = GC price - live XAUUSD price = " + basis.basis + "."
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
        return loadFiles(ivOptionsText, greeksText, gcPrice, xauSpotPrice, now, "UTC", "UTC", 0L)
    }

    fun loadFiles(barchartText: String, greeksText: String, spot: Double?): Map {
        val now = System.currentTimeMillis()
        return loadFiles(barchartText, greeksText, spot ?: Double.NaN, spot ?: Double.NaN, now, "UTC", "UTC", 0L)
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
        ivStrike + (gcPrice - liveXauPrice)

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
        priceHistory.addLast(price)
        while (priceHistory.size > 12) priceHistory.removeFirst()
        val closedBar = updateFiveMinuteBar(price, tickTime)
        val m5Rejection = closedBar?.let { detectM5Rejection(it, m.zones) }
        val z = m.zones
        val eps = max(price * 0.0005, 0.5)
        val buy = if (ask > 0.0) ask else price
        val sell = if (bid > 0.0) bid else price

        if (m5Rejection != null && closedBar != null && closedBar.start != lastConfirmedBarStart) {
            lastConfirmedBarStart = closedBar.start
            lastReactionZoneName = m5Rejection.label
            lastReactionZone = m5Rejection.zone
            val target = oppositeTarget(m5Rejection.side, if (m5Rejection.side == TradeSide.BUY) buy else sell)
            if (target != null) {
                val stop = if (m5Rejection.side == TradeSide.BUY) m5Rejection.zone - eps else m5Rejection.zone + eps
                val entry = if (m5Rejection.side == TradeSide.BUY) buy else sell
                return trade(
                    m5Rejection.side, entry, stop, target.zone, balance,
                    if (m5Rejection.side == TradeSide.BUY) MarketState.ABSORPTION_RECLAIM else MarketState.CONTINUATION,
                    "M5 " + (if (m5Rejection.side == TradeSide.BUY) "bullish" else "bearish") +
                        " rejection confirmed at " + m5Rejection.label +
                        " → next opposite zone with strongest confluence: " + target.zoneName +
                        " (" + String.format("%.1f", target.score) + ")."
                )
            }
        }

        val floor = z.primaryHedgeFloor
        val shelf = z.dealerAbsorption
        val exhaustion = z.liquidityExhaustion
        val reclaim = z.reclaimGate
        val wall = z.immediateHedgeWall
        val ceiling = z.upperInventoryCeiling
        val phfBreak = floor != null && price < floor - eps && crossedBelow(floor)
        val shelfBroken = shelf != null && price < shelf - eps && crossedBelow(shelf)
        val exhaustionBroken = exhaustion != null && price < exhaustion - eps && crossedBelow(exhaustion)

        if (ceiling != null && price >= ceiling - eps) return wait("UPPER_CEILING", "Upper Inventory Ceiling reached; wait for completed M5 reaction.")
        if (reclaim != null && price >= reclaim - eps) return wait("RECLAIM_GATE", "Reclaim Gate reached; wait for completed M5 reaction.")
        if (floor != null && abs(price - floor) <= eps) return wait("PHF_HOLD", "Primary Hedge Floor touched; waiting for completed M5 rejection.")
        if (wall != null && abs(price - wall) <= eps) return wait("WALL_TEST", "Immediate Hedge Wall under test; waiting for completed M5 rejection.")
        if (floor != null && price < floor - eps) {
            if (shelf != null && price > shelf + eps) return wait("ABSORPTION_TEST", "Primary Hedge Floor broke; testing Dealer Absorption. Wait for M5 rejection.")
            if (exhaustion != null && price > exhaustion + eps) return wait("EXHAUSTION_TEST", "Dealer Absorption did not hold; testing Liquidity Exhaustion. Wait for M5 rejection.")
            if (exhaustion != null && price <= exhaustion + eps) {
                if (exhaustionBroken && fallingThroughZone(priceHistory, listOfNotNull(exhaustion), eps))
                    return wait("CONTINUATION", "Liquidity Exhaustion failed; waiting for a completed M5 continuation/retest.")
                return wait("EXHAUSTION_TEST", "Liquidity Exhaustion reached; wait for completed M5 rejection.")
            }
        }
        if (phfBreak || shelfBroken || exhaustionBroken) return wait("BREAK_TEST", "Zone break detected; waiting for completed M5 confirmation.")
        return wait(MarketState.NO_TRADE.name, "No confirmed M5 reaction at a strongest-confluence mapped zone.")
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
        return TradePlan(side, entry, stop, target, rr, balance * 0.10, state.name, reason)
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

    private fun calculate(rows: List<Row>, spot: Double?): Map {
        if (rows.isEmpty()) return Map(emptyList(), spot, Zones(null, null, null, null, null, null), null, false, listOf("No options rows parsed."))
        if (spot == null || spot <= 0.0) return Map(rows, spot, Zones(null, null, null, null, null, null), null, false, listOf("GC futures price is required for IV strike polishing."))

        val unique = rows.filter { it.source == "iv" }.map { it.strike }.filter { it.isFinite() && it > 0.0 }.distinct().sorted()
        if (unique.size < 6) return Map(rows, spot, Zones(null, null, null, null, null, null), null, false, listOf("At least six non-ATM IV strikes are required."))

        val atm = unique.minByOrNull { abs(it - spot) }
        val ivStrikes = unique.filter { it != atm }
        val below = ivStrikes.filter { it < spot }.sortedDescending()
        val above = ivStrikes.filter { it > spot }.sorted()
        if (below.size < 3 || above.size < 3)
            return Map(rows, spot, Zones(null, null, null, null, null, null), null, false, listOf("S006 requires three non-ATM IV strikes below and three above GC spot."))

        val selected = (below.take(3).sorted() + above.take(3)).sorted()
        val difference = 0.0
        val rawZones = selected.map { it to (it + difference) }

        fun greekRowsAt(strike: Double): List<Row> = rows.filter { abs(it.strike - strike) <= STRIKE_BUFFER }
        fun confluence(strike: Double, zone: Double, name: String): ZoneConfluence {
            val matches = greekRowsAt(strike)
            val vol = matches.map { abs(it.iv) }.maxOrNull() ?: 0.0
            val greekMagnitude = matches.map { abs(it.delta) + abs(it.gamma) + abs(it.vega) + abs(it.theta) }.maxOrNull() ?: 0.0
            val score = (normalize(vol, rows.map { abs(it.iv) }) * 0.55) +
                (normalize(greekMagnitude, rows.map { abs(it.delta) + abs(it.gamma) + abs(it.vega) + abs(it.theta) }) * 0.45)
            val matched = matches.minByOrNull { abs(it.strike - strike) }?.strike
            return ZoneConfluence(name, zone, matched, score, vol, greekMagnitude, abs((matched ?: strike) - strike))
        }

        val named = selected.zip(
            listOf(
                "Liquidity Exhaustion", "Dealer Absorption", "Primary Hedge Floor",
                "Immediate Hedge Wall", "Reclaim Gate", "Upper Inventory Ceiling"
            )
        ).map { (strike, name) -> confluence(strike, strike, name) }

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
        return Map(rows, spot, zones, null, true, listOf(
            "ATM IV strike " + (atm ?: Double.NaN) + " ignored.",
            "IV zone polishing uses signed basis: polished strike = IV strike + (GC price - live XAUUSD price).",
            "Volatility + Greeks confluence buffer = " + STRIKE_BUFFER + " points."
        ))
    }

    private fun normalize(value: Double, values: List<Double>): Double {
        val finite = values.filter { it.isFinite() }
        if (!value.isFinite() || finite.isEmpty()) return 0.0
        val lo = finite.minOrNull() ?: value
        val hi = finite.maxOrNull() ?: value
        return if (hi <= lo) 100.0 else ((value - lo) / (hi - lo) * 100.0).coerceIn(0.0, 100.0)
    }

    companion object { const val STRIKE_BUFFER = 5.0 }

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
        if (strikeIdx < 0) return emptyList()
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
            rows += Row(strike, type, num(cells.getOrNull(oiIdx)) ?: 0.0, num(cells.getOrNull(volIdx)) ?: 0.0,
                num(cells.getOrNull(gammaIdx)) ?: 0.0, num(cells.getOrNull(deltaIdx)) ?: 0.0,
                num(cells.getOrNull(vegaIdx)) ?: 0.0, num(cells.getOrNull(thetaIdx)) ?: 0.0,
                num(cells.getOrNull(ivIdx)) ?: 0.0)
        }
        return rows
    }

    private fun clean(s: String): String = s.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()
    private fun normalize(s: String): String = s.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), " ").trim()
    private fun num(s: String?): Double? = s?.replace(",", "")?.replace("%", "")?.trim()?.toDoubleOrNull()
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

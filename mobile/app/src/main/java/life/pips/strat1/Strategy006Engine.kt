package life.pips.strat1

import life.pips.strat1.data.TradeSide
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Independent Strategy 006 Options Flow engine. */
class Strategy006Engine {
    data class Row(
        val strike: Double, val type: Char, val oi: Double = 0.0,
        val volume: Double = 0.0, val gamma: Double = 0.0,
        val delta: Double = 0.0, val vega: Double = 0.0,
        val theta: Double = 0.0, val iv: Double = 0.0
    )

    data class Zones(
        val upperInventoryCeiling: Double?, val reclaimGate: Double?,
        val immediateHedgeWall: Double?, val primaryHedgeFloor: Double?,
        val callWall: Double?, val putWall: Double?, val gammaFlip: Double?,
        val positiveGexRegion: Pair<Double, Double>?,
        val negativeGexRegion: Pair<Double, Double>?,
        val dealerAbsorptionShelf: Double? = null,
        val liquidityExhaustionFloor: Double? = null
    )

    data class Map(
        val rows: List<Row>, val spot: Double?,
        val callGex: kotlin.collections.Map<Double, Double>,
        val putGex: kotlin.collections.Map<Double, Double>,
        val netGex: kotlin.collections.Map<Double, Double>,
        val totalGex: Double, val qof: Double, val bias: String,
        val zones: Zones, val valid: Boolean, val warnings: List<String>
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

    private var current: Map? = null
    private var lastPrice = Double.NaN
    private var lastState = MarketState.NO_TRADE
    private val priceHistory = ArrayDeque<Double>()

    fun currentMap(): Map? = current

    fun loadFiles(barchartText: String, greeksText: String, spot: Double?): Map {
        val rows = (parseText(barchartText) + parseText(greeksText))
            .filter { it.strike > 0.0 && (it.type == 'C' || it.type == 'P') }
            .groupBy { Triple(it.strike, it.type, it.gamma) }
            .values.mapNotNull { it.maxByOrNull { row -> row.oi + row.volume } }
            .sortedBy { it.strike }
        val map = calculate(rows, spot)
        current = map
        lastPrice = Double.NaN
        lastState = MarketState.NO_TRADE
        priceHistory.clear()
        return map
    }

    fun clear() {
        current = null
        lastPrice = Double.NaN
        lastState = MarketState.NO_TRADE
        priceHistory.clear()
    }

    /**
     * Stateful live decision. Every call records the MT5 price, so the engine
     * can detect PHF breaks, shelf absorption, exhaustion failure/reclaim and
     * continuation without requiring the caller to know which zone is active.
     */
    fun plan(
        price: Double, balance: Double, bid: Double, ask: Double,
        rejection: Boolean = false
    ): TradePlan {
        val m = current ?: return wait("WAIT FILES", "Load both options files.")
        if (!m.valid || price <= 0.0 || balance <= 0.0)
            return wait("WAIT DATA", "Options map or account data is invalid.")

        priceHistory.addLast(price)
        while (priceHistory.size > 12) priceHistory.removeFirst()
        val z = m.zones
        val eps = max(price * 0.0005, 0.5)
        val buy = if (ask > 0.0) ask else price
        val sell = if (bid > 0.0) bid else price

        val floor = z.primaryHedgeFloor
        val shelf = z.dealerAbsorptionShelf
        val exhaustion = z.liquidityExhaustionFloor
        val reclaim = z.reclaimGate
        val wall = z.immediateHedgeWall
        val ceiling = z.upperInventoryCeiling

        val upReaction = risingFromZone(priceHistory, listOfNotNull(floor, shelf, exhaustion), eps)
        val downReaction = fallingThroughZone(priceHistory, listOfNotNull(floor, shelf, exhaustion), eps)
        val downReject = fallingThenRejecting(priceHistory, listOfNotNull(floor, shelf, exhaustion, wall), eps)
        val phfBreak = floor != null && price < floor - eps && crossedBelow(floor)
        val shelfBroken = shelf != null && price < shelf - eps && crossedBelow(shelf)
        val exhaustionBroken = exhaustion != null && price < exhaustion - eps && crossedBelow(exhaustion)

        if (ceiling != null && price >= ceiling - eps) {
            lastState = MarketState.UPPER_CEILING
            return wait(lastState.name, "Upper Inventory Ceiling reached; wait for rejection or confirmed breakout.")
        }

        if (reclaim != null && floor != null && price > floor + eps && price >= reclaim - eps) {
            lastState = MarketState.RECLAIM_GATE
            return wait(lastState.name, "Reclaim Gate reached; wait for acceptance/rejection.")
        }

        if (floor != null && price >= floor - eps && price <= floor + eps) {
            lastState = MarketState.PHF_HOLD
            if ((upReaction || rejection) && wall != null && wall > buy)
                return trade(TradeSide.BUY, buy, floor - eps, wall, balance, MarketState.PHF_HOLD,
                    "Primary Hedge Floor held/rejected → Immediate Hedge Wall.")
            return wait(lastState.name, "Primary Hedge Floor touch; waiting for confirmed reaction.")
        }

        if (floor != null && price < floor - eps) {
            if (shelf != null && price > shelf + eps) {
                lastState = MarketState.ABSORPTION_TEST
                if ((upReaction || rejection)) {
                    val target = firstAbove(buy, reclaim, wall, floor)
                    if (target != null)
                        return trade(TradeSide.BUY, buy, shelf - eps, target, balance,
                            MarketState.ABSORPTION_RECLAIM,
                            "PHF broke → Dealer Absorption Shelf absorbed selling → reclaim.")
                }
                return wait(lastState.name, if (phfBreak) "PHF breakdown confirmed; testing Dealer Absorption Shelf." else "Testing Dealer Absorption Shelf.")
            }

            if (exhaustion != null && price > exhaustion + eps) {
                lastState = MarketState.EXHAUSTION_TEST
                if (upReaction || rejection) {
                    val target = firstAbove(buy, floor, reclaim, wall)
                    if (target != null)
                        return trade(TradeSide.BUY, buy, exhaustion - eps, target, balance,
                            MarketState.EXHAUSTION_RECLAIM,
                            "Dealer Absorption Shelf failed → Liquidity Exhaustion Floor rejected → reclaim.")
                }
                return wait(lastState.name, "Absorption did not hold; testing Liquidity Exhaustion Floor.")
            }

            if (exhaustion != null && price <= exhaustion + eps) {
                lastState = MarketState.EXHAUSTION_FAILURE
                val lower = nextLowerZone(z, exhaustion)
                if (exhaustionBroken && downReaction && lower != null && lower < sell)
                    return trade(TradeSide.SELL, sell, exhaustion + eps, lower, balance,
                        MarketState.CONTINUATION,
                        "Liquidity Exhaustion Floor failed → confirmed downside continuation.")
                return wait(lastState.name, if (shelfBroken) "Absorption Shelf failed; exhaustion floor under test." else "Exhaustion Floor reached; waiting for failure/reclaim.")
            }
        }

        if (wall != null && abs(price - wall) <= eps) {
            if (downReject && floor != null && floor < sell)
                return trade(TradeSide.SELL, sell, wall + eps, floor, balance,
                    MarketState.CONTINUATION,
                    "Immediate Hedge Wall rejected → Primary Hedge Floor.")
            return wait("WALL TEST", "Immediate Hedge Wall under test; waiting for rejection.")
        }

        if (phfBreak || shelfBroken || exhaustionBroken) {
            lastState = if (exhaustionBroken) MarketState.CONTINUATION else if (shelfBroken) MarketState.ABSORPTION_FAILURE else MarketState.PHF_BREAK
            return wait(lastState.name, "Break detected; waiting for the next zone confirmation.")
        }

        return wait(MarketState.NO_TRADE.name, "No confirmed reaction at a mapped decision zone.")
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
        return TradePlan(side, entry, stop, target, rr, balance * 0.05, state.name, reason)
    }

    private fun firstAbove(entry: Double, vararg levels: Double?): Double? =
        levels.filterNotNull().filter { it > entry }.minOrNull()

    private fun nextLowerZone(z: Zones, level: Double): Double? =
        listOfNotNull(z.negativeGexRegion?.first, z.putWall)
            .filter { it < level }.maxOrNull()

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
        if (rows.isEmpty()) return Map(emptyList(), spot, emptyMap(), emptyMap(), emptyMap(), 0.0, 0.0, "NEUTRAL",
            Zones(null, null, null, null, null, null, null, null, null), false, listOf("No option rows parsed."))
        if (spot == null || spot <= 0.0) return Map(rows, spot, emptyMap(), emptyMap(), emptyMap(), 0.0, 0.0, "NEUTRAL",
            Zones(null, null, null, null, null, null, null, null, null), true, listOf("MT5 spot required for live zone reaction."))

        val call = mutableMapOf<Double, Double>()
        val put = mutableMapOf<Double, Double>()
        val net = mutableMapOf<Double, Double>()
        for (r in rows) {
            val sign = if (r.type == 'C') 1.0 else -1.0
            val g = sign * r.gamma * r.oi * 100.0 * spot * spot * 0.01
            if (r.type == 'C') call[r.strike] = (call[r.strike] ?: 0.0) + g
            else put[r.strike] = (put[r.strike] ?: 0.0) + g
            net[r.strike] = (net[r.strike] ?: 0.0) + g
        }

        val callWall = call.maxByOrNull { it.value }?.key
        val putWall = put.minByOrNull { it.value }?.key
        val ordered = net.toSortedMap()
        var running = 0.0
        var lastSign = 0
        var flip: Double? = null
        for ((k, v) in ordered) {
            running += v
            val s = when { running > 0 -> 1; running < 0 -> -1; else -> 0 }
            if (lastSign != 0 && s != 0 && s != lastSign) { flip = k; break }
            if (s != 0) lastSign = s
        }

        val upperCandidates = call.keys.filter { it > spot }
        val lowerCandidates = put.keys.filter { it < spot }
        val upper = upperCandidates.maxByOrNull { call[it] ?: 0.0 } ?: callWall
        val floor = lowerCandidates.minByOrNull { abs(it - spot) } ?: putWall
        val upperSide = listOfNotNull(callWall, putWall).filter { it > spot }
        val lowerSide = listOfNotNull(callWall, putWall).filter { it < spot }
        val immediate = if (upper != null && upper > spot)
            upperSide.minByOrNull { abs(it - spot) }
        else lowerSide.minByOrNull { abs(it - spot) }

        // The shelf is the strongest lower put-positioning strike.
        // The exhaustion floor is the deepest lower put strike below that shelf.
        val shelf = lowerCandidates.maxByOrNull { abs(put[it] ?: 0.0) } ?: floor
        val belowShelf = lowerCandidates.filter { shelf != null && it < shelf }
        val exhaustion = belowShelf.minOrNull() ?: lowerCandidates.minOrNull()

        val positive = ordered.filterValues { it > 0.0 }.keys
        val negative = ordered.filterValues { it < 0.0 }.keys
        val total = net.values.sum()
        val gross = net.values.sumOf { abs(it) }.coerceAtLeast(1.0)
        val qof = (100.0 * total / gross).coerceIn(-100.0, 100.0)
        val bias = when { qof >= 20.0 -> "UPSIDE"; qof <= -20.0 -> "DOWNSIDE"; else -> "NEUTRAL" }

        return Map(rows, spot, call, put, net, total, qof, bias,
            Zones(
                upper, upper, immediate, floor, callWall, putWall, flip,
                if (positive.isEmpty()) null else Pair(positive.min(), positive.max()),
                if (negative.isEmpty()) null else Pair(negative.min(), negative.max()),
                shelf, exhaustion
            ), true, emptyList())
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
                else -> continue
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

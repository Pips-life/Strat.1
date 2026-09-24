package life.pips.strat1

import life.pips.strat1.data.TradeSide

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Independent Strategy 006 engine.
 *
 * Two daily option files form the map; live MT5 price is only used for
 * zone interaction and execution. Dealer positioning is an estimate based
 * on an explicit dealer-short-options convention.
 */
class Strategy006Engine {
    data class Row(
        val strike: Double,
        val type: Char,
        val oi: Double = 0.0,
        val volume: Double = 0.0,
        val gamma: Double = 0.0,
        val delta: Double = 0.0,
        val vega: Double = 0.0,
        val theta: Double = 0.0,
        val iv: Double = 0.0
    )

    data class Zones(
        val upperInventoryCeiling: Double?,
        val reclaimGate: Double?,
        val immediateHedgeWall: Double?,
        val primaryHedgeFloor: Double?,
        val callWall: Double?,
        val putWall: Double?,
        val gammaFlip: Double?,
        val positiveGexRegion: Pair<Double, Double>?,
        val negativeGexRegion: Pair<Double, Double>?
    )

    data class Map(
        val rows: List<Row>,
        val spot: Double?,
        val callGex: Map<Double, Double>,
        val putGex: Map<Double, Double>,
        val netGex: Map<Double, Double>,
        val totalGex: Double,
        val qof: Double,
        val bias: String,
        val zones: Zones,
        val valid: Boolean,
        val warnings: List<String>
    )

    data class TradePlan(
        val side: TradeSide?,
        val entry: Double?,
        val stop: Double?,
        val target: Double?,
        val rewardRisk: Double,
        val riskAmount: Double,
        val state: String,
        val reason: String
    )

    private var current: Map? = null
    fun currentMap(): Map? = current

    fun loadFiles(barchartText: String, greeksText: String, spot: Double?): Map {
        val rows = (parseText(barchartText) + parseText(greeksText))
            .filter { it.strike > 0 && (it.type == 'C' || it.type == 'P') }
            .groupBy { Triple(it.strike, it.type, it.gamma) }
            .values.map { group -> group.maxByOrNull { it.oi + it.volume }!! }
            .sortedBy { it.strike }
        val map = calculate(rows, spot)
        current = map
        return map
    }

    fun clear() { current = null }

    fun plan(price: Double, balance: Double, bid: Double, ask: Double, rejection: Boolean = false): TradePlan {
        val m = current ?: return TradePlan(null, null, null, null, 0.0, 0.0, "WAIT FILES", "Load both options files.")
        if (!m.valid || price <= 0.0) return TradePlan(null, null, null, null, 0.0, 0.0, "WAIT DATA", "Options map or live price is invalid.")
        val z = m.zones
        val floor = z.primaryHedgeFloor
        val wall = z.immediateHedgeWall
        val entry = if (ask > 0) ask else price
        val sellEntry = if (bid > 0) bid else price
        val bufferBase = listOfNotNull(z.gammaFlip, floor, wall).let { levels ->
            if (levels.size >= 2) abs(levels.max() - levels.min()) * 0.10 else max(price * 0.001, 0.5)
        }
        if (floor != null && price >= floor && price <= floor + max(bufferBase, price * 0.0005) && wall != null && wall > entry && rejection) {
            val stop = floor - bufferBase
            val rr = (wall - entry) / max(entry - stop, 1e-9)
            return if (rr >= 1.35) TradePlan(TradeSide.BUY, entry, stop, wall, rr, balance * 0.05, "BUY ZONE REACTION", "Primary Hedge Floor rejection → Immediate Hedge Wall.")
            else TradePlan(null, null, null, null, rr, 0.0, "WAIT RR", "Nearest opposing zone is too close for the minimum 1.35R.")
        }
        if (wall != null && price <= wall && price >= wall - max(bufferBase, price * 0.0005) && floor != null && floor < sellEntry && rejection) {
            val stop = wall + bufferBase
            val rr = (sellEntry - floor) / max(stop - sellEntry, 1e-9)
            return if (rr >= 1.35) TradePlan(TradeSide.SELL, sellEntry, stop, floor, rr, balance * 0.05, "SELL ZONE REACTION", "Immediate Hedge Wall rejection → Primary Hedge Floor.")
            else TradePlan(null, null, null, null, rr, 0.0, "WAIT RR", "Nearest opposing zone is too close for the minimum 1.35R.")
        }
        return TradePlan(null, null, null, null, 0.0, 0.0, "WAIT ZONE", "Waiting for confirmed rejection at a mapped zone.")
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
            // Dollar-gamma approximation for a 1% move. Sign is an estimate.
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
        val walls = listOfNotNull(callWall, putWall)
        val immediate = walls.minByOrNull { abs(it - spot) }
        val positive = ordered.filterValues { it > 0 }.keys
        val negative = ordered.filterValues { it < 0 }.keys
        val total = net.values.sum()
        val gross = net.values.sumOf { abs(it) }.coerceAtLeast(1.0)
        val qof = (100.0 * total / gross).coerceIn(-100.0, 100.0)
        val bias = when { qof >= 20 -> "UPSIDE"; qof <= -20 -> "DOWNSIDE"; else -> "NEUTRAL" }
        return Map(rows, spot, call, put, net, total, qof, bias,
            Zones(upper, upper, immediate, floor, callWall, putWall, flip,
                if (positive.isEmpty()) null else Pair(positive.min(), positive.max()),
                if (negative.isEmpty()) null else Pair(negative.min(), negative.max())),
            true, emptyList())
    }

    private fun parseText(text: String): List<Row> {
        if (text.isBlank()) return emptyList()
        val rows = mutableListOf<Row>()
        val tableRegex = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cellRegex = Regex("<t[dh][^>]*>(.*?)</t[dh]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val htmlRows = tableRegex.findAll(text).map { match ->
            cellRegex.findAll(match.groupValues[1]).map { clean(it.groupValues[1]) }.toList()
        }.toList()
        val csvRows = text.lineSequence().map { splitCsv(it) }.filter { it.size >= 2 }.toList()
        val all = if (htmlRows.size >= 2) htmlRows else csvRows
        if (all.isEmpty()) return rows
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
        if (strikeIdx < 0) return rows
        for (cells in all.drop(1)) {
            val strike = num(cells.getOrNull(strikeIdx)) ?: continue
            val typeText = cells.getOrNull(typeIdx ?: -1).orEmpty().uppercase(Locale.US)
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

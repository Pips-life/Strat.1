package life.pips.strat1

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Strategy 006 File 1 extractor.
 *
 * Handles text exports plus screenshot/PDF options tables. Screenshot/PDF OCR
 * keeps ML Kit bounding boxes so the six IV columns can be rebuilt by geometry
 * instead of relying on OCR's flattened text order.
 */
object Strategy006FileExtractor {

    private data class OcrToken(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val page: Int = 0
    ) {
        val centerY: Double get() = (top + bottom) / 2.0
        val height: Int get() = (bottom - top).coerceAtLeast(1)
    }

    private data class OcrCapture(
        val text: String,
        val tokens: List<OcrToken>
    )

    fun normalizeClipboardText(raw: String): Result<String> = runCatching {
        val normalized = normalizeOcr(raw)
        if (normalized.lineSequence().count { it.isNotBlank() } < 2) {
            error("Clipboard text did not contain enough IV options rows.")
        }
        normalized
    }

    suspend fun extract(context: Context, uri: Uri): Result<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = queryName(context, uri)
                val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
                val lower = name.lowercase()
                val isPdf = mime == "application/pdf" || lower.endsWith(".pdf")
                val isImage = mime.startsWith("image/") ||
                    lower.endsWith(".png") || lower.endsWith(".jpg") ||
                    lower.endsWith(".jpeg") || lower.endsWith(".webp")

                if (!isPdf && !isImage) {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: error("Could not read File 1.")
                    return@runCatching text to name
                }

                val capture = if (isPdf) {
                    ocrPdf(context, uri)
                } else {
                    val bitmap = context.contentResolver.openInputStream(uri)
                        ?.use { BitmapFactory.decodeStream(it) }
                        ?: error("Could not decode screenshot image.")
                    try {
                        ocrBitmap(bitmap, page = 0)
                    } finally {
                        bitmap.recycle()
                    }
                }

                val normalized = normalizeOcr(capture.text, capture.tokens)
                if (normalized.lineSequence().count { it.isNotBlank() } < 2) {
                    error(
                        "OCR found the table image but could not reconstruct six IV rows. " +
                            "The screenshot/PDF may be cropped, too small, or the table columns are not visible together."
                    )
                }
                normalized to name
            }
        }

    /** Read a futures/options PDF or image as raw OCR so Strategy 006 can parse OI, volume, premium and strike columns. */
    suspend fun extractFutures(context: Context, uri: Uri): Result<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = queryName(context, uri)
                val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
                val lower = name.lowercase()
                val isPdf = mime == "application/pdf" || lower.endsWith(".pdf")
                val isImage = mime.startsWith("image/") || lower.endsWith(".png") ||
                    lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")

                val capture = if (isPdf) {
                    ocrPdf(context, uri)
                } else if (isImage) {
                    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        ?: error("Could not decode futures screenshot.")
                    try { ocrBitmap(bitmap, 0) } finally { bitmap.recycle() }
                } else {
                    val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Could not read futures/options file.")
                    OcrCapture(raw, emptyList())
                }

                val normalized = normalizeFuturesOcr(capture.text, capture.tokens)
                if (normalized.lineSequence().count { it.isNotBlank() } < 2) {
                    error("Could not reconstruct usable futures/options rows from File 3.")
                }
                normalized to name
            }
        }

    private data class RowValue(
        val type: Char,
        val oi: Double,
        val volume: Double,
        val premium: Double
    )

    /** Rebuild File 3 from OCR geometry and normalize it to one row per side. */
    private fun normalizeFuturesOcr(raw: String, ocrTokens: List<OcrToken>): String {
        val numberRegex = Regex("""[-+]?\d+(?:,\d{3})*(?:\.\d+)?%?""")
        fun value(s: String): Double? = s.replace(",", "").replace("%", "").toDoubleOrNull()

        fun parseRows(values: List<Double>): Pair<Double, List<RowValue>>? {
            if (values.size < 5) return null
            val strikeIndex = values.indexOfFirst { it >= 1000.0 }
            if (strikeIndex <= 0 || strikeIndex >= values.lastIndex) return null
            val strike = values[strikeIndex]
            val left = values.subList(0, strikeIndex)
            val right = values.subList(strikeIndex + 1, values.size)
            if (left.isEmpty() || right.isEmpty()) return null
            val putPremium = left.last()
            val putVol = left.getOrNull(left.size - 2) ?: 0.0
            val putOi = left.getOrNull(left.size - 3) ?: left.first()
            val callPremium = right.first()
            val callVol = right.getOrNull(1) ?: 0.0
            val callOi = right.getOrNull(2) ?: right.last()
            return strike to listOf(
                RowValue('P', putOi, putVol, putPremium),
                RowValue('C', callOi, callVol, callPremium)
            )
        }

        val rows = mutableListOf<Pair<Double, List<RowValue>>>()

        if (ocrTokens.isNotEmpty()) {
            for (pageTokens in ocrTokens.groupBy { it.page }.values) {
                val numeric = pageTokens.flatMap { token ->
                    numberRegex.findAll(token.text).mapNotNull { m ->
                        value(m.value)?.let { Triple(it, token.left, token.centerY) }
                    }
                }
                if (numeric.isEmpty()) continue
                val heights = pageTokens.map { it.height }.sorted()
                val medianHeight = heights[heights.size / 2].toDouble().coerceAtLeast(1.0)
                val rowTolerance = max(12.0, medianHeight * 0.8)
                val groups = mutableListOf<MutableList<Triple<Double, Int, Double>>>()
                for (item in numeric.sortedBy { it.third }) {
                    val group = groups.lastOrNull()
                    if (group == null ||
                        kotlin.math.abs(group.map { it.third }.average() - item.third) > rowTolerance
                    ) groups += mutableListOf(item) else group += item
                }
                for (group in groups) {
                    parseRows(group.sortedBy { it.second }.map { it.first })?.let { rows += it }
                }
            }
        }

        if (rows.isEmpty()) {
            raw.lineSequence().forEach { line ->
                parseRows(numberRegex.findAll(line).mapNotNull { value(it.value) }.toList())?.let { rows += it }
            }
        }

        if (rows.isEmpty()) {
            val values = numberRegex.findAll(raw).mapNotNull { value(it.value) }.toList()
            for (i in values.indices) {
                if (i + 5 > values.size) break
                parseRows(values.subList(i, minOf(values.size, i + 7)))?.let { rows += it }
            }
        }

        if (rows.isEmpty()) return ""
        return buildString {
            appendLine("strike,type,volume,open interest,premium")
            rows.distinctBy { it.first to it.second.first().type }.forEach { (strike, sides) ->
                sides.forEach { side ->
                    appendLine(String.format(java.util.Locale.US, "%.4f,%s,%.4f,%.4f,%.4f",
                        strike, side.type, side.volume, side.oi, side.premium))
                }
            }
        }
    }

    private fun queryName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else uri.lastPathSegment.orEmpty()
        }.orEmpty().ifBlank {
            uri.lastPathSegment.orEmpty().ifBlank { "Barchart File 1" }
        }

    private fun ocrBitmap(bitmap: Bitmap, page: Int): OcrCapture {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(recognizer.process(image))
            val tokens = result.textBlocks.flatMap { block ->
                block.lines.flatMap { line ->
                    line.elements.map { element ->
                        val box = element.boundingBox
                        OcrToken(
                            text = element.text,
                            left = box?.left ?: 0,
                            top = box?.top ?: 0,
                            right = box?.right ?: 0,
                            bottom = box?.bottom ?: 0,
                            page = page
                        )
                    }
                }
            }
            OcrCapture(result.text, tokens)
        } finally {
            recognizer.close()
        }
    }

    private fun ocrPdf(context: Context, uri: Uri): OcrCapture {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Could not open PDF.")

        return pfd.use {
            PdfRenderer(it).use { renderer ->
                val out = StringBuilder()
                val allTokens = mutableListOf<OcrToken>()

                for (pageIndex in 0 until renderer.pageCount) {
                    renderer.openPage(pageIndex).use { page ->
                        // Screenshot PDFs often contain a raster image rather than a
                        // text layer. Render at a high, OCR-friendly resolution and
                        // preserve the page aspect ratio exactly.
                        val targetWidth = 3200.0
                        val scale = max(2.0, targetWidth / page.width.toDouble())
                        val width = (page.width * scale).toInt().coerceAtMost(4800)
                        val height = (page.height * scale).toInt().coerceAtMost(4800)

                        fun renderAndOcr(renderWidth: Int, renderHeight: Int): OcrCapture {
                            val bitmap = Bitmap.createBitmap(
                                renderWidth,
                                renderHeight,
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.eraseColor(Color.WHITE)
                            page.render(
                                bitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                            )
                            return try {
                                ocrBitmap(bitmap, pageIndex)
                            } finally {
                                bitmap.recycle()
                            }
                        }

                        var capture = renderAndOcr(width, height)

                        // If the first pass produced little/no text, retry the same
                        // screenshot PDF page at a larger raster size. This specifically
                        // covers small text embedded inside PDF screenshots.
                        if (capture.tokens.size < 8 || capture.text.lineSequence().count { it.isNotBlank() } < 2) {
                            val retryScale = max(scale * 1.35, 3.0)
                            val retryWidth = (page.width * retryScale).toInt().coerceAtMost(6000)
                            val retryHeight = (page.height * retryScale).toInt().coerceAtMost(6000)
                            val retry = renderAndOcr(retryWidth, retryHeight)
                            if (retry.tokens.size > capture.tokens.size ||
                                retry.text.length > capture.text.length) {
                                capture = retry
                            }
                        }

                        out.append(capture.text).append('\n')
                        allTokens += capture.tokens
                    }
                }
                OcrCapture(out.toString(), allTokens)
            }
        }
    }

    /**
     * Reconstruct the Barchart side-by-side table:
     * PUT Δ | PUT PRICE | STRIKE | CALL PRICE | CALL Δ | IMP VOL
     *
     * For images/PDFs we use OCR element coordinates to group tokens into
     * physical rows and then sort them left-to-right. This avoids the common
     * ML Kit failure where all columns are returned as one flattened stream.
     */
    private fun normalizeOcr(
        raw: String,
        ocrTokens: List<OcrToken> = emptyList()
    ): String {
        val numberRegex = Regex(
            """[-+]?\d+(?:,\d{3})*(?:\.\d+)?%?"""
        )

        fun numberValue(text: String): Double? =
            text.replace(",", "").replace("%", "").toDoubleOrNull()

        fun validWindow(values: List<Double>): Boolean {
            if (values.size != 6) return false
            val putDelta = values[0]
            val putPrice = values[1]
            val strike = values[2]
            val callPrice = values[3]
            val callDelta = values[4]
            val iv = values[5]
            return putDelta in -1.2..0.05 &&
                putPrice >= 0.0 &&
                strike >= 1000.0 &&
                callPrice >= 0.0 &&
                callDelta in -0.05..1.2 &&
                iv in 0.0..100.0
        }

        val spotMatch = Regex(
            """(?i)\bspot(?:\s+price)?\s*[:=\-]?\s*([0-9]+(?:\.[0-9]+)?)\b"""
        ).find(raw)
        val spotFromRaw = spotMatch?.groupValues?.getOrNull(1)

        // 1) Preferred path: physical OCR geometry.
        val geometricRows = mutableListOf<String>()
        if (ocrTokens.isNotEmpty()) {
            for (pageTokens in ocrTokens.groupBy { it.page }.values) {
                val numericTokens = pageTokens.flatMap { token ->
                    numberRegex.findAll(token.text).mapNotNull { match ->
                        numberValue(match.value)?.let { value ->
                            Triple(value, token.left, token.centerY)
                        }
                    }
                }

                if (numericTokens.isEmpty()) continue

                val heights = pageTokens.map { it.height }.sorted()
                val medianHeight = heights[heights.size / 2].toDouble()
                val rowTolerance = max(12.0, medianHeight * 0.75)

                val groups = mutableListOf<MutableList<Triple<Double, Int, Double>>>()
                for (item in numericTokens.sortedBy { it.third }) {
                    val group = groups.lastOrNull()
                    if (group == null ||
                        kotlin.math.abs(group.map { it.third }.average() - item.third) > rowTolerance
                    ) {
                        groups += mutableListOf(item)
                    } else {
                        group += item
                    }
                }

                for (group in groups) {
                    val values = group.sortedBy { it.second }.map { it.first }
                    if (values.size >= 6) {
                        for (start in 0..values.size - 6) {
                            val window = values.subList(start, start + 6)
                            if (validWindow(window)) {
                                geometricRows += window.joinToString(",")
                                break
                            }
                        }
                    }
                }
            }
        }

        // 2) Fallback: OCR's flattened numeric stream.
        if (geometricRows.isEmpty()) {
            val tablePart = spotMatch?.let { raw.substring(0, it.range.first) } ?: raw
            val numeric = numberRegex.findAll(tablePart)
                .map { it.value.replace(",", "").replace("%", "") }
                .toList()

            var i = 0
            while (i + 6 <= numeric.size) {
                val values = numeric.subList(i, i + 6).mapNotNull { it.toDoubleOrNull() }
                if (values.size == 6 && validWindow(values)) {
                    geometricRows += values.joinToString(",")
                    i += 6
                } else {
                    i += 1
                }
            }
        }

        if (geometricRows.isNotEmpty()) {
            return buildString {
                appendLine("put delta,put price,strike,call price,call delta,imp vol")
                geometricRows.distinct().forEach { appendLine(it) }

                val spot = spotFromRaw ?: ocrTokens
                    .sortedWith(compareBy<OcrToken> { it.page }.thenBy { it.top }.thenBy { it.left })
                    .windowed(2, 1, partialWindows = true)
                    .firstNotNullOfOrNull { pair ->
                        val text = pair.joinToString(" ") { it.text }
                        Regex(
                            """(?i)\bspot(?:\s+price)?\s*[:=\-]?\s*([0-9]+(?:\.[0-9]+)?)\b"""
                        ).find(text)?.groupValues?.getOrNull(1)
                    }

                spot?.let { appendLine("spot,$it") }
            }
        }

        // 3) Legacy stacked Call/Put fallback.
        val lines = raw.lineSequence()
            .map { it.replace('|', ' ').replace('—', '-').replace('–', '-') }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .toList()

        val rows = mutableListOf<String>()
        for (line in lines) {
            val type = when {
                Regex("""\b(call|c)\b""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> "C"
                Regex("""\b(put|p)\b""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> "P"
                else -> null
            } ?: continue

            val nums = numberRegex.findAll(line)
                .mapNotNull { numberValue(it.value) }
                .toList()

            if (nums.isEmpty()) continue
            val strike = nums.firstOrNull { it >= 100.0 } ?: continue
            val afterStrike = nums.dropWhile { kotlin.math.abs(it - strike) > 1e-9 }.drop(1)
            val integerish = afterStrike.filter {
                kotlin.math.abs(it - kotlin.math.round(it)) < 1e-6 && it >= 0.0
            }
            val oi = integerish.maxOrNull() ?: afterStrike.maxOrNull() ?: 0.0
            val volume = if (afterStrike.size >= 2) {
                afterStrike.filter { it != oi }.maxOrNull() ?: 0.0
            } else {
                0.0
            }
            rows += "$strike,$type,$volume,$oi"
        }

        if (rows.isEmpty()) return ""
        return buildString {
            appendLine("strike,type,volume,open interest")
            rows.distinct().forEach { appendLine(it) }
            spotFromRaw?.let { appendLine("spot,$it") }
        }
    }
}
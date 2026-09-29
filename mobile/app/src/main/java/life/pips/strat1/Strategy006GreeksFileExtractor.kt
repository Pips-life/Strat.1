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
import kotlin.math.abs

/**
 * Strategy 006 Volatility/Greeks input.
 *
 * Screenshot PDFs are reconstructed as a 13-column table:
 * CALL IV | Delta | Gamma | Theta | Vega | IV Skew |
 * STRIKE |
 * PUT IV | Delta | Gamma | Theta | Vega | IV Skew
 *
 * OCR values are assigned by physical X/Y coordinates, never by flattened OCR
 * order. This prevents values from sliding into neighbouring columns.
 */
object Strategy006GreeksFileExtractor {
    private data class OcrToken(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val page: Int
    ) {
        val centerY: Double get() = (top + bottom) / 2.0
        val height: Int get() = (bottom - top).coerceAtLeast(1)
    }

    private data class OcrCapture(val text: String, val tokens: List<OcrToken>)

    suspend fun extract(context: Context, uri: Uri): Result<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = queryName(context, uri)
                val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
                val lower = name.lowercase()
                val isPdf = mime == "application/pdf" || lower.endsWith(".pdf")
                val isImage = mime.startsWith("image/") || lower.endsWith(".png") ||
                    lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")

                if (!isPdf && !isImage) {
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Could not read Volatility/Greeks file.")
                    return@runCatching text to name
                }

                val capture = if (isPdf) ocrPdf(context, uri) else {
                    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        ?: error("Could not decode Volatility/Greeks screenshot.")
                    try { ocrBitmap(bitmap, 0) } finally { bitmap.recycle() }
                }

                val normalized = normalizeGreeksOcr(capture)
                if (normalized.lineSequence().count { it.isNotBlank() } < 2) {
                    error("OCR could not reconstruct the 13-column Volatility/Greeks table.")
                }
                normalized to name
            }
        }

    private fun queryName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else uri.lastPathSegment.orEmpty()
        }.orEmpty().ifBlank { uri.lastPathSegment.orEmpty().ifBlank { "Volatility-Greeks file" } }

    private fun ocrBitmap(bitmap: Bitmap, page: Int): OcrCapture {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            val tokens = result.textBlocks.flatMap { block ->
                block.lines.flatMap { line ->
                    line.elements.map { element ->
                        val box = element.boundingBox
                        OcrToken(
                            element.text,
                            box?.left ?: 0,
                            box?.top ?: 0,
                            box?.right ?: 0,
                            box?.bottom ?: 0,
                            page
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
            ?: error("Could not open Volatility/Greeks PDF.")
        return pfd.use {
            PdfRenderer(it).use { renderer ->
                val allText = StringBuilder()
                val allTokens = mutableListOf<OcrToken>()
                for (pageIndex in 0 until renderer.pageCount) {
                    renderer.openPage(pageIndex).use { page ->
                        // High-resolution rendering is required for screenshot PDFs.
                        val scale = max(2.0, 3200.0 / page.width.toDouble())
                        val width = (page.width * scale).toInt().coerceAtMost(6000)
                        val height = (page.height * scale).toInt().coerceAtMost(6000)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        try {
                            val capture = ocrBitmap(bitmap, pageIndex)
                            allText.append(capture.text).append('\n')
                            allTokens += capture.tokens
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
                OcrCapture(allText.toString(), allTokens)
            }
        }
    }

    private fun normalizeGreeksOcr(capture: OcrCapture): String {
        /*
         * File 2 is accepted in two forms:
         *  1) screenshot/image PDF: reconstruct by physical X/Y position;
         *  2) CSV/text: reconstruct by header names or a strict 13-cell row.
         *
         * Canonical output:
         * call iv,call delta,call gamma,call theta,call vega,call iv skew,
         * strike,
         * put iv,put delta,put gamma,put theta,put vega,put iv skew
         */
        val numberRegex = Regex("""[-+]?\d+(?:,\d{3})*(?:\.\d+)?%?""")
        fun value(s: String): Double? =
            s.replace(",", "").replace("%", "").trim().toDoubleOrNull()

        fun valid(values: List<Double>): Boolean {
            if (values.size != 13) return false
            val strike = values[6]
            if (!strike.isFinite() || strike < 100.0) return false
            // Reject obvious OCR/header artefacts while allowing IVs above 100%.
            val ivs = listOf(values[0], values[7], values[5], values[12])
            return ivs.all { it.isFinite() && it >= 0.0 && it <= 1000.0 }
        }

        fun emit(rows: List<List<Double>>): String {
            if (rows.isEmpty()) return ""
            return buildString {
                appendLine("call iv,call delta,call gamma,call theta,call vega,call iv skew,strike,put iv,put delta,put gamma,put theta,put vega,put iv skew")
                rows.distinct().forEach { values ->
                    appendLine(values.joinToString(",") { String.format(java.util.Locale.US, "%.8f", it) })
                }
            }
        }

        // ---- CSV/text path -------------------------------------------------
        // Prefer named columns. This fixes CSVs whose column order differs from
        // the screenshot layout.
        val lines = capture.text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (lines.size >= 2 && lines.first().contains(",")) {
            val table = lines.map { splitCsv(it) }
            val header = table.first().map { normalizeHeader(it) }

            fun find(vararg aliases: String): Int =
                aliases.firstNotNullOfOrNull { alias ->
                    val a = normalizeHeader(alias)
                    header.indexOfFirst { h -> h == a || h.contains(a) }
                        .takeIf { it >= 0 }
                } ?: -1

            val callIv = find("call iv", "call implied volatility", "call implied vol")
            val callDelta = find("call delta")
            val callGamma = find("call gamma")
            val callTheta = find("call theta")
            val callVega = find("call vega")
            val callSkew = find("call iv skew", "call skew")
            val strike = find("strike", "strike price")
            val putIv = find("put iv", "put implied volatility", "put implied vol")
            val putDelta = find("put delta")
            val putGamma = find("put gamma")
            val putTheta = find("put theta")
            val putVega = find("put vega")
            val putSkew = find("put iv skew", "put skew")

            val named = listOf(
                callIv, callDelta, callGamma, callTheta, callVega, callSkew,
                strike, putIv, putDelta, putGamma, putTheta, putVega, putSkew
            )

            if (named.all { it >= 0 }) {
                val rows = table.drop(1).mapNotNull { cells ->
                    val values = named.map { idx -> value(cells.getOrNull(idx).orEmpty()) ?: return@mapNotNull null }
                    values.takeIf(::valid)
                }
                val out = emit(rows)
                if (out.isNotBlank()) return out
            }

            // Exact 13-cell CSV fallback. This supports a clean export whose
            // header is absent or uses provider-specific labels.
            val positional = table.drop(1).mapNotNull { cells ->
                if (cells.size < 13) return@mapNotNull null
                val values = cells.take(13).map { value(it) ?: return@mapNotNull null }
                values.takeIf(::valid)
            }
            val out = emit(positional)
            if (out.isNotBlank()) return out
        }

        // ---- Screenshot/OCR path ------------------------------------------
        // Never require the OCR engine to return exactly 13 values in its
        // flattened reading order. First identify the strike physically, then
        // take the nearest six numeric cells on each side.
        val rows = mutableListOf<List<Double>>()
        if (capture.tokens.isNotEmpty()) {
            for ((_, tokens) in capture.tokens.groupBy { it.page }) {
                val numeric = tokens.flatMap { token ->
                    numberRegex.findAll(token.text).mapNotNull { m ->
                        value(m.value)?.let { Triple(it, token.left, token.centerY) }
                    }
                }.filter { it.first.isFinite() }

                if (numeric.isEmpty()) continue
                val heights = tokens.map { it.height }.sorted()
                val medianHeight = heights[heights.size / 2].toDouble().coerceAtLeast(1.0)
                val tolerance = max(12.0, medianHeight * 1.25)

                val groups = mutableListOf<MutableList<Triple<Double, Int, Double>>>()
                for (item in numeric.sortedBy { it.third }) {
                    val current = groups.lastOrNull()
                    if (current == null ||
                        abs(current.map { it.third }.average() - item.third) > tolerance
                    ) {
                        groups += mutableListOf(item)
                    } else {
                        current += item
                    }
                }

                for (group in groups) {
                    val ordered = group.sortedBy { it.second }
                    // Find the physical strike anchor rather than assuming the
                    // 7th OCR number is the strike.
                    val strikeIndex = ordered.indexOfLast { it.first >= 1000.0 }
                    if (strikeIndex < 0) continue

                    val left = ordered.take(strikeIndex).takeLast(6)
                    val right = ordered.drop(strikeIndex + 1).take(6)
                    if (left.size != 6 || right.size != 6) continue

                    val values = (left.map { it.first } + ordered[strikeIndex].first + right.map { it.first })
                    if (valid(values)) rows += values
                }
            }
        }

        // Last-resort plain text fallback for pasted/OCR text where coordinates
        // are unavailable. It still anchors on a strike and never blindly uses
        // the first 13 numbers in the line.
        if (rows.isEmpty()) {
            for (line in lines) {
                val nums = numberRegex.findAll(line).mapNotNull { value(it.value) }.toList()
                if (nums.size < 13) continue
                val strikeIndex = nums.indexOfLast { it >= 1000.0 }
                if (strikeIndex < 6 || nums.size - strikeIndex - 1 < 6) continue
                val values = nums.take(strikeIndex).takeLast(6) +
                    nums[strikeIndex] +
                    nums.drop(strikeIndex + 1).take(6)
                if (valid(values)) rows += values
            }
        }

        return emit(rows)
    }

    private fun normalizeHeader(s: String): String =
        s.lowercase(java.util.Locale.US)
            .replace("%", " percent ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (quoted && i + 1 < line.length && line[i + 1] == '"') {
                    cur.append('"')
                    i++
                } else {
                    quoted = !quoted
                }
            } else if (c == ',' && !quoted) {
                out += cur.toString().trim()
                cur.clear()
            } else {
                cur.append(c)
            }
            i++
        }
        out += cur.toString().trim()
        return out
    }
}

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
        val numberRegex = Regex("""[-+]?\d+(?:,\d{3})*(?:\.\d+)?%?""")
        fun value(s: String): Double? = s.replace(",", "").replace("%", "").toDoubleOrNull()

        fun valid(values: List<Double>): Boolean {
            if (values.size != 13) return false
            val strike = values[6]
            return strike >= 100.0
        }

        val rows = mutableListOf<List<Double>>()

        if (capture.tokens.isNotEmpty()) {
            // Page 2 is the Greeks table. For a single screenshot image page=0 is used.
            val pageGroups = capture.tokens.groupBy { it.page }
            for ((page, tokens) in pageGroups) {
                val numeric = tokens.flatMap { token ->
                    numberRegex.findAll(token.text).mapNotNull { m ->
                        value(m.value)?.let { Triple(it, token.left, token.centerY) }
                    }
                }
                if (numeric.isEmpty()) continue

                val heights = tokens.map { it.height }.sorted()
                val medianHeight = heights[heights.size / 2].toDouble().coerceAtLeast(1.0)
                val tolerance = max(12.0, medianHeight * 0.8)
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
                    if (ordered.size == 13) {
                        val values = ordered.map { it.first }
                        if (valid(values)) rows += values
                    }
                }
            }
        }

        // Strict text fallback for CSV/table text: exactly 13 numeric cells per row.
        if (rows.isEmpty()) {
            for (line in capture.text.lineSequence()) {
                val values = numberRegex.findAll(line).mapNotNull { value(it.value) }.toList()
                if (valid(values)) rows += values
            }
        }

        if (rows.isEmpty()) return ""

        return buildString {
            appendLine(
                "call iv,call delta,call gamma,call theta,call vega,call iv skew," +
                    "strike,put iv,put delta,put gamma,put theta,put vega,put iv skew"
            )
            rows.distinct().forEach { values ->
                appendLine(values.joinToString(",") { String.format(java.util.Locale.US, "%.8f", it) })
            }
        }
    }
}

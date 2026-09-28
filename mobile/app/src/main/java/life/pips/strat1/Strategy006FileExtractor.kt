package life.pips.strat1

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.gms.tasks.Tasks
import kotlin.math.abs
import kotlin.math.max

/**
 * Strategy 006 File 1 extractor.
 *
 * Accepts:
 * - MHT/MHTML/HTML/text exports (read as text)
 * - PDF options-chain screenshots/pages (native PdfRenderer + ML Kit OCR)
 * - PNG/JPG/JPEG/WEBP screenshots (ML Kit OCR)
 *
 * OCR output is normalized to a simple CSV: strike,type,volume,openInterest.
 * Greeks/gamma remain sourced from File 2.
 */
object Strategy006FileExtractor {
    fun normalizeClipboardText(raw: String): Result<String> = runCatching {
        val normalized = normalizeOcr(raw)
        if (normalized.lineSequence().count { it.isNotBlank() } < 2) {
            error("Clipboard text did not contain enough IV options rows.")
        }
        normalized
    }

    suspend fun extract(context: Context, uri: Uri): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val name = queryName(context, uri)
            val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
            val lower = name.lowercase()
            val isPdf = mime == "application/pdf" || lower.endsWith(".pdf")
            val isImage = mime.startsWith("image/") ||
                lower.endsWith(".png") || lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") || lower.endsWith(".webp")

            if (!isPdf && !isImage) {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read File 1.")
                return@runCatching text to name
            }

            val ocrText = if (isPdf) ocrPdf(context, uri) else {
                val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: error("Could not decode screenshot image.")
                try { ocrBitmap(bitmap) } finally { bitmap.recycle() }
            }

            val normalized = normalizeOcr(ocrText)
            if (normalized.lineSequence().count { it.isNotBlank() } < 2) {
                error("OCR did not find enough options-chain rows. Use a clear, full-resolution screenshot/PDF of the Barchart table.")
            }
            normalized to name
        }
    }

    private fun queryName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else uri.lastPathSegment.orEmpty()
        }.orEmpty().ifBlank { uri.lastPathSegment.orEmpty().ifBlank { "Barchart File 1" } }

    private fun ocrPdf(context: Context, uri: Uri): String {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Could not open PDF.")
        return pfd.use {
            PdfRenderer(it).use { renderer ->
                val out = StringBuilder()
                for (pageIndex in 0 until renderer.pageCount) {
                    renderer.openPage(pageIndex).use { page ->
                        val scale = max(2.0, 2400.0 / page.width.toDouble())
                        val width = (page.width * scale).toInt().coerceAtMost(4000)
                        val height = (page.height * scale).toInt().coerceAtMost(4000)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        out.append(ocrBitmap(bitmap)).append('\n')
                        bitmap.recycle()
                    }
                }
                out.toString()
            }
        }
    }

    private fun ocrBitmap(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            Tasks.await(recognizer.process(image)).text
        } finally {
            recognizer.close()
        }
    }

    /**
     * Converts common OCR layouts into the same row format consumed by Strategy 006.
     * It handles the common Barchart stacked form (rows contain C/P or Call/Put)
     * and the side-by-side form by using the order of numeric fields.
     */
    private fun normalizeOcr(raw: String): String {
        /*
         * Barchart side-by-side screenshot layout:
         * PUT Δ | PUT PRICE | STRIKE | CALL PRICE | CALL Δ | IMP VOL
         *
         * OCR often collapses the whole table into one line. Do not treat that
         * as one row. Reconstruct rows from the numeric sequence and validate
         * the six-field schema before emitting CSV.
         */
        val spotMatch = Regex("""(?i)\\bspot\\s*(?:price)?\\s*[:=\\-]?\\s*([0-9]+(?:\\.[0-9]+)?)\\b""").find(raw)
        val tablePart = spotMatch?.let { raw.substring(0, it.range.first) } ?: raw
        val numeric = Regex("""(?<![A-Za-z])[-+]?\\d+(?:,\\d{3})*(?:\\.\\d+)?%?""")
            .findAll(tablePart)
            .map { it.value.replace(",", "").replace("%", "") }
            .toList()

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

        val sideBySideRows = mutableListOf<String>()
        var i = 0
        while (i + 6 <= numeric.size) {
            val values = numeric.subList(i, i + 6).mapNotNull { it.toDoubleOrNull() }
            if (values.size == 6 && validWindow(values)) {
                val pd = values[0]
                val pp = values[1]
                val strike = values[2]
                val cp = values[3]
                val cd = values[4]
                val iv = values[5]
                sideBySideRows += "$pd,$pp,$strike,$cp,$cd,$iv"
                i += 6
            } else {
                i += 1
            }
        }

        if (sideBySideRows.isNotEmpty()) {
            return buildString {
                appendLine("put delta,put price,strike,call price,call delta,imp vol")
                sideBySideRows.distinct().forEach { appendLine(it) }
                spotMatch?.groupValues?.getOrNull(1)?.let { appendLine("spot,$it") }
            }
        }

        // Fallback for stacked Call/Put OCR layouts.
        val lines = raw.lineSequence()
            .map { it.replace('|', ' ').replace('—', '-').replace('–', '-') }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .toList()

        val rows = mutableListOf<String>()
        for (line in lines) {
            val type = when {
                Regex("\\b(call|c)\\b", RegexOption.IGNORE_CASE).containsMatchIn(line) -> "C"
                Regex("\\b(put|p)\\b", RegexOption.IGNORE_CASE).containsMatchIn(line) -> "P"
                else -> null
            } ?: continue

            val nums = Regex("(?<![A-Za-z])[-+]?\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?%?")
                .findAll(line)
                .mapNotNull { token ->
                    token.value.replace(",", "").replace("%", "").toDoubleOrNull()
                }.toList()

            if (nums.isEmpty()) continue
            val strike = nums.firstOrNull { it >= 100.0 } ?: continue
            val afterStrike = nums.dropWhile { kotlin.math.abs(it - strike) > 1e-9 }.drop(1)
            val integerish = afterStrike.filter { kotlin.math.abs(it - kotlin.math.round(it)) < 1e-6 && it >= 0.0 }
            val oi = integerish.maxOrNull() ?: afterStrike.maxOrNull() ?: 0.0
            val volume = if (afterStrike.size >= 2) afterStrike.filter { it != oi }.maxOrNull() ?: 0.0 else 0.0
            rows += "$strike,$type,$volume,$oi"
        }

        if (rows.isEmpty()) return ""
        return buildString {
            appendLine("strike,type,volume,open interest")
            rows.distinct().forEach { appendLine(it) }
            spotMatch?.groupValues?.getOrNull(1)?.let { appendLine("spot,$it") }
        }
    }
}

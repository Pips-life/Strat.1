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
        pfd.use {
            PdfRenderer(it).use { renderer ->
                val out = StringBuilder()
                for (pageIndex in 0 until renderer.pageCount) {
                    renderer.openPage(pageIndex).use { page ->
                        val scale = max(1.0, 1800.0 / page.width.toDouble())
                        val width = (page.width * scale).toInt().coerceAtMost(3000)
                        val height = (page.height * scale).toInt().coerceAtMost(3000)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
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

            // Strike is normally the first substantial price-like number in an OCR row.
            // For gold futures, strike values are much larger than option premiums.
            val strike = nums.firstOrNull { it >= 100.0 } ?: continue
            val afterStrike = nums.dropWhile { abs(it - strike) > 1e-9 }.drop(1)
            val integerish = afterStrike.filter { abs(it - kotlin.math.round(it)) < 1e-6 && it >= 0.0 }
            val oi = integerish.maxOrNull() ?: afterStrike.maxOrNull() ?: 0.0
            val volume = if (afterStrike.size >= 2) {
                afterStrike.filter { it != oi }.maxOrNull() ?: 0.0
            } else 0.0

            rows += "$strike,$type,$volume,$oi"
        }

        if (rows.isEmpty()) return ""

        // Deduplicate exact OCR repeats from PDF pages / repeated headers.
        return buildString {
            appendLine("strike,type,volume,open interest")
            rows.distinct().forEach { appendLine(it) }
        }
    }
}

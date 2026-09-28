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

/** File parser for Strategy 006 Volatility/Greeks input. Supports text/CSV plus PDF/image OCR. */
object Strategy006GreeksFileExtractor {
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

                val text = if (isPdf) ocrPdf(context, uri) else {
                    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        ?: error("Could not decode Volatility/Greeks screenshot.")
                    try { ocrBitmap(bitmap) } finally { bitmap.recycle() }
                }
                if (text.lineSequence().count { it.isNotBlank() } < 2) {
                    error("OCR could not read the Volatility/Greeks table.")
                }
                text to name
            }
        }

    private fun queryName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else uri.lastPathSegment.orEmpty()
        }.orEmpty().ifBlank { uri.lastPathSegment.orEmpty().ifBlank { "Volatility-Greeks file" } }

    private fun ocrPdf(context: Context, uri: Uri): String {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Could not open Volatility/Greeks PDF.")
        return pfd.use {
            PdfRenderer(it).use { renderer ->
                buildString {
                    for (pageIndex in 0 until renderer.pageCount) {
                        renderer.openPage(pageIndex).use { page ->
                            val scale = max(2.0, 2600.0 / page.width.toDouble())
                            val width = (page.width * scale).toInt().coerceAtMost(4500)
                            val height = (page.height * scale).toInt().coerceAtMost(4500)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            append(ocrBitmap(bitmap)).append('\n')
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
    }

    private fun ocrBitmap(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text
        } finally {
            recognizer.close()
        }
    }
}

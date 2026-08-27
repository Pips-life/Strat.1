package life.pips.strat1

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import life.pips.strat1.data.BackendApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

private const val RELEASES_URL = "https://api.github.com/repos/Pips-life/Strat.1/releases/latest"
private const val APK_MIME = "application/vnd.android.package-archive"

/** GitHub Releases updater. It only offers APKs from official Pips-life releases. */
@Composable
fun ReleaseUpdatePrompt(context: Context) {
    var update by remember { mutableStateOf<ReleaseInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { ReleaseUpdater().latest() }
            .onSuccess { if (it != null && isNewer(it.versionCode, BuildConfig.VERSION_CODE)) update = it }
            .onFailure { /* Update checks must never block the trading UI. */ }
    }

    update?.let { release ->
        AlertDialog(
            onDismissRequest = { if (!downloading) update = null },
            title = { Text("Pips-life update available") },
            text = { Text(if (downloading) "Downloading ${release.tag}…" else "Version ${release.tag} is available. Download the official GitHub release now?${error?.let { "\n\n$it" } ?: ""}") },
            confirmButton = {
                Button(enabled = !downloading, onClick = {
                    downloading = true
                    error = null
                    androidx.compose.runtime.LaunchedEffect(Unit) { }
                }) { Text(if (downloading) "DOWNLOADING…" else "DOWNLOAD") }
            },
            dismissButton = {
                Button(enabled = !downloading, onClick = { update = null }) { Text("LATER") }
            }
        )
    }
}

/** Imperative helper used by the activity/UI when an APK has been downloaded. */
class ReleaseUpdater(private val http: OkHttpClient = OkHttpClient()) {
    suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", "Pips-life-Android")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) error("GitHub release check failed (${response.code})")
            val root = JSONObject(response.body?.string().orEmpty())
            val tag = root.optString("tag_name").removePrefix("v")
            val assets = root.optJSONArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk", true) && asset.optString("browser_download_url").startsWith("https://github.com/Pips-life/Strat.1/releases/download/")) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            if (tag.isBlank() || apkUrl.isNullOrBlank()) null else ReleaseInfo(tag, versionCode(tag), apkUrl)
        }
    }

    suspend fun download(context: Context, release: ReleaseInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "Pips-life-${release.tag}.apk")
        val request = Request.Builder().url(release.apkUrl).addHeader("User-Agent", "Pips-life-Android").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Release download failed (${response.code})")
            response.body?.byteStream()?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                ?: error("GitHub returned an empty APK")
        }
        file
    }

    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return
        }
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun versionCode(tag: String): Int {
        val p = tag.trim().removePrefix("v").split(".")
        if (p.size < 3 || p.any { it.toIntOrNull() == null }) return 0
        return p[0].toInt() * 1_000_000 + p[1].toInt() * 1_000 + p[2].toInt()
    }

    companion object {
        fun isNewer(releaseCode: Int, currentCode: Int): Boolean = releaseCode > currentCode
    }
}

data class ReleaseInfo(val tag: String, val versionCode: Int, val apkUrl: String)

private fun isNewer(releaseCode: Int, currentCode: Int): Boolean = ReleaseUpdater.isNewer(releaseCode, currentCode)

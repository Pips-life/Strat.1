package life.pips.strat1

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

private const val GITHUB_LATEST_URL = "https://api.github.com/repos/Pips-life/Strat.1/releases/latest"
private const val APK_MIME = "application/vnd.android.package-archive"

data class AppRelease(val tag: String, val name: String, val versionName: String, val versionCode: Int, val assetId: Long, val assetName: String, val downloadUrl: String, val viaBackend: Boolean = false)

class ReleaseUpdateManager(private val context: Context) {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    suspend fun check(): Result<AppRelease?> = withContext(Dispatchers.IO) { runCatching { checkPublicGitHub() } }

    private fun checkPublicGitHub(): AppRelease? {
        val request = Request.Builder().url(GITHUB_LATEST_URL).get().header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "Pips-life/${BuildConfig.VERSION_NAME}").build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("GitHub release check failed (${response.code})")
            val json = JSONObject(body)
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val tag = json.optString("tag_name").trim()
            val versionName = tag.removePrefix("v").trim()
            val assets = json.optJSONArray("assets") ?: error("GitHub release has no assets")
            var best: AppRelease? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name").trim()
                if (!name.startsWith("pips-life-", true) || !name.endsWith(".apk", true)) continue
                val versionCode = Regex("-(\\d+)\\.apk$", RegexOption.IGNORE_CASE).find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
                if (versionCode <= BuildConfig.VERSION_CODE) continue
                val url = asset.optString("browser_download_url").trim()
                if (url.isBlank()) continue
                val candidate = AppRelease(tag, json.optString("name", "Pips-life update"), versionName, versionCode, asset.optLong("id"), name, url)
                if (best == null || candidate.versionCode > best!!.versionCode) best = candidate
            }
            return best
        }
    }

    suspend fun downloadAndInstall(release: AppRelease, onProgress: (Int) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Allow Pips-life to install updates, then tap Download again.", Toast.LENGTH_LONG).show()
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                return@runCatching
            }
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("App download directory unavailable")
            val file = File(dir, "pips-life-${release.versionName}-${release.versionCode}.apk")
            if (file.exists()) file.delete()
            val request = Request.Builder().url(release.downloadUrl).get().header("Accept", "application/vnd.github+json").header("User-Agent", "Pips-life/${BuildConfig.VERSION_NAME}").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Update download failed (${response.code})")
                val body = response.body ?: error("Update download was empty")
                val total = body.contentLength(); var read = 0L
                body.byteStream().use { input -> FileOutputStream(file).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) { val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count); read += count; if (total > 0) onProgress(((read * 100L) / total).toInt().coerceIn(0, 100)) }
                    output.fd.sync()
                } }
            }
            if (!file.exists() || file.length() == 0L) error("Downloaded update is empty")
            withContext(Dispatchers.Main) { installApk(file) }
        }
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, APK_MIME); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION) })
    }
}

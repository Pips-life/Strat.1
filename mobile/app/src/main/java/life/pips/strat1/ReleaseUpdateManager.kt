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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val GITHUB_LATEST_URL = "https://api.github.com/repos/Pips-life/Strat.1/releases/latest"
private const val APK_MIME = "application/vnd.android.package-archive"
private const val DOWNLOAD_READ_TIMEOUT_MINUTES = 5L
private const val DOWNLOAD_CALL_TIMEOUT_MINUTES = 15L

data class AppRelease(val tag: String, val name: String, val versionName: String, val versionCode: Int, val assetId: Long, val assetName: String, val downloadUrl: String, val sha256Digest: String = "", val viaBackend: Boolean = false)

class ReleaseUpdateManager(private val context: Context) {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(DOWNLOAD_READ_TIMEOUT_MINUTES, TimeUnit.MINUTES).callTimeout(DOWNLOAD_CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES).build()

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
                val digest = asset.optString("digest").trim().removePrefix("sha256:").lowercase()
                val candidate = AppRelease(tag, json.optString("name", "Pips-life update"), versionName, versionCode, asset.optLong("id"), name, url, digest)
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
            val partial = File(dir, "${file.name}.part")

            if (file.exists() && file.length() > 0L && digestMatches(file, release.sha256Digest)) {
                withContext(Dispatchers.Main) { installApk(file) }
                return@runCatching
            }
            if (file.exists()) file.delete()

            downloadResumable(release, partial, onProgress)

            if (!partial.exists() || partial.length() == 0L) error("Downloaded update is empty")
            if (!digestMatches(partial, release.sha256Digest)) {
                partial.delete()
                error("Downloaded update failed SHA-256 verification")
            }
            if (file.exists()) file.delete()
            if (!partial.renameTo(file)) error("Could not finalize downloaded update")

            withContext(Dispatchers.Main) { installApk(file) }
        }
    }

    private fun downloadResumable(release: AppRelease, partial: File, onProgress: (Int) -> Unit) {
        var existing = if (partial.exists()) partial.length() else 0L
        val builder = Request.Builder().url(release.downloadUrl).get()
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "Pips-life/${BuildConfig.VERSION_NAME}")
        if (existing > 0L) builder.header("Range", "bytes=$existing-")

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("Update download failed (${response.code})")
            val resumed = existing > 0L && response.code == 206
            if (existing > 0L && !resumed) {
                partial.delete()
                existing = 0L
            }
            val body = response.body ?: error("Update download was empty")
            val contentLength = body.contentLength()
            val total = if (resumed && contentLength > 0L) existing + contentLength else contentLength
            var read = existing
            body.byteStream().use { input ->
                FileOutputStream(partial, resumed).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        if (total > 0L) onProgress(((read * 100L) / total).toInt().coerceIn(0, 100))
                    }
                    output.fd.sync()
                }
            }
        }
    }

    private fun digestMatches(file: File, expected: String): Boolean {
        if (expected.isBlank()) return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expected, ignoreCase = true)
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, APK_MIME); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION) })
    }
}

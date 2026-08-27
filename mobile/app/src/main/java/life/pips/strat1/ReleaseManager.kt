package life.pips.strat1

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** GitHub Releases updater. It never downloads code from a branch; only a published release APK. */
object ReleaseManager {
    private const val RELEASES_API = "https://api.github.com/repos/Pips-life/Strat.1/releases/latest"
    private const val CURRENT_VERSION = "0.2.1"
    private const val CURRENT_VERSION_CODE = 3

    data class ReleaseInfo(val tag: String, val name: String, val version: String, val versionCode: Int, val apkUrl: String, val notes: String)

    suspend fun checkForUpdate(): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            if (connection.responseCode !in 200..299) return@runCatching null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return@runCatching null
            val tag = json.optString("tag_name")
            val version = tag.removePrefix("v")
            val versionCode = json.optInt("version_code", parseVersionCode(version))
            val asset = (0 until json.getJSONArray("assets").length())
                .map { json.getJSONArray("assets").getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?: return@runCatching null
            if (versionCode <= CURRENT_VERSION_CODE && compareVersions(version, CURRENT_VERSION) <= 0) return@runCatching null
            ReleaseInfo(tag, json.optString("name", tag), version, versionCode, asset.getString("browser_download_url"), json.optString("body"))
        }
    }

    suspend fun downloadAndInstall(context: Context, release: ReleaseInfo): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(context.cacheDir, "pips-life-${release.version}.apk")
            val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("Accept", "application/octet-stream")
                instanceFollowRedirects = true
            }
            if (connection.responseCode !in 200..299) error("GitHub download failed: HTTP ${connection.responseCode}")
            connection.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            withContext(Dispatchers.Main) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    error("Allow Pips-life to install updates, then tap Update again.")
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    fun currentVersion(): String = CURRENT_VERSION

    private fun parseVersionCode(version: String): Int {
        val p = version.split(".").mapNotNull { it.toIntOrNull() }
        return when (p.size) { 3 -> p[0] * 10000 + p[1] * 100 + p[2]; else -> 0 }
    }

    private fun compareVersions(a: String, b: String): Int {
        val av = a.split(".").map { it.toIntOrNull() ?: 0 }
        val bv = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(av.size, bv.size)) {
            val d = (av.getOrNull(i) ?: 0).compareTo(bv.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }
}

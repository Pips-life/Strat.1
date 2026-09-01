package life.pips.strat1

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.util.regex.Pattern

private const val GITHUB_LATEST_URL = "https://api.github.com/repos/Pips-life/Strat.1/releases/latest"
private const val GITHUB_RELEASES_URL = "https://github.com/Pips-life/Strat.1/releases/latest"
private const val APK_MIME = "application/vnd.android.package-archive"

data class AppRelease(
    val tag: String,
    val name: String,
    val versionName: String,
    val versionCode: Int,
    val assetId: Long,
    val assetName: String,
    val downloadUrl: String
)

class ReleaseUpdateManager(private val context: Context) {
    private val client = OkHttpClient()
    private var receiver: BroadcastReceiver? = null
    private var pendingInstallFile: File? = null

    init { UpdateNotifications.ensureChannel(context) }

    /**
     * Release discovery is deliberately independent of the Pips-life backend.
     * GitHub's public latest-release endpoint works without authentication, so a
     * protected/expired Vercel deployment cannot break the updater.
     */
    suspend fun check(): Result<AppRelease?> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(GITHUB_LATEST_URL)
                .get()
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2026-03-10")
                .header("User-Agent", "Pips-life/${BuildConfig.VERSION_NAME}")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("GitHub release check failed (${response.code})")
                val json = JSONObject(body)
                if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) return@use null

                val tag = json.optString("tag_name").trim()
                val versionName = tag.removePrefix("v").ifBlank { json.optString("name").trim() }
                if (versionName.isBlank()) error("GitHub latest release has no version")

                val assets = json.optJSONArray("assets") ?: error("GitHub release has no assets")
                var apkName = ""
                var apkUrl = ""
                var apkId = 0L
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name").trim()
                    if (name.endsWith(".apk", ignoreCase = true) && name.startsWith("pips-life-", ignoreCase = true)) {
                        apkName = name
                        apkUrl = asset.optString("browser_download_url").trim()
                        apkId = asset.optLong("id", 0L)
                        break
                    }
                }
                if (apkName.isBlank() || apkUrl.isBlank()) error("GitHub latest release has no Pips-life APK")

                val versionCode = extractVersionCode(apkName)
                    ?: error("Cannot determine Android version code from $apkName")
                if (versionCode <= BuildConfig.VERSION_CODE) return@use null

                AppRelease(
                    tag = tag,
                    name = json.optString("name", "Pips-life update"),
                    versionName = versionName,
                    versionCode = versionCode,
                    assetId = apkId,
                    assetName = apkName,
                    downloadUrl = apkUrl
                ).also { UpdateNotifications.notifyUpdateAvailable(context, it) }
            }
        }
    }

    fun downloadAndInstall(release: AppRelease) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, "Allow Pips-life to install updates, then tap Install again.", Toast.LENGTH_LONG).show()
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }

        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        val filename = "pips-life-${release.versionName}-${release.versionCode}.apk"
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val file = File(dir, filename)
        if (file.exists()) file.delete()
        pendingInstallFile = file

        val request = DownloadManager.Request(Uri.parse(release.downloadUrl))
            .setTitle("Pips-life ${release.versionName}")
            .setDescription("Downloading the official Pips-life GitHub release")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = manager.enqueue(request)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                try {
                    manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                        if (!cursor.moveToFirst()) return
                        if (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL) {
                            Toast.makeText(context, "Pips-life update download failed.", Toast.LENGTH_LONG).show()
                            return
                        }
                    }
                    installDownloadedApk(file)
                } finally {
                    runCatching { context.unregisterReceiver(this) }
                    receiver = null
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        Toast.makeText(context, "Pips-life ${release.versionName} downloading…", Toast.LENGTH_LONG).show()
    }

    private fun installDownloadedApk(file: File) {
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(context, "Downloaded update is missing or empty.", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = file
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    fun installPendingIfAllowed() {
        val file = pendingInstallFile ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = null
            installDownloadedApk(file)
        }
    }

    private fun extractVersionCode(name: String): Int? {
        val m = Pattern.compile("-(\\d+)\\.apk$", Pattern.CASE_INSENSITIVE).matcher(name)
        return if (m.find()) m.group(1)?.toIntOrNull() else null
    }

    companion object {
        const val RELEASES_PAGE = GITHUB_RELEASES_URL
    }
}

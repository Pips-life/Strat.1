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

private const val RELEASE_LATEST_PATH = "/api/app/release/latest"
private const val RELEASE_DOWNLOAD_PATH = "/api/app/release/download?asset="
private const val GITHUB_LATEST_URL = "https://api.github.com/repos/Pips-life/Strat.1/releases/latest"
private const val APK_MIME = "application/vnd.android.package-archive"

data class AppRelease(
    val tag: String,
    val name: String,
    val versionName: String,
    val versionCode: Int,
    val assetId: Long,
    val assetName: String,
    val downloadUrl: String,
    val viaBackend: Boolean
)

class ReleaseUpdateManager(private val context: Context) {
    private val client = OkHttpClient()
    private var receiver: BroadcastReceiver? = null
    private var pendingInstallFile: File? = null

    init { UpdateNotifications.ensureChannel(context) }

    /**
     * Primary path: public release gateway, which is required because Strat.1 is
     * private and the APK must never contain a GitHub credential.
     * Fallback: public GitHub Releases API if the repository is ever made public.
     */
    suspend fun check(): Result<AppRelease?> = withContext(Dispatchers.IO) {
        runCatching {
            val backend = runCatching { checkBackend() }.getOrNull()
            if (backend != null) return@runCatching backend

            checkPublicGitHub()
        }
    }

    private fun checkBackend(): AppRelease? {
        val base = BuildConfig.BACKEND_BASE_URL.trimEnd('/')
        val request = Request.Builder()
            .url(base + RELEASE_LATEST_PATH)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "Pips-life/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Release gateway unavailable (${response.code})")
            val json = JSONObject(body)
            val versionName = json.optString("versionName").trim()
            val versionCode = json.optInt("versionCode", 0)
            val assetId = json.optLong("assetId", 0L)
            val assetName = json.optString("assetName", "Pips-life-update.apk")
            if (versionName.isBlank() || versionCode <= 0 || assetId <= 0L) error("Release gateway returned incomplete release information")
            if (versionCode <= BuildConfig.VERSION_CODE) return null
            return AppRelease(
                tag = json.optString("tag"),
                name = json.optString("name", "Pips-life update"),
                versionName = versionName,
                versionCode = versionCode,
                assetId = assetId,
                assetName = assetName,
                downloadUrl = base + RELEASE_DOWNLOAD_PATH + assetId,
                viaBackend = true
            )
        }
    }

    private fun checkPublicGitHub(): AppRelease? {
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
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val tag = json.optString("tag_name").trim()
            val versionName = tag.removePrefix("v")
            val assets = json.optJSONArray("assets") ?: error("GitHub release has no assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name").trim()
                if (!name.startsWith("pips-life-", true) || !name.endsWith(".apk", true)) continue
                val versionCode = Pattern.compile("-(\\d+)\\.apk$", Pattern.CASE_INSENSITIVE).matcher(name).let { if (it.find()) it.group(1)?.toIntOrNull() else null } ?: continue
                if (versionCode <= BuildConfig.VERSION_CODE) return null
                val url = a.optString("browser_download_url").trim()
                if (url.isBlank()) continue
                return AppRelease(tag, json.optString("name", "Pips-life update"), versionName, versionCode, a.optLong("id"), name, url, false)
            }
            return null
        }
    }

    fun downloadAndInstall(release: AppRelease) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, "Allow Pips-life to install updates, then tap Download again.", Toast.LENGTH_LONG).show()
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
            .setDescription("Downloading the official Pips-life release")
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
}

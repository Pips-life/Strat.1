package life.pips.strat1

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

private const val LATEST_RELEASE_URL = "https://api.github.com/repos/Pips-life/Strat.1/releases/latest"

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

    init { UpdateNotifications.ensureChannel(context) }

    suspend fun check(): Result<AppRelease?> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .get()
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Pips-life/${BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("GitHub release check failed (${response.code})")
                val json = JSONObject(response.body?.string().orEmpty())
                if (json.optBoolean("draft") || json.optBoolean("prerelease")) error("Latest release is not stable")
                val assets = json.optJSONArray("assets") ?: error("Latest release has no assets")
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    val match = Regex("pips-life-(\\d+\\.\\d+\\.\\d+)-(\\d+)\\.apk", RegexOption.IGNORE_CASE).matchEntire(name) ?: continue
                    val versionName = match.groupValues[1]
                    val versionCode = match.groupValues[2].toInt()
                    if (versionCode <= BuildConfig.VERSION_CODE) return@use null
                    val downloadUrl = asset.optString("browser_download_url")
                    if (!downloadUrl.startsWith("https://github.com/")) error("Invalid GitHub APK URL")
                    return@use AppRelease(
                        tag = json.optString("tag_name"),
                        name = json.optString("name", "Pips-life update"),
                        versionName = versionName,
                        versionCode = versionCode,
                        assetId = asset.optLong("id", 0L),
                        assetName = name,
                        downloadUrl = downloadUrl
                    )
                }
                error("Latest release has no numbered Pips-life APK")
            }
        }.also { result ->
            result.getOrNull()?.let { UpdateNotifications.notifyUpdateAvailable(context, it) }
        }
    }

    fun downloadAndInstall(release: AppRelease) {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        val filename = "pips-life-${release.versionName}-${release.versionCode}.apk"
        val request = DownloadManager.Request(Uri.parse(release.downloadUrl))
            .setTitle("Pips-life ${release.versionName}")
            .setDescription("Downloading the official Pips-life GitHub release")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, filename)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = manager.enqueue(request)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                try {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    manager.query(query).use { cursor ->
                        if (!cursor.moveToFirst()) return
                        if (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL) return
                    }
                    val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), filename)
                    if (!file.exists()) return
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } finally {
                    runCatching { context.unregisterReceiver(this) }
                    receiver = null
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
    }
}

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
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val UPDATE_LATEST_PATH = "/api/app/release/latest"
private const val UPDATE_DOWNLOAD_PATH = "/api/app/release/download"

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
    private var receiver: BroadcastReceiver? = null
    private val baseUrl = BuildConfig.BACKEND_BASE_URL.trimEnd('/')

    init { UpdateNotifications.ensureChannel(context) }

    suspend fun check(): Result<AppRelease?> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(baseUrl + UPDATE_LATEST_PATH).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Pips-life-Android/${BuildConfig.VERSION_NAME}")
            }
            try {
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val detail = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
                    error(if (detail.isBlank()) "Update service unavailable (HTTP $code)" else detail)
                }
                val json = JSONObject(body)
                val versionName = json.optString("versionName").trim()
                val versionCode = json.optInt("versionCode", 0)
                val assetId = json.optLong("assetId", 0L)
                val assetName = json.optString("assetName").trim()
                if (versionName.isBlank() || versionCode <= 0 || assetId <= 0L || assetName.isBlank()) {
                    error("Update service returned incomplete release information")
                }
                if (versionCode <= BuildConfig.VERSION_CODE) return@runCatching null
                val release = AppRelease(
                    tag = json.optString("tag"),
                    name = json.optString("name", "Pips-life update"),
                    versionName = versionName,
                    versionCode = versionCode,
                    assetId = assetId,
                    assetName = assetName,
                    downloadUrl = baseUrl + UPDATE_DOWNLOAD_PATH + "?asset=" + assetId
                )
                UpdateNotifications.notifyUpdateAvailable(context, release)
                release
            } finally {
                connection.disconnect()
            }
        }
    }

    fun downloadAndInstall(release: AppRelease) {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        val filename = "pips-life-${release.versionName}-${release.versionCode}.apk"
        val request = DownloadManager.Request(Uri.parse(release.downloadUrl))
            .setTitle("Pips-life ${release.versionName}")
            .setDescription("Downloading the official Pips-life release")
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

package life.pipslife.mobile

import android.app.Activity
import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import life.pips.strat1.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class PipsLifeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var checked = false
            override fun onActivityResumed(activity: Activity) {
                if (!checked) {
                    checked = true
                    ReleaseUpdater(this@PipsLifeApplication).checkAndPrompt(activity)
                }
            }
            override fun onActivityCreated(a: Activity, s: android.os.Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: android.os.Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}

data class PipsLifeRelease(
    val tag: String,
    val name: String,
    val versionName: String,
    val versionCode: Int,
    val assetId: Long
)

class ReleaseUpdater(private val context: Context) {
    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val LATEST_PATH = "/api/app/release/latest"
        private const val DOWNLOAD_PATH = "/api/app/release/download?asset="
    }

    fun checkAndPrompt(activity: Activity) {
        CoroutineScope(Dispatchers.IO).launch {
            val release = runCatching { fetchLatest() }.getOrNull() ?: return@launch
            if (release.versionCode <= BuildConfig.VERSION_CODE) return@launch
            withContext(Dispatchers.Main) {
                android.app.AlertDialog.Builder(activity)
                    .setTitle("Pips-life update available")
                    .setMessage("${release.name}\n\nInstalled: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nAvailable: ${release.versionName} (${release.versionCode})")
                    .setNegativeButton("Later", null)
                    .setPositiveButton("Download & install") { _, _ -> downloadAndInstall(activity, release) }
                    .show()
            }
        }
    }

    private fun fetchLatest(): PipsLifeRelease {
        val base = BuildConfig.BACKEND_BASE_URL.trimEnd('/')
        val connection = URL(base + LATEST_PATH).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Pips-life/${BuildConfig.VERSION_NAME}")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        if (connection.responseCode !in 200..299) error("Release check failed: ${connection.responseCode}")
        val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        return PipsLifeRelease(
            tag = json.getString("tag"),
            name = json.optString("name", "Pips-life update"),
            versionName = json.getString("versionName"),
            versionCode = json.getInt("versionCode"),
            assetId = json.getLong("assetId")
        )
    }

    private fun downloadAndInstall(activity: Activity, release: PipsLifeRelease) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, "Allow Pips-life to install updates, then tap the update again.", Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return
        }
        val url = BuildConfig.BACKEND_BASE_URL.trimEnd('/') + DOWNLOAD_PATH + release.assetId
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Pips-life ${release.versionName}")
            .setDescription("Downloading Pips-life ${release.tag} from the GitHub release gateway")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "Pips-life-${release.versionName}-${release.versionCode}.apk")
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(request)
        Toast.makeText(context, "Update download started.", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                val cursor = dm.query(DownloadManager.Query().setFilterById(id))
                if (!cursor.moveToFirst()) { cursor.close(); return@launch }
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                cursor.close()
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    withContext(Dispatchers.Main) { install(activity, Uri.parse(uri)) }
                    return@launch
                }
                if (status == DownloadManager.STATUS_FAILED) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Pips-life update download failed.", Toast.LENGTH_LONG).show() }
                    return@launch
                }
                delay(700)
            }
        }
    }

    private fun install(activity: Activity, downloadedUri: Uri) {
        val source = if (downloadedUri.scheme == "file") {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(downloadedUri.path!!))
        } else downloadedUri
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(source, APK_MIME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}

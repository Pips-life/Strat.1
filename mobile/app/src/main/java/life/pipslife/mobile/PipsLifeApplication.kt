package life.pipslife.mobile

import android.app.Activity
import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

data class GitHubRelease(val tag: String, val name: String, val versionName: String, val versionCode: Int, val apkUrl: String)

class ReleaseUpdater(private val context: Context) {
    companion object {
        private const val LATEST_URL = "https://api.github.com/repos/Pips-life/Strat.1/releases/latest"
        private const val APK_MIME = "application/vnd.android.package-archive"
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

    private fun fetchLatest(): GitHubRelease {
        val c = URL(LATEST_URL).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Pips-life/${BuildConfig.VERSION_NAME}")
        c.connectTimeout = 10000
        c.readTimeout = 10000
        if (c.responseCode !in 200..299) error("GitHub release lookup failed: ${c.responseCode}")
        val json = c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        val tag = json.optString("tag_name")
        val name = json.optString("name", tag)
        val body = json.optString("body", "")
        val versionName = Regex("(?m)^Version:\\s*([0-9]+\\.[0-9]+\\.[0-9]+)").find(body)?.groupValues?.get(1)
            ?: tag.removePrefix("v").substringBefore("+")
        val versionCode = Regex("(?m)^VersionCode:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?: versionName.split(".").fold(0) { acc, part -> acc * 100 + (part.toIntOrNull() ?: 0) }
        val assets = json.optJSONArray("assets") ?: error("Release has no assets")
        var apk: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", true)) { apk = a.optString("browser_download_url"); break }
        }
        return GitHubRelease(tag, name, versionName, versionCode, apk ?: error("Release has no APK asset"))
    }

    private fun downloadAndInstall(activity: Activity, release: GitHubRelease) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, "Allow Pips-life to install updates, then tap the update again.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            activity.startActivity(intent)
            return
        }
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("Pips-life ${release.versionName}")
            .setDescription("Downloading Pips-life update ${release.tag}")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "Pips-life-${release.versionName}-${release.versionCode}.apk")
        val id = (context.getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        Toast.makeText(context, "Update download started.", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            val dm = context.getSystemService(DOWNLOAD_SERVICE) as DownloadManager
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
                if (status == DownloadManager.STATUS_FAILED) return@launch
                kotlinx.coroutines.delay(700)
            }
        }
    }

    private fun install(activity: Activity, downloadedUri: Uri) {
        val source = if (downloadedUri.scheme == "file") FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(downloadedUri.path!!)) else downloadedUri
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(source, APK_MIME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
    }
}

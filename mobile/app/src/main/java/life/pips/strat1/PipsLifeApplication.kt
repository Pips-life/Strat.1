package life.pips.strat1

import android.app.Activity
import android.app.Application
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import life.pips.strat1.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases and offers user-approved APK updates. */
class PipsLifeApplication : Application() {
    private val handler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) handler.postDelayed({ checkForUpdate(activity) }, 1200)
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityCreated(a: Activity, s: android.os.Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, s: android.os.Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }

    private fun checkForUpdate(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        Thread {
            val release = runCatching { fetchLatestRelease() }.getOrNull() ?: return@Thread
            if (release.versionCode <= BuildConfig.VERSION_CODE) return@Thread
            val apk = release.apkUrl ?: return@Thread
            val releaseKey = "${release.versionName}+${release.versionCode}"
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (prefs.getString(LAST_PROMPTED, null) == releaseKey) return@Thread
            handler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                prefs.edit().putString(LAST_PROMPTED, releaseKey).apply()
                AlertDialog.Builder(activity)
                    .setTitle("Pips-life update available")
                    .setMessage("Pips-life ${release.versionName} (${release.versionCode}) is available. You have ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}). Download and install the update from GitHub?")
                    .setNegativeButton("LATER", null)
                    .setPositiveButton("DOWNLOAD & INSTALL") { _, _ -> downloadAndInstall(activity, release, apk) }
                    .show()
            }
        }.start()
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Pips-life/${BuildConfig.VERSION_NAME}")
        }
        return connection.use {
            if (it.responseCode !in 200..299) error("GitHub releases request failed: ${it.responseCode}")
            val json = JSONObject(it.inputStream.bufferedReader().use { r -> r.readText() })
            val tagVersion = json.optString("tag_name").removePrefix("v")
            val releaseName = json.optString("name")
            val assets = json.optJSONArray("assets") ?: return@use ReleaseInfo(tagVersion, 0, null)
            var apkUrl: String? = null
            var assetVersionCode = 0
            var assetVersionName = tagVersion
            val pattern = Regex("Pips-life-(\\d+\\.\\d+\\.\\d+)-(\\d+)\\.apk", RegexOption.IGNORE_CASE)
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (!name.endsWith(".apk", true)) continue
                val match = pattern.find(name)
                if (match != null) {
                    assetVersionName = match.groupValues[1]
                    assetVersionCode = match.groupValues[2].toIntOrNull() ?: 0
                }
                apkUrl = asset.optString("browser_download_url").takeIf { u -> u.startsWith("https://github.com/") }
                if (apkUrl != null) break
            }
            val versionName = if (assetVersionName.isBlank()) releaseName else assetVersionName
            ReleaseInfo(versionName, assetVersionCode, apkUrl)
        }
    }

    private fun downloadAndInstall(activity: Activity, release: ReleaseInfo, apkUrl: String) {
        val filename = "Pips-life-${release.versionName}-${release.versionCode}.apk"
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Pips-life ${release.versionName}")
            .setDescription("Downloading Pips-life update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, filename)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val id = manager.enqueue(request)
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                openDownloadedApk(activity, manager, id)
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        Toast.makeText(this, "Pips-life update downloading…", Toast.LENGTH_LONG).show()
    }

    private fun openDownloadedApk(activity: Activity, manager: DownloadManager, id: Long) {
        val cursor: Cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return
        cursor.use {
            if (!it.moveToFirst()) return
            val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusIndex >= 0 && it.getInt(statusIndex) != DownloadManager.STATUS_SUCCESSFUL) {
                Toast.makeText(this, "Pips-life update download failed.", Toast.LENGTH_LONG).show()
                return
            }
            val uri = manager.getUriForDownloadedFile(id) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                Toast.makeText(this, "Allow Pips-life to install updates, then tap the downloaded APK notification.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }

    private data class ReleaseInfo(val versionName: String, val versionCode: Int, val apkUrl: String?)

    companion object {
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/Pips-life/Strat.1/releases/latest"
        private const val PREFS = "pips_life_updates"
        private const val LAST_PROMPTED = "last_prompted_release"
    }
}

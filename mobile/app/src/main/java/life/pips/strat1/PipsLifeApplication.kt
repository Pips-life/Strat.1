package life.pips.strat1

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Scans GitHub Releases for the official, numbered Pips-life APK. */
class PipsLifeApplication : Application() {
    private val handler = Handler(Looper.getMainLooper())
    private var updateReceiver: BroadcastReceiver? = null
    private var lastCheckAt = 0L

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) {
                    val now = System.currentTimeMillis()
                    if (now - lastCheckAt >= CHECK_INTERVAL_MS) {
                        lastCheckAt = now
                        handler.postDelayed({ checkForUpdate(activity) }, 1200L)
                    }
                }
            }
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivityCreated(a: Activity, s: android.os.Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, s: android.os.Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }

    private fun checkForUpdate(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        Thread({
            val release = runCatching { fetchLatestRelease() }.getOrNull() ?: return@Thread
            if (release.versionCode <= BuildConfig.VERSION_CODE || release.apkUrl.isNullOrBlank()) return@Thread
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (prefs.getInt(LAST_PROMPTED_CODE, -1) == release.versionCode) return@Thread
            handler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                AlertDialog.Builder(activity)
                    .setTitle("Pips-life update available")
                    .setMessage("Pips-life ${release.versionName} (build ${release.versionCode}) is available. You are running ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}).")
                    .setNegativeButton("LATER", null)
                    .setPositiveButton("DOWNLOAD & INSTALL") { _, _ ->
                        prefs.edit().putInt(LAST_PROMPTED_CODE, release.versionCode).apply()
                        downloadAndInstall(activity, release)
                    }
                    .show()
            }
        }, "pips-life-release-check").start()
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Pips-life/${BuildConfig.VERSION_NAME}")
        }
        return try {
            if (connection.responseCode !in 200..299) error("GitHub releases request failed: ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) error("Not a stable release")
            val assets = json.optJSONArray("assets") ?: error("Latest release has no assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                val match = APK_NAME.matchEntire(name) ?: continue
                val url = asset.optString("browser_download_url")
                if (url.startsWith("https://github.com/")) {
                    return ReleaseInfo(match.groupValues[1], match.groupValues[2].toInt(), url)
                }
            }
            error("Latest release has no numbered Pips-life APK")
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadAndInstall(activity: Activity, release: ReleaseInfo) {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        updateReceiver?.let { runCatching { unregisterReceiver(it) } }
        val filename = "pips-life-${release.versionName}-${release.versionCode}.apk"
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("Pips-life ${release.versionName}")
            .setDescription("Downloading the official Pips-life GitHub release")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, filename)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val downloadId = manager.enqueue(request)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                runCatching { unregisterReceiver(this) }
                updateReceiver = null
                openDownloadedApk(activity, manager, downloadId)
            }
        }
        updateReceiver = receiver
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        Toast.makeText(this, "Pips-life build ${release.versionCode} downloading…", Toast.LENGTH_LONG).show()
    }

    private fun openDownloadedApk(activity: Activity, manager: DownloadManager, id: Long) {
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return
        cursor.use {
            if (!it.moveToFirst()) return
            if (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL) {
                Toast.makeText(this, "Pips-life update download failed.", Toast.LENGTH_LONG).show()
                return
            }
            val uri = manager.getUriForDownloadedFile(id) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(activity)
                    .setTitle("Allow Pips-life updates")
                    .setMessage("Android needs permission to install APK updates downloaded directly from GitHub.")
                    .setNegativeButton("LATER", null)
                    .setPositiveButton("OPEN SETTINGS") { _, _ ->
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    }
                    .show()
                return
            }
            activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }

    private data class ReleaseInfo(val versionName: String, val versionCode: Int, val apkUrl: String)

    companion object {
        private val APK_NAME = Regex("Pips-life-(\\d+\\.\\d+\\.\\d+)-(\\d+)\\.apk", RegexOption.IGNORE_CASE)
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/Pips-life/Strat.1/releases/latest"
        private const val PREFS = "pips_life_updates"
        private const val LAST_PROMPTED_CODE = "last_prompted_version_code"
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}

package life.pips.strat1

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Single Pips-life mobile release updater.
 * GitHub Releases are the only update source. Releases are identified by both
 * semantic versionName and monotonically increasing Android versionCode.
 */
class PipsLifeApplication : Application() {
    private val handler = Handler(Looper.getMainLooper())
    private var updateReceiver: BroadcastReceiver? = null

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
        Thread(name = "pips-life-release-check") {
            val release = runCatching { fetchLatestRelease() }.getOrNull() ?: return@Thread
            if (release.apkUrl.isNullOrBlank() || release.versionCode <= BuildConfig.VERSION_CODE) return@Thread

            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (prefs.getInt(LAST_PROMPTED_CODE, -1) == release.versionCode) return@Thread

            handler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                prefs.edit().putInt(LAST_PROMPTED_CODE, release.versionCode).apply()
                AlertDialog.Builder(activity)
                    .setTitle("Pips-life update available")
                    .setMessage(
                        "Pips-life ${release.versionName} (build ${release.versionCode}) is available.\n\n" +
                            "You are running ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}).\n\n" +
                            release.notes
                    )
                    .setNegativeButton("LATER", null)
                    .setPositiveButton("DOWNLOAD & INSTALL") { _, _ ->
                        downloadAndInstall(activity, release.versionName ?: BuildConfig.VERSION_NAME, release.apkUrl!!)
                    }
                    .show()
            }
        }.start()
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Pips-life/${BuildConfig.VERSION_NAME}")
            if (connection.responseCode !in 200..299) error("GitHub releases request failed: ${connection.responseCode}")

            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return ReleaseInfo(null, 0, null, "")

            val tagVersion = json.optString("tag_name").removePrefix("v")
            val body = json.optString("body")
            val versionCode = Regex("(?im)^\\s*versionCode\\s*[:=]\\s*(\\d+)")
                .find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val assets = json.optJSONArray("assets") ?: return ReleaseInfo(tagVersion, versionCode, null, body)
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.startsWith("Pips-life-") && name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                        .takeIf { it.startsWith("https://github.com/") }
                    if (apkUrl != null) break
                }
            }
            val notes = body.trim().let { if (it.length > 600) it.take(600) + "…" else it }
            ReleaseInfo(tagVersion, versionCode, apkUrl, notes)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadAndInstall(activity: Activity, version: String, apkUrl: String) {
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Pips-life $version")
            .setDescription("Downloading the official Pips-life GitHub release")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "Pips-life-$version.apk")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val downloadId = manager.enqueue(request)

        updateReceiver?.let { runCatching { unregisterReceiver(it) } }
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

    private data class ReleaseInfo(
        val versionName: String?,
        val versionCode: Int,
        val apkUrl: String?,
        val notes: String
    )

    companion object {
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/Pips-life/Strat.1/releases/latest"
        private const val PREFS = "pips_life_updates"
        private const val LAST_PROMPTED_CODE = "last_prompted_version_code"
    }
}

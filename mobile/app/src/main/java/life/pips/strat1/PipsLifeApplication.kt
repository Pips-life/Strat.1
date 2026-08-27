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

/** Checks the public GitHub Releases feed. No secrets are embedded in the APK. */
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
        Thread {
            val release = runCatching { fetchLatestRelease() }.getOrNull() ?: return@Thread
            val latest = release.version ?: return@Thread
            val apk = release.apkUrl ?: return@Thread
            if (!isNewer(latest, BuildConfig.VERSION_NAME)) return@Thread
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (prefs.getString(LAST_PROMPTED, null) == latest) return@Thread
            handler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                AlertDialog.Builder(activity)
                    .setTitle("Pips-life update available")
                    .setMessage("Pips-life $latest is available. You are running ${BuildConfig.VERSION_NAME}. Download and install the GitHub release?")
                    .setNegativeButton("LATER", null)
                    .setPositiveButton("DOWNLOAD & INSTALL") { _, _ ->
                        prefs.edit().putString(LAST_PROMPTED, latest).apply()
                        downloadAndInstall(activity, latest, apk)
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
            if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) return ReleaseInfo(null, null)
            val tag = json.optString("tag_name").removePrefix("v")
            val assets = json.optJSONArray("assets") ?: return ReleaseInfo(null, null)
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                val candidate = asset.optString("browser_download_url")
                if (name.startsWith("Pips-life-") && name.endsWith(".apk", true) && candidate.startsWith("https://github.com/")) {
                    apk = candidate
                    break
                }
            }
            ReleaseInfo(tag.takeIf { it.isNotBlank() }, apk)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadAndInstall(activity: Activity, version: String, apkUrl: String) {
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Pips-life $version")
            .setDescription("Downloading Pips-life update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "Pips-life-$version.apk")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val downloadId = manager.enqueue(request)

        updateReceiver?.let { runCatching { unregisterReceiver(it) } }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
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
                Toast.makeText(this, "Allow Pips-life to install updates, then return to Pips-life.", Toast.LENGTH_LONG).show()
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0..2) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av > bv
        }
        return false
    }

    private data class ReleaseInfo(val version: String?, val apkUrl: String?)

    companion object {
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/Pips-life/Strat.1/releases/latest"
        private const val PREFS = "pips_life_updates"
        private const val LAST_PROMPTED = "last_prompted_release"
    }
}

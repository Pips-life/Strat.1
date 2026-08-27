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

/** Checks the public GitHub Releases feed. Secrets are never embedded in the APK. */
class PipsLifeApplication : Application() {
    private var resumedActivity: Activity? = null
    private var receiverRegistered = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = activity
                if (activity is MainActivity) {
                    handler.postDelayed({ checkForUpdate(activity) }, 1200)
                }
            }
            override fun onActivityPaused(activity: Activity) { if (resumedActivity === activity) resumedActivity = null }
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
            if (!isNewer(latest, BuildConfig.VERSION_NAME)) return@Thread
            val apk = release.apkUrl ?: return@Thread
            val lastPrompted = getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_PROMPTED, null)
            if (lastPrompted == latest) return@Thread
            handler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(LAST_PROMPTED, latest).apply()
                AlertDialog.Builder(activity)
                    .setTitle("Pips-life update available")
                    .setMessage("Pips-life ${latest} is available. Your current version is ${BuildConfig.VERSION_NAME}. Download the new APK from GitHub?")
                    .setNegativeButton("LATER", null)
                    .setPositiveButton("DOWNLOAD & INSTALL") { _, _ -> downloadAndInstall(activity, latest, apk) }
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
            val tag = json.optString("tag_name").removePrefix("v")
            val assets = json.optJSONArray("assets") ?: return@use ReleaseInfo(null, null)
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk", true)) {
                    apk = asset.optString("browser_download_url").takeIf { u -> u.startsWith("https://github.com/") }
                    break
                }
            }
            ReleaseInfo(tag.takeIf { it.isNotBlank() }, apk)
        }
    }

    private fun downloadAndInstall(activity: Activity, version: String, apkUrl: String) {
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Pips-life $version")
            .setDescription("Downloading Pips-life update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "Pips-life-$version.apk")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = manager.enqueue(request)

        if (!receiverRegistered) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id != downloadId) return
                    openDownloadedApk(activity, manager, id)
                }
            }
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
            receiverRegistered = true
        }
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
                Toast.makeText(this, "Allow Pips-life to install updates, then open the downloaded APK.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(install)
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

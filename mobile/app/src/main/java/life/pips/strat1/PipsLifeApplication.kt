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

/** Checks the authenticated release gateway without exposing GitHub credentials to the APK. */
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
            if (release.versionCode <= BuildConfig.VERSION_CODE || release.assetId <= 0L) return@Thread
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
        val connection = (URL(BuildConfig.BACKEND_BASE_URL.trimEnd('/') + LATEST_RELEASE_PATH).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Pips-life/${BuildConfig.VERSION_NAME}")
        }
        return try {
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
            if (versionName.isBlank() || versionCode <= 0 || assetId <= 0L) error("Update service returned incomplete release information")
            ReleaseInfo(versionName, versionCode, assetId)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadAndInstall(activity: Activity, release: ReleaseInfo) {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        updateReceiver?.let { runCatching { unregisterReceiver(it) } }
        val filename = "pips-life-${release.versionName}-${release.versionCode}.apk"
        val downloadUrl = BuildConfig.BACKEND_BASE_URL.trimEnd('/') + DOWNLOAD_PATH + "?asset=" + release.assetId
        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("Pips-life ${release.versionName}")
            .setDescription("Downloading the official Pips-life release")
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
                    .setMessage("Android needs permission to install APK updates downloaded by Pips-life.")
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

    private data class ReleaseInfo(val versionName: String, val versionCode: Int, val assetId: Long)

    companion object {
        private const val LATEST_RELEASE_PATH = "/api/app/release/latest"
        private const val DOWNLOAD_PATH = "/api/app/release/download"
        private const val PREFS = "pips_life_updates"
        private const val LAST_PROMPTED_CODE = "last_prompted_version_code"
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}

package life.pips.strat1

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import kotlin.concurrent.thread

/** Checks the Pips-life backend for the latest private GitHub Release and installs its APK. */
class UpdateGateActivity : android.app.Activity() {
    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val RELEASE_LATEST_PATH = "/api/app/release/latest"
        private const val RELEASE_DOWNLOAD_PATH = "/api/app/release/download?asset="
    }

    private var pendingApk: File? = null
    private var downloadId = -1L
    private var receiverRegistered = false
    private var awaitingInstallPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkLatestRelease()
    }

    override fun onResume() {
        super.onResume()
        if (awaitingInstallPermission && canInstallPackages()) {
            awaitingInstallPermission = false
            pendingApk?.let { installApk(it) }
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) try { unregisterReceiver(downloadReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun backendBaseUrl(): String = BuildConfig.BACKEND_BASE_URL.trimEnd('/')

    private fun checkLatestRelease() {
        thread(name = "pips-life-release-check") {
            try {
                val connection = (URL(backendBaseUrl() + RELEASE_LATEST_PATH).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 7000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "Pips-life/${BuildConfig.VERSION_NAME}")
                }
                if (connection.responseCode !in 200..299) throw IllegalStateException("Release gateway ${connection.responseCode}")
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val versionName = jsonString(body, "versionName") ?: return@thread openApp()
                val versionCode = jsonNumber(body, "versionCode") ?: 0
                val assetId = jsonNumber(body, "assetId") ?: return@thread openApp()
                val releaseUrl = jsonString(body, "releaseUrl") ?: "https://github.com/Pips-life/Strat.1/releases/latest"
                if (versionCode > BuildConfig.VERSION_CODE || (versionCode == 0 && isNewer(versionName, BuildConfig.VERSION_NAME))) {
                    runOnUiThread { showUpdateDialog(versionName, assetId, releaseUrl) }
                } else openApp()
            } catch (_: Exception) {
                // Release checks are non-blocking. Backend/GitHub outages never prevent app access.
                openApp()
            }
        }
    }

    private fun showUpdateDialog(version: String, assetId: Long, releaseUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Pips-life update available")
            .setMessage("Pips-life $version is available from the official GitHub release. Download and install it now?")
            .setCancelable(false)
            .setNegativeButton("Later") { _, _ -> openApp() }
            .setPositiveButton("Download & Install") { _, _ -> downloadApk(version, assetId, releaseUrl) }
            .show()
    }

    private fun downloadApk(version: String, assetId: Long, releaseUrl: String) {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return showReleaseFallback(releaseUrl)
        val file = File(dir, "Pips-life-$version.apk")
        if (file.exists()) file.delete()
        pendingApk = file
        val downloadUrl = backendBaseUrl() + RELEASE_DOWNLOAD_PATH + assetId
        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("Pips-life $version")
            .setDescription("Downloading the official Pips-life GitHub release")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        downloadId = manager.enqueue(request)
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(downloadReceiver, filter)
        receiverRegistered = true
        Toast.makeText(this, "Downloading Pips-life $version…", Toast.LENGTH_LONG).show()
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
            val file = pendingApk ?: return
            if (!file.exists() || file.length() == 0L) {
                Toast.makeText(this@UpdateGateActivity, "Update download failed.", Toast.LENGTH_LONG).show()
                openApp(); return
            }
            installApk(file)
        }
    }

    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun installApk(file: File) {
        if (!canInstallPackages()) {
            awaitingInstallPermission = true
            Toast.makeText(this, "Allow Pips-life to install updates, then return to continue.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }

    private fun showReleaseFallback(releaseUrl: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl))); finish()
    }

    private fun openApp() {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            startActivity(Intent(this, MainActivity::class.java)); finish()
        }
    }

    private fun jsonString(json: String, key: String): String? {
        val pattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        return pattern.matcher(json).let { if (it.find()) it.group(1) else null }
    }

    private fun jsonNumber(json: String, key: String): Long? {
        val pattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(\\d+)")
        return pattern.matcher(json).let { if (it.find()) it.group(1).toLongOrNull() else null }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        fun parts(value: String) = value.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val r = parts(remote); val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }; val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}

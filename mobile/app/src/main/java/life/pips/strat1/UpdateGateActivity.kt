package life.pips.strat1

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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

/** Checks GitHub Releases and offers the official APK when a newer numbered release exists. */
class UpdateGateActivity : android.app.Activity() {
    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/Pips-life/Strat.1/releases/latest"
        private const val APK_MIME = "application/vnd.android.package-archive"
    }

    private var pendingApk: File? = null
    private var downloadId: Long = -1L
    private var receiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkLatestRelease()
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(downloadReceiver) } catch (_: Exception) { }
        }
        super.onDestroy()
    }

    private fun checkLatestRelease() {
        thread(name = "pips-life-release-check") {
            try {
                val connection = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 7000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    setRequestProperty("User-Agent", "Pips-life/${BuildConfig.VERSION_NAME}")
                }
                if (connection.responseCode !in 200..299) throw IllegalStateException("GitHub ${connection.responseCode}")
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val tag = jsonString(body, "tag_name") ?: return@thread openApp()
                val releaseUrl = jsonString(body, "html_url") ?: "https://github.com/Pips-life/Strat.1/releases/latest"
                val apkUrl = findApkAssetUrl(body) ?: return@thread openApp()
                val remoteVersion = tag.removePrefix("v").trim()
                if (isNewer(remoteVersion, BuildConfig.VERSION_NAME)) {
                    runOnUiThread { showUpdateDialog(remoteVersion, apkUrl, releaseUrl) }
                } else openApp()
            } catch (_: Exception) {
                openApp()
            }
        }
    }

    private fun showUpdateDialog(version: String, apkUrl: String, releaseUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Pips-life update available")
            .setMessage("Pips-life $version is available from the official GitHub release. Download and install it now?")
            .setCancelable(false)
            .setNegativeButton("Later") { _, _ -> openApp() }
            .setPositiveButton("Download & Install") { _, _ -> downloadApk(version, apkUrl, releaseUrl) }
            .show()
    }

    private fun downloadApk(version: String, apkUrl: String, releaseUrl: String) {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return showReleaseFallback(releaseUrl)
        val file = File(dir, "Pips-life-$version.apk")
        if (file.exists()) file.delete()
        pendingApk = file

        val request = DownloadManager.Request(Uri.parse(apkUrl))
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
                openApp()
                return
            }
            installApk(file)
        }
    }

    private fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow Pips-life to install updates, then return to continue.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        finish()
    }

    private fun showReleaseFallback(releaseUrl: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)))
        finish()
    }

    private fun openApp() {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun jsonString(json: String, key: String): String? {
        val pattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        return pattern.matcher(json).let { if (it.find()) it.group(1) else null }
    }

    private fun findApkAssetUrl(json: String): String? {
        val pattern = Pattern.compile("\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+\\.apk)\\\"")
        return pattern.matcher(json).let { if (it.find()) it.group(1) else null }
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

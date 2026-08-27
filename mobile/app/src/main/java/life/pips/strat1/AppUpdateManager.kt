package life.pips.strat1

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub-release based updater for Pips-life.
 *
 * Release policy:
 * - Git tag: v<versionName>
 * - Android versionCode must increase monotonically.
 * - The GitHub release must contain exactly/at least one .apk asset.
 * - Updates are offered only when the remote versionCode is newer.
 *
 * Android still requires the user to approve installation of an APK from an
 * external source. This class downloads the signed release APK and hands it
 * to the system package installer after that user approval.
 */
class AppUpdateManager(private val activity: Activity) {
    private val repo = "Pips-life/Strat.1"
    private val releasesUrl = "https://api.github.com/repos/$repo/releases/latest"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == downloadId) installDownloadedApk(id)
        }
    }
    private var downloadId = -1L

    fun checkForUpdate() {
        Thread {
            try {
                val connection = (URL(releasesUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val release = JSONObject(body)
                val tag = release.optString("tag_name")
                val remoteCode = parseVersionCode(release)
                val assetUrl = release.optJSONArray("assets")?.let { assets ->
                    (0 until assets.length()).map { assets.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk", true) }
                        ?.optString("browser_download_url")
                }.orEmpty()
                if (remoteCode <= currentVersionCode() || assetUrl.isBlank()) return@Thread
                val title = release.optString("name").ifBlank { tag }
                activity.runOnUiThread { showUpdatePrompt(title, tag, assetUrl) }
            } catch (_: Exception) {
                // Update checks are non-fatal. The trading app remains usable offline.
            }
        }.start()
    }

    private fun currentVersionCode(): Int = try {
        @Suppress("DEPRECATION")
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode
    } catch (_: Exception) { 0 }

    private fun parseVersionCode(release: JSONObject): Int {
        val body = release.optString("body")
        val marker = Regex("versionCode\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
        if (marker != null) return marker.toIntOrNull() ?: 0
        return release.optString("tag_name").removePrefix("v").split(".")
            .mapNotNull { it.toIntOrNull() }.let { parts -> if (parts.size == 3) parts[0] * 10000 + parts[1] * 100 + parts[2] else 0 }
    }

    private fun showUpdatePrompt(title: String, tag: String, apkUrl: String) {
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("Pips-life update available")
            .setMessage("$title ($tag) is ready. Download the signed APK from the official Pips-life GitHub release?")
            .setNegativeButton("Later", null)
            .setPositiveButton("Download & Install") { _, _ -> downloadApk(apkUrl, tag) }
            .show()
    }

    private fun downloadApk(url: String, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Allow Pips-life to install updates, then tap Download & Install again.", Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        try {
            val file = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Pips-life-$tag.apk")
            if (file.exists()) file.delete()
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Pips-life $tag")
                .setDescription("Downloading official Pips-life update")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(file))
            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            activity.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
            downloadId = manager.enqueue(request)
            Toast.makeText(activity, "Pips-life update downloading…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(activity, "Update download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun installDownloadedApk(id: Long) {
        try {
            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(id)
            manager.query(query).use { cursor ->
                if (!cursor.moveToFirst()) return
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status != DownloadManager.STATUS_SUCCESSFUL) return
                val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                val file = File(Uri.parse(localUri).path ?: return)
                val contentUri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "Could not open the update installer: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            try { activity.unregisterReceiver(receiver) } catch (_: Exception) { }
        }
    }
}

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
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Checks GitHub Releases for a newer stable Pips-life APK and offers an in-app update. */
class ReleaseUpdateChecker(private val context: Context) {
    companion object {
        private const val OWNER = "Pips-life"
        private const val REPO = "Strat.1"
        private const val LATEST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
        private const val TAG_PREFIX = "v"
        private const val APK_PREFIX = "Pips-life-"
        private const val APK_SUFFIX = ".apk"
        private val checkedThisProcess = AtomicBoolean(false)
    }

    private var downloadId = -1L
    private var receiverRegistered = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != downloadId) return
            installDownloadedApk(id)
        }
    }

    fun checkOnLaunch() {
        if (!checkedThisProcess.compareAndSet(false, true)) return
        registerReceiver()
        thread(name = "pips-life-release-check") {
            try {
                val connection = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 7000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Pips-life-Android")
                }
                if (connection.responseCode !in 200..299) return@thread
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) return@thread
                val tag = json.optString("tag_name")
                val remoteVersion = tag.removePrefix(TAG_PREFIX)
                val currentVersion = BuildConfig.VERSION_NAME
                if (compareVersions(remoteVersion, currentVersion) <= 0) return@thread

                val assets = json.optJSONArray("assets") ?: return@thread
                var apkUrl: String? = null
                var apkName: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    if (name.startsWith(APK_PREFIX) && name.endsWith(APK_SUFFIX)) {
                        apkName = name
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                if (apkUrl.isNullOrBlank()) return@thread

                val title = json.optString("name").ifBlank { "Pips-life $remoteVersion" }
                val notes = json.optString("body").trim().let { if (it.length > 500) it.take(500) + "…" else it }
                context.getSharedPreferences("pips_life_updates", Context.MODE_PRIVATE)
                    .edit().putString("available_version", remoteVersion).apply()

                (context as? android.app.Activity)?.runOnUiThread {
                    AlertDialog.Builder(context)
                        .setTitle("Pips-life update available")
                        .setMessage("$title\n\nA newer version ($remoteVersion) is ready.\n\n$notes")
                        .setNegativeButton("Later", null)
                        .setPositiveButton("Download") { _, _ -> downloadApk(apkUrl, apkName ?: "Pips-life-$remoteVersion.apk") }
                        .show()
                }
            } catch (_: Exception) {
                // Update checks are non-blocking. Failure must never prevent the trading UI from opening.
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(downloadReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun downloadApk(url: String, fileName: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Pips-life update")
                .setDescription("Downloading $fileName")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            downloadId = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(context, "Pips-life update downloading…", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Could not start the update download.", Toast.LENGTH_LONG).show()
        }
    }

    private fun installDownloadedApk(id: Long) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = manager.getUriForDownloadedFile(id) ?: run {
            Toast.makeText(context, "Update download failed.", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(context)
                .setTitle("Allow Pips-life updates")
                .setMessage("Android needs permission to install updates downloaded directly from GitHub. Enable 'Allow from this source', then return to Pips-life.")
                .setNegativeButton("Later", null)
                .setPositiveButton("Open settings") { _, _ ->
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                }
                .show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Android could not open the downloaded update.", Toast.LENGTH_LONG).show()
        }
    }

    fun unregister() {
        if (!receiverRegistered) return
        try { context.unregisterReceiver(downloadReceiver) } catch (_: Exception) { }
        receiverRegistered = false
    }

    private fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val pa = parts(a); val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val av = pa.getOrElse(i) { 0 }; val bv = pb.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }
}

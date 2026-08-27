package life.pips.strat1

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
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Pips-life GitHub release updater.
 *
 * GitHub Releases are the source of truth for mobile releases. The app compares
 * the release's explicit build number with BuildConfig.VERSION_CODE, asks the
 * user before downloading, then opens Android's package installer after download.
 * Android still requires the user to confirm the installation; silent installs are
 * intentionally not attempted.
 */
class UpdateManager(private val activity: MainActivity) {
    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/Pips-life/Strat.1/releases/latest"
        private const val APK_NAME = "Pips-life-update.apk"
        private const val PREFS = "pips_life_updates"
        private const val PENDING_APK = "pending_apk"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun checkForUpdate() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { fetchLatestRelease() }
                .onSuccess { release ->
                    if (release.buildNumber > BuildConfig.VERSION_CODE && release.apkUrl.isNotBlank()) {
                        withContext(Dispatchers.Main) { showUpdatePrompt(release) }
                    }
                }
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Pips-life/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub release check failed: HTTP ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            val tag = json.optString("tag_name")
            val body = json.optString("body")
            val build = Regex("(?im)^\\s*versionCode\\s*[:=]\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("(?:build|code)[-_ ]?(\\d+)", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.toIntOrNull()
                ?: return ReleaseInfo(tag, 0, "", "")
            val assets = json.optJSONArray("assets") ?: return ReleaseInfo(tag, build, "", body)
            var apkUrl = ""
            var apkName = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true) && name.startsWith("Pips-life-")) {
                    apkName = name
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            return ReleaseInfo(tag, build, apkUrl, body, apkName)
        }
    }

    private fun showUpdatePrompt(release: ReleaseInfo) {
        AlertDialog.Builder(activity)
            .setTitle("Pips-life update available")
            .setMessage("Version ${release.tag} (build ${release.buildNumber}) is available. Download it from the official GitHub release and install it?")
            .setNegativeButton("Later", null)
            .setPositiveButton("Download & Install") { _, _ -> download(release.apkUrl) }
            .show()
    }

    private fun download(url: String) {
        val file = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Pips-life update")
            .setDescription("Downloading the latest Pips-life release")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file))

        val downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("download_id", downloadId)
            .putString(PENDING_APK, file.absolutePath)
            .apply()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                runCatching { activity.unregisterReceiver(this) }
                activity.runOnUiThread { installDownloadedApk(file) }
            }
        }
        activity.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
    }

    fun resumePendingInstall() {
        val path = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PENDING_APK, null) ?: return
        val file = File(path)
        if (file.exists()) installDownloadedApk(file)
    }

    private fun installDownloadedApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            activity.startActivity(intent)
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private data class ReleaseInfo(
        val tag: String,
        val buildNumber: Int,
        val apkUrl: String,
        val body: String,
        val apkName: String = ""
    )
}

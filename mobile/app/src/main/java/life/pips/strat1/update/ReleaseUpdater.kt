package life.pips.strat1.update

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** Checks GitHub Releases and installs a newer APK asset. */
object ReleaseUpdater {
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/Pips-life/Strat.1/releases/latest"
    private const val APK_PREFIX = "Pips-life-"

    data class Release(val tag: String, val versionName: String, val apkUrl: String)

    suspend fun latest(): Result<Release?> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Pips-life-Android")
                .build()
            OkHttpClient().newCall(request).execute().use { response ->
                if (response.code == 404) return@use null
                if (!response.isSuccessful) error("GitHub release check failed: HTTP ${response.code}")
                val root = JSONObject(response.body?.string().orEmpty())
                val tag = root.optString("tag_name")
                val version = tag.removePrefix("v")
                val assets = root.optJSONArray("assets") ?: return@use null
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name.startsWith(APK_PREFIX) && name.endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                if (tag.isBlank() || apkUrl.isNullOrBlank()) return@use null
                Release(tag, version, apkUrl!!)
            }
        }
    }

    fun isNewer(current: String, latest: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(current); val b = parts(latest)
        for (i in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrElse(i) { 0 }; val bv = b.getOrElse(i) { 0 }
            if (bv != av) return bv > av
        }
        return false
    }

    fun prompt(activity: Activity, release: Release) {
        AlertDialog.Builder(activity)
            .setTitle("Pips-life update available")
            .setMessage("Version ${release.versionName} is available on GitHub. Download and install it now?")
            .setNegativeButton("Later", null)
            .setPositiveButton("Download & Install") { _, _ -> download(activity, release) }
            .show()
    }

    private fun download(context: Context, release: Release) {
        val fileName = "Pips-life-${release.versionName}.apk"
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("Pips-life ${release.versionName}")
            .setDescription("Downloading the Pips-life update from GitHub Releases")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setMimeType("application/vnd.android.package-archive")
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = manager.enqueue(request)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                ctx.unregisterReceiver(this)
                val file = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                if (file.exists()) install(ctx, file)
            }
        }
        context.registerReceiver(receiver, android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
    }

    private fun install(context: Context, file: File) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

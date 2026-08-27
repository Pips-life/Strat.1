package life.pips.strat1

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import kotlin.concurrent.thread

/**
 * Pips-life release gate.
 *
 * On every launch the app checks the latest published GitHub release. Release
 * tags are semantic versions (for example v0.2.1). If the release is newer,
 * the user is prompted to download the APK from that GitHub release. The APK
 * is then handed to Android Package Installer for the final upgrade approval.
 *
 * No backend or MetaApi secret is stored here.
 */
class UpdateGateActivity : Activity() {
    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/Pips-life/Strat.1/releases/latest"
        private const val APK_NAME = "pips-life-update.apk"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkLatestRelease()
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
                if (connection.responseCode !in 200..299) {
                    connection.disconnect()
                    return@thread openApp()
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val tag = jsonString(body, "tag_name") ?: return@thread openApp()
                val releaseUrl = jsonString(body, "html_url")
                    ?: "https://github.com/Pips-life/Strat.1/releases/latest"
                val apkUrl = findApkAssetUrl(body) ?: return@thread openApp()
                val remoteVersion = tag.removePrefix("v").trim()

                if (isNewer(remoteVersion, BuildConfig.VERSION_NAME)) {
                    runOnUiThread { showUpdateDialog(remoteVersion, releaseUrl, apkUrl) }
                } else {
                    openApp()
                }
            } catch (_: Exception) {
                // GitHub being unavailable must never block the trading UI.
                openApp()
            }
        }
    }

    private fun showUpdateDialog(version: String, releaseUrl: String, apkUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Pips-life update available")
            .setMessage("Version $version is available from the official Pips-life GitHub release. Download and install it now?")
            .setCancelable(false)
            .setNegativeButton("Later") { _, _ -> openApp() }
            .setPositiveButton("Download & Install") { _, _ -> downloadAndInstall(version, releaseUrl, apkUrl) }
            .show()
    }

    private fun downloadAndInstall(version: String, releaseUrl: String, apkUrl: String) {
        Toast.makeText(this, "Downloading Pips-life $version…", Toast.LENGTH_LONG).show()
        thread(name = "pips-life-apk-download") {
            try {
                val connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Pips-life/${BuildConfig.VERSION_NAME}")
                }
                if (connection.responseCode !in 200..299) throw IllegalStateException("GitHub download returned ${connection.responseCode}")

                val target = File(cacheDir, APK_NAME)
                connection.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                connection.disconnect()

                runOnUiThread { installApk(target, releaseUrl) }
            } catch (error: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Update download failed")
                        .setMessage("Pips-life could not download the update. You can open the official GitHub release instead.")
                        .setNegativeButton("Close") { _, _ -> openApp() }
                        .setPositiveButton("Open GitHub") { _, _ ->
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)))
                            finish()
                        }
                        .show()
                }
            }
        }
    }

    private fun installApk(file: File, releaseUrl: String) {
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Allow Pips-life updates")
                .setMessage("Android requires permission to install updates downloaded from GitHub. Allow it, then return to Pips-life to continue.")
                .setNegativeButton("Later") { _, _ -> openApp() }
                .setPositiveButton("Open permission") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    Toast.makeText(this, "After allowing installs, open Pips-life again to install the update.", Toast.LENGTH_LONG).show()
                    finish()
                }
                .show()
            return
        }

        try {
            val apkUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            finish()
        } catch (_: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Open installer")
                .setMessage("Android could not open the update installer. You can open the official GitHub release manually.")
                .setPositiveButton("Open GitHub") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)))
                    finish()
                }
                .setNegativeButton("Later") { _, _ -> openApp() }
                .show()
        }
    }

    private fun openApp() {
        runOnUiThread {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun jsonString(json: String, key: String): String? {
        val pattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        return pattern.matcher(json).let { if (it.find()) it.group(1) else null }
    }

    private fun findApkAssetUrl(json: String): String? {
        val assetPattern = Pattern.compile("\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+\\.apk)\\\"")
        return assetPattern.matcher(json).let { if (it.find()) it.group(1) else null }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        fun parts(value: String) = value.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}

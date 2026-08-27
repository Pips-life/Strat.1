package life.pips.strat1

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import kotlin.concurrent.thread

/**
 * Release gate for Pips-life.
 *
 * Every launch checks GitHub's latest published release. Releases use semantic
 * version tags such as v0.2.1. If a newer release exists, the user is offered
 * the official GitHub release APK. Android's Package Installer performs the
 * final installation/upgrade confirmation.
 *
 * This activity never contains backend or MetaApi credentials.
 */
class UpdateGateActivity : AppCompatActivity() {
    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/Pips-life/Strat.1/releases/latest"
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
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val tag = jsonString(body, "tag_name") ?: return@thread openApp()
                val releaseUrl = jsonString(body, "html_url") ?: "https://github.com/Pips-life/Strat.1/releases/latest"
                val apkUrl = findApkAssetUrl(body) ?: releaseUrl
                val remoteVersion = tag.removePrefix("v").trim()

                if (isNewer(remoteVersion, BuildConfig.VERSION_NAME)) {
                    runOnUiThread { showUpdateDialog(remoteVersion, apkUrl) }
                } else {
                    openApp()
                }
            } catch (_: Exception) {
                // Updates must never block access to the trading UI when GitHub is unavailable.
                openApp()
            }
        }
    }

    private fun showUpdateDialog(version: String, apkUrl: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Pips-life update available")
            .setMessage("Version $version is available from the official GitHub release. Download it to update Pips-life.")
            .setCancelable(false)
            .setNegativeButton("Later") { _, _ -> openApp() }
            .setPositiveButton("Download & Install") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)))
                } catch (_: Exception) {
                    Toast.makeText(this, "Unable to open the GitHub release.", Toast.LENGTH_LONG).show()
                }
                finish()
            }
            .show()
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

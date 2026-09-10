package life.pips.strat1

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Checks GitHub Releases on every app launch and installs a newer signed APK with user confirmation. */
class UpdateGateActivity : android.app.Activity() {
    private val http = OkHttpClient()
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkForUpdate()
    }

    private fun checkForUpdate() {
        executor.execute {
            runCatching {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/Pips-life/Strat.1/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("GitHub release check failed: ${response.code}")
                    val root = JSONObject(response.body?.string().orEmpty())
                    val tag = root.optString("tag_name").removePrefix("v")
                    val latestCode = tag.substringAfterLast('.').toIntOrNull() ?: return@use
                    if (latestCode <= BuildConfig.VERSION_CODE) return@use
                    val assets = root.optJSONArray("assets") ?: return@use
                    var apkUrl = ""
                    var apkName = ""
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val name = a.optString("name")
                        if (name.endsWith(".apk", true)) { apkName = name; apkUrl = a.optString("browser_download_url"); break }
                    }
                    if (apkUrl.isBlank()) return@use
                    runOnUiThread { promptUpdate(tag, apkUrl, apkName) }
                }
            }
        }
    }

    private fun promptUpdate(version: String, url: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Pips-life update available")
            .setMessage("Version $version is available. Install the latest signed release now?")
            .setPositiveButton("UPDATE") { _, _ -> downloadAndInstall(url, name) }
            .setNegativeButton("LATER") { _, _ -> openApp() }
            .setCancelable(false)
            .show()
    }

    private fun downloadAndInstall(url: String, name: String) {
        executor.execute {
            runCatching {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
                val file = File(dir, if (name.endsWith(".apk", true)) name else "pips-life-update.apk")
                val request = Request.Builder().url(url).get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Update download failed: ${response.code}")
                    response.body?.byteStream()?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                }
                runOnUiThread { install(file) }
            }.onFailure { runOnUiThread { showError(it.message ?: "Update failed") } }
        }
    }

    private fun install(file: File) {
        val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this).setTitle("Update failed").setMessage(message).setPositiveButton("Continue") { _, _ -> openApp() }.show()
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java)); finish()
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}

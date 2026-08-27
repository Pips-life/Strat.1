package life.pips.strat1.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Release manifest expected from the Strat.1 release service. */
data class AppRelease(val versionCode: Long, val versionName: String, val apkUrl: String, val notes: String)

class UpdateManager(private val context: Context) {
    suspend fun check(manifestUrl: String): AppRelease? = withContext(Dispatchers.IO) {
        val connection = URL(manifestUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.inputStream.bufferedReader().use { reader ->
            val json = JSONObject(reader.readText())
            val release = AppRelease(
                json.getLong("versionCode"),
                json.getString("versionName"),
                json.getString("apkUrl"),
                json.optString("notes", "")
            )
            if (release.versionCode > currentVersionCode()) release else null
        }
    }

    suspend fun downloadAndPrompt(release: AppRelease): Unit = withContext(Dispatchers.IO) {
        val apk = File(context.cacheDir, "strat1-${release.versionCode}.apk")
        URL(release.apkUrl).openStream().use { input -> apk.outputStream().use { output -> input.copyTo(output) } }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun currentVersionCode(): Long = context.packageManager
        .getPackageInfo(context.packageName, 0).longVersionCode
}

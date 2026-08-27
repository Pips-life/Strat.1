package life.pips.strat1

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val RELEASES_API = "https://api.github.com/repos/Pips-life/Strat.1/releases/latest"
private const val APK_MIME = "application/vnd.android.package-archive"

private data class ReleaseInfo(val tag: String, val name: String, val notes: String, val apkUrl: String)

class ReleaseUpdateActivity : ComponentActivity() {
    private var pendingDownloadId: Long = -1L
    private var receiverRegistered = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == pendingDownloadId) installDownloadedApk(id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { UpdateScreen() }
        registerDownloadReceiver()
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(downloadReceiver)
        super.onDestroy()
    }

    @Composable
    private fun UpdateScreen() {
        val scope = rememberCoroutineScope()
        var release by remember { mutableStateOf<ReleaseInfo?>(null) }
        var checking by remember { mutableStateOf(true) }
        var message by remember { mutableStateOf("Checking GitHub for a new Pips-life release…") }
        var downloading by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            val result = withContext(Dispatchers.IO) { fetchLatestRelease() }
            checking = false
            result.onSuccess { found ->
                if (found == null) {
                    openMain()
                } else {
                    release = found
                    message = "A newer Pips-life release is available."
                }
            }.onFailure {
                message = "Update check unavailable. Starting Pips-life normally."
                openMain()
            }
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF28B7D9), background = Color(0xFF071018), surface = Color(0xFF0D1822))) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF071018)) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    if (checking) {
                        CircularProgressIndicator()
                    } else if (release != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("PIPS-LIFE UPDATE", color = Color.White, fontSize = 24.sp)
                            Text("Installed: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = Color.LightGray)
                            Text("Available: ${release!!.name} • ${release!!.tag}", color = Color(0xFF35D07F), fontSize = 18.sp)
                            if (release!!.notes.isNotBlank()) Text(release!!.notes.take(500), color = Color.LightGray, fontSize = 12.sp)
                            Text(message, color = Color.LightGray, fontSize = 12.sp)
                            Button(enabled = !downloading, onClick = {
                                downloading = true
                                message = "Downloading ${release!!.tag} from GitHub…"
                                scope.launch { pendingDownloadId = enqueueApk(release!!.apkUrl, release!!.tag) }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (downloading) "DOWNLOADING…" else "DOWNLOAD & INSTALL")
                            }
                            TextButton(onClick = { openMain() }) { Text("Later") }
                        }
                    } else {
                        Text(message, color = Color.LightGray)
                    }
                }
            }
        }
    }

    private suspend fun enqueueApk(url: String, tag: String): Long = withContext(Dispatchers.IO) {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Pips-life $tag")
            .setDescription("Pips-life update from GitHub Releases")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this@ReleaseUpdateActivity, Environment.DIRECTORY_DOWNLOADS, "pips-life-$tag.apk")
        (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }

    private fun installDownloadedApk(id: Long) {
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val uri = manager.getUriForDownloadedFile(id) ?: return
        if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            type = APK_MIME
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, packageName)
        }
        startActivity(intent)
    }

    private fun registerDownloadReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(this, downloadReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun fetchLatestRelease(): Result<ReleaseInfo?> = runCatching {
        val request = Request.Builder().url(RELEASES_API).header("Accept", "application/vnd.github+json").build()
        OkHttpClient().newCall(request).execute().use { response ->
            if (response.code == 404) return@runCatching null
            if (!response.isSuccessful) error("GitHub release check failed: HTTP ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            val tag = json.optString("tag_name")
            val name = json.optString("name", tag)
            val notes = json.optString("body", "")
            if (tag.isBlank()) error("GitHub release has no tag")
            val availableCode = releaseCode(tag)
            if (availableCode <= BuildConfig.VERSION_CODE) return@runCatching null
            val assets = json.optJSONArray("assets") ?: error("Release has no assets")
            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            if (apkUrl.isBlank()) error("Release has no APK asset")
            ReleaseInfo(tag, name, notes, apkUrl)
        }
    }

    private fun releaseCode(tag: String): Int {
        val match = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$").find(tag) ?: return 0
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        val patch = match.groupValues[3].toInt()
        return major * 1_000_000 + minor * 1_000 + patch
    }
}

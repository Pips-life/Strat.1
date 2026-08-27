package life.pips.strat1

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Visible update status for the compact Updates area.
 *
 * The checker is deliberately standalone: it does not own navigation, MT5 state,
 * or installation. It reports one of four states so the host screen can remain small.
 */
enum class UpdaterStatus {
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    CHECK_FAILED
}

data class UpdaterState(
    val status: UpdaterStatus = UpdaterStatus.CHECKING,
    val installedVersion: String,
    val latestVersion: String? = null
)

object UpdaterStatusChecker {
    private const val RELEASES_URL = "https://api.github.com/repos/Pips-life/Strat.1/releases/latest"

    suspend fun check(context: Context): UpdaterState = withContext(Dispatchers.IO) {
        val installed = installedVersion(context)
        try {
            val connection = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Pips-life-Android")
            }
            connection.use {
                if (it.responseCode !in 200..299) error("HTTP ${it.responseCode}")
                val body = it.inputStream.bufferedReader().use { reader -> reader.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name").removePrefix("v").trim()
                if (tag.isBlank()) error("Missing release version")
                val newer = compareVersions(tag, installed) > 0
                UpdaterState(
                    status = if (newer) UpdaterStatus.UPDATE_AVAILABLE else UpdaterStatus.UP_TO_DATE,
                    installedVersion = installed,
                    latestVersion = tag
                )
            }
        } catch (_: Exception) {
            UpdaterState(UpdaterStatus.CHECK_FAILED, installed)
        }
    }

    private fun installedVersion(context: Context): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    } catch (_: Exception) {
        "0.0.0"
    }

    private fun compareVersions(a: String, b: String): Int {
        val aa = a.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val bb = b.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(aa.size, bb.size)) {
            val av = aa.getOrElse(i) { 0 }
            val bv = bb.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }
}

@Composable
fun UpdaterStatusRow(
    context: Context,
    modifier: Modifier = Modifier,
    onUpdateAvailable: (() -> Unit)? = null
) {
    var state by remember {
        mutableStateOf(
            UpdaterState(
                status = UpdaterStatus.CHECKING,
                installedVersion = "—"
            )
        )
    }

    suspend fun runCheck() {
        state = state.copy(status = UpdaterStatus.CHECKING)
        state = UpdaterStatusChecker.check(context)
    }

    LaunchedEffect(Unit) { runCheck() }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            when (state.status) {
                UpdaterStatus.CHECKING -> {
                    Text("Checking for updates…", style = MaterialTheme.typography.labelMedium)
                    Text("Current: v${state.installedVersion}", style = MaterialTheme.typography.labelSmall)
                }
                UpdaterStatus.UP_TO_DATE -> {
                    Text("You’re up to date", style = MaterialTheme.typography.labelMedium)
                    Text("v${state.installedVersion}", style = MaterialTheme.typography.labelSmall)
                }
                UpdaterStatus.UPDATE_AVAILABLE -> {
                    Text("Update available · v${state.latestVersion}", style = MaterialTheme.typography.labelMedium)
                    Text("Installed: v${state.installedVersion}", style = MaterialTheme.typography.labelSmall)
                }
                UpdaterStatus.CHECK_FAILED -> {
                    Text("Couldn’t check for updates", style = MaterialTheme.typography.labelMedium)
                    Text("Installed: v${state.installedVersion}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        when (state.status) {
            UpdaterStatus.UPDATE_AVAILABLE -> IconButton(onClick = { onUpdateAvailable?.invoke() }) {
                Icon(Icons.Default.Download, contentDescription = "Update")
            }
            UpdaterStatus.CHECK_FAILED -> IconButton(onClick = { state = state.copy(status = UpdaterStatus.CHECKING) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Retry update check")
            }
            UpdaterStatus.UP_TO_DATE -> Icon(Icons.Default.CheckCircle, contentDescription = "Up to date")
            UpdaterStatus.CHECKING -> Icon(Icons.Default.CloudOff, contentDescription = "Checking for updates")
        }
    }
}

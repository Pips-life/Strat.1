package life.pips.strat1

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun AppUpdateCard() {
    val context = LocalContext.current
    val manager = remember { ReleaseUpdateManager(context) }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(true) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var message by remember { mutableStateOf("Checking for updates…") }

    suspend fun runCheck() {
        checking = true
        message = "Checking for updates…"
        manager.check().onSuccess { found ->
            release = found
            message = if (found == null) "App is up to date" else "Update available · v${found.versionName}"
        }.onFailure { error ->
            release = null
            message = error.message?.ifBlank { null } ?: "Update check unavailable"
        }
        checking = false
    }

    LaunchedEffect(Unit) { runCheck() }

    fun startDownload(found: AppRelease) {
        if (downloading) return
        downloading = true
        progress = 0
        message = "Downloading v${found.versionName}…"
        scope.launch {
            manager.downloadAndInstall(found) { progress = it }
                .onSuccess { downloading = false; message = "Update ready — opening installer" }
                .onFailure { downloading = false; message = it.message ?: "Update download failed" }
        }
    }

    Surface(
        color = Color(0xFF0A1624),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF25D9FF).copy(alpha = 0.24f), RoundedCornerShape(10.dp))
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("APP UPDATE", color = Color(0xFF25D9FF), fontSize = 8.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("v${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}", color = Color(0xFF7F95AE), fontSize = 8.sp)
                }
                Text(
                    if (downloading) "Downloading $progress%" else message,
                    color = when {
                        release != null -> Color(0xFF25D9FF)
                        message == "App is up to date" -> Color(0xFF43F28E)
                        checking -> Color(0xFF91A9C4)
                        else -> Color(0xFFFFC857)
                    },
                    fontSize = 9.sp
                )
                if (downloading) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(2.dp))
            }
            Spacer(Modifier.width(8.dp))
            when {
                release != null -> Button(enabled = !downloading, onClick = { release?.let(::startDownload) }, contentPadding = PaddingValues(horizontal = 11.dp, vertical = 4.dp), modifier = Modifier.height(34.dp)) { Text(if (downloading) "$progress%" else "DOWNLOAD UPDATE", fontSize = 8.sp) }
                checking -> TextButton(enabled = false, onClick = {}, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) { Text("CHECKING", fontSize = 8.sp) }
                else -> TextButton(onClick = { scope.launch { runCheck() } }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) { Text("CHECK", fontSize = 8.sp) }
            }
        }
    }
}

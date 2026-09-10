package life.pips.strat1

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@Composable
fun AppUpdateCard() {
    val context = LocalContext.current
    val manager = remember { ReleaseUpdateManager(context) }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var message by remember { mutableStateOf("Tap CHECK FOR UPDATE") }

    suspend fun runCheck() {
        checking = true
        message = "Checking for update…"
        manager.check()
            .onSuccess { found ->
                release = found
                message = if (found == null) {
                    "App is up to date"
                } else {
                    "Download update · v${found.versionName}"
                }
            }
            .onFailure {
                release = null
                message = "Update check failed"
            }
        checking = false
    }

    fun startDownload(found: AppRelease) {
        if (downloading) return
        downloading = true
        progress = 0
        message = "Downloading update…"
        scope.launch {
            manager.downloadAndInstall(found) { value -> progress = value }
                .onSuccess {
                    downloading = false
                    message = "Update downloaded — opening installer…"
                }
                .onFailure {
                    downloading = false
                    message = "Update download failed"
                }
        }
    }

    LaunchedEffect(Unit) { runCheck() }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF25D9FF).copy(alpha = 0.45f), RoundedCornerShape(14.dp))
    ) {
        Column(
            Modifier
                .background(
                    Brush.linearGradient(listOf(Color(0xFF10243D), Color(0xFF17122F))),
                    RoundedCornerShape(14.dp)
                )
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("APP UPDATES", color = Color(0xFF25D9FF), fontSize = 9.sp, letterSpacing = 1.sp)
                Spacer(Modifier.width(7.dp))
                Text("v${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}", color = Color(0xFF8EA2BB), fontSize = 9.sp)
            }

            Text(
                if (downloading) "Downloading… $progress%" else message,
                color = when {
                    downloading -> Color(0xFF25D9FF)
                    message == "App is up to date" -> Color(0xFF39F28A)
                    message.startsWith("Download update") -> Color(0xFF25D9FF)
                    message.contains("failed", true) -> Color(0xFFFFC857)
                    else -> Color(0xFF8EA2BB)
                },
                fontSize = 10.sp
            )

            Button(
                enabled = !checking && !downloading && release != null,
                onClick = { release?.let(::startDownload) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 3.dp)
            ) {
                Text(
                    when {
                        checking -> "CHECKING…"
                        downloading -> "DOWNLOADING…"
                        release != null -> "DOWNLOAD UPDATE"
                        else -> "APP IS UP TO DATE"
                    },
                    fontSize = 10.sp
                )
            }

            if (!downloading) {
                TextButton(
                    enabled = !checking,
                    onClick = { scope.launch { runCheck() } },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text(if (checking) "CHECKING…" else "CHECK AGAIN", fontSize = 9.sp)
                }
            }

            if (downloading) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            }
        }
    }
}

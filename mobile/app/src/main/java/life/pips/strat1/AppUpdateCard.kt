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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var message by remember { mutableStateOf("Checking releases…") }
    var promptedVersion by remember { mutableStateOf<Int?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    suspend fun runCheck() {
        checking = true
        message = "Checking releases…"
        manager.check().onSuccess { found ->
            release = found
            message = if (found == null) "You’re up to date — v${BuildConfig.VERSION_NAME}" else "Update available — v${found.versionName} (build ${found.versionCode})"
        }.onFailure { error ->
            release = null
            message = "Release check failed — ${error.message ?: "network error"}"
        }
        checking = false
    }

    fun startDownload(found: AppRelease) {
        if (downloading) return
        showDialog = false
        downloading = true
        progress = 0
        message = "Downloading v${found.versionName}…"
        scope.launch {
            manager.downloadAndInstall(found) { value -> progress = value }
                .onSuccess {
                    downloading = false
                    message = "Download complete — opening installer…"
                }
                .onFailure { error ->
                    downloading = false
                    message = "Update download failed — ${error.message ?: "network error"}"
                }
        }
    }

    LaunchedEffect(Unit) { runCheck() }

    LaunchedEffect(release?.versionCode) {
        val found = release ?: return@LaunchedEffect
        if (promptedVersion != found.versionCode && !downloading) {
            promptedVersion = found.versionCode
            showDialog = true
        }
    }

    if (showDialog) {
        val found = release
        if (found != null) AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Pips-life update available") },
            text = { Text("Pips-life ${found.versionName} (build ${found.versionCode}) is ready. Download it now, then Android will open the installer.") },
            confirmButton = {
                Button(
                    onClick = { startDownload(found) },
                    modifier = Modifier.height(36.dp).wrapContentWidth(),
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(9.dp)
                ) { Text("DOWNLOAD", fontSize = 10.sp) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text("LATER", fontSize = 10.sp)
                }
            }
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF25D9FF).copy(alpha = 0.45f), RoundedCornerShape(14.dp))
    ) {
        Column(
            Modifier.background(Brush.linearGradient(listOf(Color(0xFF10243D), Color(0xFF17122F))), RoundedCornerShape(14.dp)).padding(8.dp),
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
                    message.startsWith("You’re up to date") -> Color(0xFF39F28A)
                    message.startsWith("Update available") -> Color(0xFF25D9FF)
                    message.startsWith("Release check failed") -> Color(0xFFFFC857)
                    else -> Color(0xFF8EA2BB)
                },
                fontSize = 10.sp
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !checking && !downloading,
                    onClick = { scope.launch { runCheck() } },
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)
                ) { Text(if (checking) "CHECKING…" else "CHECK", fontSize = 10.sp) }
                release?.let { found ->
                    TextButton(
                        enabled = !downloading,
                        onClick = { startDownload(found) },
                        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)
                    ) { Text("DOWNLOAD", color = Color(0xFF39F28A), fontSize = 10.sp) }
                    Text("v${found.versionName}", color = Color(0xFF8EA2BB), fontSize = 9.sp)
                }
            }
            if (downloading) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

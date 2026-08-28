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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun AppUpdateCard() {
    val context = LocalContext.current
    val manager = remember { ReleaseUpdateManager(context) }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var message by remember { mutableStateOf("Checking GitHub releases…") }
    var promptShownFor by remember { mutableStateOf<Int?>(null) }

    suspend fun runCheck() {
        checking = true
        message = "Checking GitHub releases…"
        release = null
        manager.check().onSuccess { found ->
            release = found
            message = if (found == null) "You’re up to date — v${BuildConfig.VERSION_NAME}" else "Update available — v${found.versionName} (build ${found.versionCode})"
        }.onFailure { error ->
            release = null
            message = "Release check failed — ${error.message ?: "network error"}"
        }
        checking = false
    }

    LaunchedEffect(Unit) { runCheck() }

    LaunchedEffect(release?.versionCode) {
        val found = release ?: return@LaunchedEffect
        if (promptShownFor == found.versionCode) return@LaunchedEffect
        promptShownFor = found.versionCode
        AlertDialog.Builder(context)
    }

    release?.let { found ->
        if (promptShownFor == found.versionCode) {
            // The actual dialog is rendered below so it is lifecycle-safe in Compose.
        }
    }

    var showDialog by remember { mutableStateOf(false) }
    LaunchedEffect(release?.versionCode) { if (release != null) showDialog = true }
    if (showDialog) {
        val found = release
        if (found != null) AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Pips-life update available") },
            text = { Text("Pips-life ${found.versionName} (build ${found.versionCode}) is available. Download and install it now?") },
            confirmButton = { TextButton(onClick = { showDialog = false; manager.downloadAndInstall(found) }) { Text("DOWNLOAD & INSTALL") } },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("LATER") } }
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF25D9FF).copy(alpha = 0.45f), RoundedCornerShape(16.dp))
    ) {
        Column(Modifier.background(Brush.linearGradient(listOf(Color(0xFF10243D), Color(0xFF17122F))), RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("APP UPDATES", color = Color(0xFF25D9FF), fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("v${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}", color = Color(0xFF8EA2BB), fontSize = 9.sp)
                }
                Text("GITHUB", color = Color(0xFF8EA2BB), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
            Text(message, color = when {
                message.startsWith("You’re up to date") -> Color(0xFF39F28A)
                message.startsWith("Update available") -> Color(0xFF25D9FF)
                message.startsWith("Release check failed") -> Color(0xFFFFC857)
                else -> Color(0xFF8EA2BB)
            }, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !checking, onClick = { scope.launch { runCheck() } }, modifier = Modifier.weight(1f).height(36.dp), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D9FF), contentColor = Color(0xFF040712))) { Text(if (checking) "CHECKING…" else "CHECK FOR UPDATES", fontSize = 9.sp, fontWeight = FontWeight.Black) }
                release?.let { found -> OutlinedButton(onClick = { manager.downloadAndInstall(found) }, modifier = Modifier.weight(1f).height(36.dp), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF39F28A))) { Text("INSTALL ${found.versionName}", fontSize = 9.sp, fontWeight = FontWeight.Black) } }
            }
        }
    }
}

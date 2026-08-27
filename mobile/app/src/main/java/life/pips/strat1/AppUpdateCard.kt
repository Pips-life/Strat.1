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
    var message by remember { mutableStateOf("Keep Pips-life current with numbered GitHub releases.") }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220)),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF25D9FF).copy(alpha = 0.55f), RoundedCornerShape(24.dp))
    ) {
        Column(
            Modifier.background(Brush.linearGradient(listOf(Color(0xFF10243D), Color(0xFF17122F))), RoundedCornerShape(24.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("APP UPDATES", color = Color(0xFF25D9FF), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                    Text("Pips-life releases", color = Color(0xFFF6FAFF), fontSize = 18.sp, fontWeight = FontWeight.Black)
                }
                Text("v${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}", color = Color(0xFF8EA2BB), fontSize = 10.sp)
            }
            Text(
                release?.let { "New release ${it.versionName} (build ${it.versionCode}) is ready." } ?: message,
                color = Color(0xFF8EA2BB), fontSize = 11.sp
            )
            Button(
                enabled = !checking,
                onClick = {
                    checking = true
                    message = "Checking the official release feed…"
                    scope.launch {
                        manager.check().onSuccess { found ->
                            release = found
                            message = if (found == null) "You're up to date." else ""
                        }.onFailure {
                            release = null
                            message = "Release check failed. Try again when the backend is reachable."
                        }
                        checking = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D9FF), contentColor = Color(0xFF040712))
            ) { Text(if (checking) "CHECKING…" else "CHECK FOR UPDATES", fontWeight = FontWeight.Black) }

            release?.let { found ->
                OutlinedButton(
                    onClick = { manager.downloadAndInstall(found) },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF39F28A))
                ) { Text("DOWNLOAD & INSTALL ${found.versionName}", fontWeight = FontWeight.Black) }
            }
        }
    }
}

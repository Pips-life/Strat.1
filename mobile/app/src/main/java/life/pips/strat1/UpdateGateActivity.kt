package life.pips.strat1

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Automatic release checker. Updates come directly from official GitHub releases. */
class UpdateGateActivity : android.app.Activity() {
    private val updates = ReleaseUpdateManager(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CoroutineScope(Dispatchers.Main).launch {
            updates.check().onSuccess { release ->
                if (release != null) {
                    AlertDialog.Builder(this@UpdateGateActivity)
                        .setTitle("Update available")
                        .setMessage("Pips-life ${release.versionName} is available. Install it now?")
                        .setPositiveButton("UPDATE") { _, _ ->
                            CoroutineScope(Dispatchers.Main).launch { updates.downloadAndInstall(release) }
                        }
                        .setNegativeButton("LATER") { _, _ -> openApp() }
                        .setCancelable(false)
                        .show()
                } else openApp()
            }.onFailure { openApp() }
        }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

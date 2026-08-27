package life.pips.strat1

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Checks GitHub Releases when the app opens and gives the user control of installation. */
class UpdatePromptActivity : Activity() {
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check()
    }

    private fun check() {
        scope.launch {
            val release = ReleaseManager.checkForUpdate().getOrNull()
            if (release == null) { finish(); return@launch }
            AlertDialog.Builder(this@UpdatePromptActivity)
                .setTitle("Pips-life update available")
                .setMessage("Version ${release.version} is available on GitHub Releases.\n\n${release.notes.take(600)}")
                .setNegativeButton("Later") { _, _ -> finish() }
                .setPositiveButton("Download & Install") { _, _ -> download(release) }
                .setCancelable(false)
                .show()
        }
    }

    private fun download(release: ReleaseManager.ReleaseInfo) {
        Toast.makeText(this, "Downloading Pips-life ${release.version}…", Toast.LENGTH_LONG).show()
        scope.launch {
            val result = ReleaseManager.downloadAndInstall(this@UpdatePromptActivity, release)
            if (result.isFailure) Toast.makeText(this@UpdatePromptActivity, result.exceptionOrNull()?.message ?: "Update failed", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() { job.cancel(); super.onDestroy() }
}

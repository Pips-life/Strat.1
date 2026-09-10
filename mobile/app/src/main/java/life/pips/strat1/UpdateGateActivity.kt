package life.pips.strat1

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Opens the latest GitHub release directly; no Render/Vercel/backend dependency. */
class UpdateGateActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialog.Builder(this)
            .setTitle("Pips-life")
            .setMessage("Updates are distributed through the official GitHub releases.")
            .setPositiveButton("Open Releases") { _, _ -> openReleases() }
            .setNegativeButton("Continue") { _, _ -> openApp() }
            .setCancelable(false)
            .show()
    }

    private fun openReleases() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Pips-life/Strat.1/releases/latest")))
        openApp()
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

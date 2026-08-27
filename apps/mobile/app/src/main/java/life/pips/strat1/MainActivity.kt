package life.pips.strat1

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "Strategy 001"
            textSize = 26f
            setPadding(24, 32, 24, 16)
        }
        val status = TextView(this).apply {
            text = "Backend: not connected"
            setPadding(24, 8, 24, 24)
        }
        val replay = Button(this).apply { text = "REPLAY (development)" }
        val demo = Button(this).apply { text = "DEMO" }
        val live = Button(this).apply { text = "LIVE" }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(status)
            addView(replay)
            addView(demo)
            addView(live)
        }
        setContentView(layout)
    }
}

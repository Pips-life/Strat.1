package life.pips.strat1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Strat1App() }
    }
}

@Composable
private fun Strat1App() {
    MaterialTheme {
        Text("Strat.1 — Mobile Foundation")
    }
}

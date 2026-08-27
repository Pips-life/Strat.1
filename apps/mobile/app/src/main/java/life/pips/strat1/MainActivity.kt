package life.pips.strat1

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import org.json.JSONObject

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    private fun showHome() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 24) }
        val title = TextView(this).apply { text = "Strategy 001"; textSize = 26f }
        val status = TextView(this).apply { text = "Backend: ready for MT5 discovery" }
        val mt5 = Button(this).apply { text = "MT5"; setOnClickListener { showMt5() } }
        root.addView(title); root.addView(status); root.addView(mt5)
        setContentView(root)
    }

    private fun showMt5() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val title = TextView(this).apply { text = "MT5"; textSize = 26f }
        val broker = EditText(this).apply { hint = "MT5 broker name"; singleLine = true }
        val search = Button(this).apply { text = "Find broker servers" }
        val status = TextView(this).apply { text = "Enter at least 2 characters." }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val back = Button(this).apply { text = "Back"; setOnClickListener { showHome() } }

        search.setOnClickListener {
            val query = broker.text.toString().trim()
            if (query.length < 2) { status.text = "Enter at least 2 characters."; return@setOnClickListener }
            search.isEnabled = false
            status.text = "Searching MetaApi known MT5 servers…"
            results.removeAllViews()
            executor.execute {
                val result = discoverServers(query)
                runOnUiThread {
                    search.isEnabled = true
                    if (result.error != null) { status.text = result.error; return@runOnUiThread }
                    status.text = "Select the server matching your MT5 account."
                    result.brokers.forEach { (name, servers) ->
                        val heading = TextView(this).apply { text = name; textSize = 18f; setPadding(0, 20, 0, 8) }
                        results.addView(heading)
                        servers.forEach { server ->
                            val button = Button(this).apply {
                                text = server
                                gravity = Gravity.START
                                setOnClickListener { status.text = "Selected: $server" }
                            }
                            results.addView(button)
                        }
                    }
                    if (result.brokers.isEmpty()) status.text = "No known MT5 servers matched. A manual server can be supported later."
                }
            }
        }

        root.addView(title); root.addView(broker); root.addView(search); root.addView(status); root.addView(results); root.addView(back)
        setContentView(root)
    }

    private data class DiscoveryResult(val brokers: List<Pair<String, List<String>>>, val error: String? = null)

    private fun discoverServers(query: String): DiscoveryResult = try {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val connection = (URL("${BuildConfig.BACKEND_BASE_URL}/api/mt5/servers?query=$encoded").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 10_000; readTimeout = 15_000
        }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        val json = JSONObject(body)
        if (connection.responseCode !in 200..299) return DiscoveryResult(emptyList(), json.optString("error", "Server discovery failed"))
        val brokersJson = json.optJSONArray("brokers") ?: return DiscoveryResult(emptyList())
        val brokers = buildList {
            for (i in 0 until brokersJson.length()) {
                val broker = brokersJson.getJSONObject(i)
                val serversJson = broker.optJSONArray("servers") ?: continue
                val servers = buildList { for (j in 0 until serversJson.length()) add(serversJson.getString(j)) }
                add(broker.optString("broker") to servers)
            }
        }
        DiscoveryResult(brokers)
    } catch (e: Exception) {
        DiscoveryResult(emptyList(), "Could not reach the Strat.1 backend: ${e.message ?: "network error"}")
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}

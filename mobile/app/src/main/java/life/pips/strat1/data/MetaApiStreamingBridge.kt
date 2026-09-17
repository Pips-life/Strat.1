package life.pips.strat1.data

import cloud.metaapi.sdk.clients.meta_api.SynchronizationListener
import cloud.metaapi.sdk.clients.meta_api.models.MarketDataSubscription
import cloud.metaapi.sdk.clients.meta_api.models.MetatraderSymbolPrice
import cloud.metaapi.sdk.meta_api.MetaApi
import cloud.metaapi.sdk.meta_api.MetaApiConnection
import cloud.metaapi.sdk.meta_api.MetatraderAccount
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Direct MetaApi bridge with bounded blocking isolated to a worker and automatic reconnect. */
class MetaApiStreamingBridge {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private var api: MetaApi? = null
    private var connection: MetaApiConnection? = null
    private var listener: SynchronizationListener? = null

    fun start(token: String, accountId: String, symbols: List<String>, onTick: (String, TickPrice) -> Unit, onStatus: (String) -> Unit) {
        stop()
        if (token.isBlank() || accountId.isBlank()) { onStatus("STREAM OFF | MetaApi credentials missing"); return }
        val requested = symbols.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(20)
        if (requested.isEmpty()) { onStatus("STREAM OFF | watchlist empty"); return }
        val runId = generation.incrementAndGet()
        running.set(true)
        executor.execute {
            var attempt = 0
            while (running.get() && generation.get() == runId) {
                try {
                    onStatus(if (attempt == 0) "STREAM CONNECTING | MetaApi websocket" else "STREAM RETRYING | attempt=$attempt")
                    val metaApi = MetaApi(token)
                    api = metaApi
                    val account: MetatraderAccount = metaApi.getMetatraderAccountApi().getAccount(accountId).join()
                    if (!running.get() || generation.get() != runId) return@execute
                    onStatus("STREAM WAITING | broker connection")
                    account.waitConnected().join()
                    if (!running.get() || generation.get() != runId) return@execute
                    val metaConnection = account.connect().join()
                    connection = metaConnection
                    val quoteListener = object : SynchronizationListener() {
                        override fun onSymbolPriceUpdated(instanceIndex: String, price: MetatraderSymbolPrice): CompletableFuture<Void> {
                            if (running.get() && requested.contains(price.symbol)) {
                                val bid = price.bid; val ask = price.ask
                                if (bid.isFinite() && ask.isFinite()) onTick(price.symbol, TickPrice(bid, ask, System.currentTimeMillis()))
                            }
                            return CompletableFuture.completedFuture(null)
                        }
                    }
                    listener = quoteListener
                    metaConnection.addSynchronizationListener(quoteListener)
                    onStatus("STREAM SYNCING | terminal state")
                    metaConnection.waitSynchronized().join()
                    if (!running.get() || generation.get() != runId) return@execute
                    requested.forEach { symbol ->
                        metaConnection.subscribeToMarketData(symbol, listOf(MarketDataSubscription().apply { type = "ticks" })).join()
                    }
                    attempt = 0
                    onStatus("STREAM LIVE | ${requested.joinToString(", ")}")
                    return@execute
                } catch (t: Throwable) {
                    if (!running.get() || generation.get() != runId) return@execute
                    val cause = t.cause ?: t
                    onStatus("STREAM ERROR | ${cause.message ?: cause.javaClass.simpleName}")
                    attempt += 1
                    val backoffMs = (1000L shl (attempt.coerceAtMost(5) - 1)).coerceAtMost(30000L)
                    try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return@execute }
                }
            }
        }
    }

    fun stop() {
        generation.incrementAndGet()
        running.set(false)
        try { listener?.let { connection?.removeSynchronizationListener(it) } } catch (_: Throwable) { }
        listener = null
        connection = null
        api = null
    }

    fun close() { stop(); executor.shutdownNow() }
}

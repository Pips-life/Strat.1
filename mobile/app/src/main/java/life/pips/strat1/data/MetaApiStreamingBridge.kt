package life.pips.strat1.data

import cloud.metaapi.sdk.clients.meta_api.SynchronizationListener
import cloud.metaapi.sdk.clients.meta_api.models.MetatraderSymbolPrice
import cloud.metaapi.sdk.clients.meta_api.models.MarketDataSubscription
import cloud.metaapi.sdk.meta_api.MetaApi
import cloud.metaapi.sdk.meta_api.MetaApiConnection
import cloud.metaapi.sdk.meta_api.MetatraderAccount
import io.vertx.core.Future
import io.vertx.core.Vertx
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct MetaApi real-time streaming connection. The SDK owns the websocket,
 * synchronization and reconnect lifecycle; the app receives broker prices
 * through the listener and can feed them directly into TradingEngine.
 */
class MetaApiStreamingBridge {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var vertx: Vertx? = null
    private var api: MetaApi? = null
    private var connection: MetaApiConnection? = null
    private var listener: SynchronizationListener? = null

    fun start(
        token: String,
        accountId: String,
        symbols: List<String>,
        onTick: (String, TickPrice) -> Unit,
        onStatus: (String) -> Unit
    ) {
        stop()
        if (token.isBlank() || accountId.isBlank()) {
            onStatus("STREAM OFF | MetaApi credentials missing")
            return
        }
        val requested = symbols.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(20)
        if (requested.isEmpty()) return
        running.set(true)
        executor.execute {
            try {
                onStatus("STREAM CONNECTING | MetaApi websocket")
                val v = Vertx.vertx()
                vertx = v
                val a = MetaApi(token, v)
                api = a
                val account: MetatraderAccount = a.getMetatraderAccountApi().getAccount(accountId)
                    .toCompletionStage().toCompletableFuture().get()
                if (!running.get()) return@execute

                if (account.getConnectionStatus().name != "CONNECTED") {
                    onStatus("STREAM WAITING | broker connection")
                    account.waitConnected().toCompletionStage().toCompletableFuture().get()
                }

                val c = account.connect().toCompletionStage().toCompletableFuture().get()
                connection = c

                val l = object : SynchronizationListener() {
                    override fun onSymbolPriceUpdated(instanceIndex: String, price: MetatraderSymbolPrice): Future<Void> {
                        if (!running.get()) return Future.succeededFuture()
                        if (price.symbol in requested && price.bid.isFinite() && price.ask.isFinite()) {
                            onTick(price.symbol, TickPrice(price.bid, price.ask, System.currentTimeMillis()))
                        }
                        return Future.succeededFuture()
                    }
                }
                listener = l
                c.addSynchronizationListener(l)

                onStatus("STREAM SYNCING | terminal state")
                c.waitSynchronized().toCompletionStage().toCompletableFuture().get()
                if (!running.get()) return@execute

                requested.forEach { symbol ->
                    c.subscribeToMarketData(symbol, listOf(MarketDataSubscription().apply { type = "ticks" }))
                        .toCompletionStage().toCompletableFuture().get()
                }
                onStatus("STREAM LIVE | ${requested.joinToString(", ")}")
            } catch (t: Throwable) {
                if (running.get()) onStatus("STREAM ERROR | ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            listener?.let { connection?.removeSynchronizationListener(it) }
        } catch (_: Throwable) {
        }
        connection = null
        api = null
        try { vertx?.close() } catch (_: Throwable) { }
        vertx = null
    }
}

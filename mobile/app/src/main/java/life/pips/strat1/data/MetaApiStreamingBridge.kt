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

/**
 * Direct MetaApi websocket/streaming bridge.
 *
 * The MetaApi Java SDK exposes its asynchronous operations as Java
 * CompletableFuture instances. Keep this bridge on a dedicated worker so
 * account connection/synchronization never blocks the Android main thread.
 */
class MetaApiStreamingBridge {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
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

        val requested = symbols
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)

        if (requested.isEmpty()) {
            onStatus("STREAM OFF | watchlist empty")
            return
        }

        running.set(true)
        executor.execute {
            try {
                onStatus("STREAM CONNECTING | MetaApi websocket")

                // SDK 14.x uses the default MetaApi runtime/Vert.x instance.
                // Passing Vertx directly is not a supported constructor in 14.0.9.
                val metaApi = MetaApi(token)
                api = metaApi

                val account: MetatraderAccount = metaApi
                    .getMetatraderAccountApi()
                    .getAccount(accountId)
                    .join()

                if (!running.get()) return@execute

                onStatus("STREAM WAITING | broker connection")
                account.waitConnected().join()
                if (!running.get()) return@execute

                val metaConnection = account.connect().join()
                connection = metaConnection

                val quoteListener = object : SynchronizationListener() {
                    override fun onSymbolPriceUpdated(
                        instanceIndex: String,
                        price: MetatraderSymbolPrice
                    ): CompletableFuture<Void> {
                        if (running.get() && requested.contains(price.symbol)) {
                            val bid = price.bid
                            val ask = price.ask
                            if (bid.isFinite() && ask.isFinite()) {
                                onTick(
                                    price.symbol,
                                    TickPrice(bid, ask, System.currentTimeMillis())
                                )
                            }
                        }
                        return CompletableFuture.completedFuture(null)
                    }
                }

                listener = quoteListener
                metaConnection.addSynchronizationListener(quoteListener)

                onStatus("STREAM SYNCING | terminal state")
                metaConnection.waitSynchronized().join()
                if (!running.get()) return@execute

                requested.forEach { symbol ->
                    metaConnection.subscribeToMarketData(
                        symbol,
                        listOf(MarketDataSubscription().apply { type = "ticks" })
                    ).join()
                }

                onStatus("STREAM LIVE | ${requested.joinToString(", ")}")
            } catch (t: Throwable) {
                if (running.get()) {
                    val cause = t.cause ?: t
                    onStatus("STREAM ERROR | ${cause.message ?: cause.javaClass.simpleName}")
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            listener?.let { connection?.removeSynchronizationListener(it) }
        } catch (_: Throwable) {
        }
        listener = null
        connection = null
        api = null
    }

    fun close() {
        stop()
        executor.shutdownNow()
    }
}
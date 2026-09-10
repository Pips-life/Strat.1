package life.pips.strat1.data

import cloud.metaapi.sdk.clients.meta_api.SynchronizationListener
import cloud.metaapi.sdk.clients.meta_api.models.MarketDataSubscription
import cloud.metaapi.sdk.clients.meta_api.models.MetatraderSymbolPrice
import cloud.metaapi.sdk.meta_api.MetaApi
import cloud.metaapi.sdk.meta_api.MetaApiConnection
import cloud.metaapi.sdk.meta_api.MetatraderAccount
import io.vertx.core.Future
import io.vertx.core.Vertx
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Direct MetaApi websocket/streaming bridge. */
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
        if (requested.isEmpty()) {
            onStatus("STREAM OFF | watchlist empty")
            return
        }

        running.set(true)
        executor.execute {
            try {
                onStatus("STREAM CONNECTING | MetaApi websocket")
                val v = Vertx.vertx()
                vertx = v
                val metaApi = MetaApi(token, v)
                api = metaApi

                val account: MetatraderAccount = metaApi.getMetatraderAccountApi()
                    .getAccount(accountId)
                    .toCompletionStage().toCompletableFuture().get()

                if (!running.get()) return@execute
                onStatus("STREAM WAITING | broker connection")
                account.waitConnected().toCompletionStage().toCompletableFuture().get()
                if (!running.get()) return@execute

                val metaConnection = account.connect()
                    .toCompletionStage().toCompletableFuture().get()
                connection = metaConnection

                val quoteListener = object : SynchronizationListener() {
                    override fun onSymbolPriceUpdated(
                        instanceIndex: String,
                        price: MetatraderSymbolPrice
                    ): Future<Void> {
                        if (running.get() && price.symbol in requested && price.bid.isFinite() && price.ask.isFinite()) {
                            onTick(
                                price.symbol,
                                TickPrice(price.bid, price.ask, System.currentTimeMillis())
                            )
                        }
                        return Future.succeededFuture()
                    }
                }
                listener = quoteListener
                metaConnection.addSynchronizationListener(quoteListener)

                onStatus("STREAM SYNCING | terminal state")
                metaConnection.waitSynchronized().toCompletionStage().toCompletableFuture().get()
                if (!running.get()) return@execute

                requested.forEach { symbol ->
                    metaConnection.subscribeToMarketData(
                        symbol,
                        listOf(MarketDataSubscription().apply { type = "ticks" })
                    ).toCompletionStage().toCompletableFuture().get()
                }
                onStatus("STREAM LIVE | ${requested.joinToString(", ")}")
            } catch (t: Throwable) {
                if (running.get()) onStatus("STREAM ERROR | ${t.message ?: t.javaClass.simpleName}")
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
        try { vertx?.close() } catch (_: Throwable) { }
        vertx = null
    }
}

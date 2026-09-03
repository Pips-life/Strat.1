import http from 'node:http';
import MetaApi from 'metaapi.cloud-sdk';

const PORT = Number(process.env.PORT || 10000);
const TOKEN = (process.env.METAAPI_TOKEN || process.env.META_API_TOKEN || process.env.METAAPI_KEY || process.env.META_API_KEY || '').trim();
const RUNNER_TOKEN = (process.env.PIPSLIFE_BOT_CONTROL_TOKEN || TOKEN).trim();

let metaApi;
let account;
let connection;
let listener;
let running = false;
let accountId = '';
let selectedStrategy = '001';
let lastTick = null;
let lastError = null;
let connectedAt = null;
const subscriptions = new Set();

function requireToken() {
  if (!TOKEN) throw new Error('METAAPI_TOKEN is not configured');
}

function authorized(req) {
  if (!RUNNER_TOKEN) return true;
  const value = req.headers.authorization || '';
  return value === `Bearer ${RUNNER_TOKEN}`;
}

function json(res, status, body) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  res.end(JSON.stringify(body));
}

async function body(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const text = Buffer.concat(chunks).toString('utf8');
  return text ? JSON.parse(text) : {};
}

class LiveListener {
  async onSymbolPriceUpdated(instanceIndex, price) {
    lastTick = {
      symbol: price.symbol,
      bid: price.bid,
      ask: price.ask,
      time: price.time || new Date().toISOString()
    };

    // Strategy execution is deliberately event-driven: every incoming quote
    // reaches the selected strategy immediately instead of being polled.
    await strategyOnTick(lastTick);
  }

  async onPositionsUpdated(instanceIndex, positions) {
    // Kept as a hook for the stop-order/position state machine.
  }

  async onOrdersUpdated(instanceIndex, orders) {
    // Kept as a hook for detecting an opposite stop becoming active.
  }

  async onDisconnected(instanceIndex) {
    lastError = 'MetaApi streaming connection disconnected';
    running = false;
  }
}

async function strategyOnTick(tick) {
  if (!running) return;
  if (selectedStrategy === '002') {
    // Strategy 002 consumes the same live tick stream; its rule engine can be
    // attached here without changing the MetaApi transport layer.
    return;
  }
  // Strategy 001 hook. No synthetic signals are generated from missing data.
}

async function startStream(id, symbols = []) {
  requireToken();
  if (!id) throw new Error('accountId is required');

  if (connection && accountId === id && running) {
    for (const symbol of symbols) await subscribe(symbol);
    return;
  }

  await stopStream();
  accountId = id;
  metaApi = metaApi || new MetaApi(TOKEN);
  account = await metaApi.metatraderAccountApi.getAccount(accountId);
  connection = account.getStreamingConnection();
  listener = new LiveListener();
  connection.addSynchronizationListener(listener);

  await connection.connect();
  await connection.waitSynchronized({ timeoutInSeconds: 120 });
  connectedAt = new Date().toISOString();
  running = true;
  lastError = null;

  for (const symbol of symbols.length ? symbols : ['EURUSD']) await subscribe(symbol);
}

async function subscribe(symbol) {
  const s = String(symbol || '').trim();
  if (!s || !connection) return;
  await connection.subscribeToMarketData(s, [
    { type: 'quotes' },
    { type: 'ticks' }
  ], 30);
  subscriptions.add(s);
}

async function stopStream() {
  running = false;
  subscriptions.clear();
  if (connection) {
    try { connection.removeSynchronizationListener(listener); } catch {}
    try { await connection.close(); } catch {}
  }
  connection = undefined;
  account = undefined;
  listener = undefined;
  connectedAt = null;
}

async function handle(req, res) {
  if (!authorized(req)) return json(res, 401, { error: 'Unauthorized' });
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/health')) {
    return json(res, 200, {
      ok: true,
      runner: 'online',
      transport: 'metaapi-javascript-sdk-streaming',
      execution: 'tick-event-driven',
      metaapiConfigured: Boolean(TOKEN),
      liveTradingEnabled: true,
      accountId: accountId || null,
      running,
      strategy: selectedStrategy,
      subscriptions: [...subscriptions],
      connectedAt,
      lastTick,
      error: lastError
    });
  }

  if (req.method === 'GET' && url.pathname === '/state') {
    return json(res, 200, {
      configured: Boolean(TOKEN),
      state: running ? 'STREAMING' : (accountId ? 'STOPPED' : 'IDLE'),
      strategy: selectedStrategy,
      activity: lastTick ? `Streaming ${lastTick.symbol}` : 'Waiting for live quote stream',
      accountId: accountId || null,
      subscriptions: [...subscriptions],
      lastTick,
      error: lastError
    });
  }

  if (req.method === 'POST' && (url.pathname === '/' || url.pathname === '/control')) {
    try {
      const data = await body(req);
      const action = String(data.action || '').toLowerCase();
      if (action === 'select') {
        if (data.strategy !== '001' && data.strategy !== '002') throw new Error('strategy must be 001 or 002');
        selectedStrategy = data.strategy;
        return json(res, 200, { ok: true, state: running ? 'STREAMING' : 'STOPPED', strategy: selectedStrategy });
      }
      if (action === 'start') {
        await startStream(String(data.accountId || accountId), Array.isArray(data.symbols) ? data.symbols : []);
        return json(res, 200, { ok: true, state: 'STREAMING', strategy: selectedStrategy, accountId, subscriptions: [...subscriptions], lastTick });
      }
      if (action === 'subscribe') {
        await subscribe(String(data.symbol));
        return json(res, 200, { ok: true, state: 'STREAMING', strategy: selectedStrategy, subscriptions: [...subscriptions], lastTick });
      }
      if (action === 'stop') {
        await stopStream();
        return json(res, 200, { ok: true, state: 'STOPPED', strategy: selectedStrategy });
      }
      return json(res, 400, { error: 'action must be start, stop, select or subscribe' });
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error);
      return json(res, 500, { ok: false, state: 'ERROR', strategy: selectedStrategy, error: lastError });
    }
  }

  return json(res, 404, { error: 'Not found' });
}

const server = http.createServer((req, res) => {
  handle(req, res).catch(error => json(res, 500, { error: error instanceof Error ? error.message : String(error) }));
});

server.listen(PORT, () => console.log(`Pips-life JS MetaApi streaming runner listening on ${PORT}`));

process.on('SIGTERM', async () => { await stopStream(); server.close(() => process.exit(0)); });
process.on('SIGINT', async () => { await stopStream(); server.close(() => process.exit(0)); });

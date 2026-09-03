import http from 'node:http';
import MetaApi from 'metaapi.cloud-sdk';

const PORT = Number(process.env.PORT || 10000);
const TOKEN = (process.env.METAAPI_TOKEN || process.env.META_API_TOKEN || process.env.METAAPI_KEY || process.env.META_API_KEY || '').trim();
const CONTROL_TOKEN = (process.env.PIPSLIFE_BOT_CONTROL_TOKEN || TOKEN).trim();
const DEFAULT_SYMBOL = (process.env.PIPSLIFE_SYMBOL || 'XAUUSD').trim();
const LIVE_TRADING = String(process.env.PIPSLIFE_LIVE_TRADING_ENABLED ?? 'true').toLowerCase() === 'true';
const EXECUTION_VOLUME = Number(process.env.PIPSLIFE_EXECUTION_VOLUME || 0);
const TRAIL_PIPS = Number(process.env.PIPSLIFE_STRATEGY002_TRAIL_PIPS || 100);
const MAGIC = 100002;
const CLIENT_ID = 'PIPS002';

let metaApi;
const runtimes = new Map();

function auth(req) {
  if (!CONTROL_TOKEN) return true;
  return req.headers.authorization === `Bearer ${CONTROL_TOKEN}`;
}
function json(res, status, data) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  res.end(JSON.stringify(data));
}
async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const text = Buffer.concat(chunks).toString('utf8');
  return text ? JSON.parse(text) : {};
}
function side(value) {
  const v = String(value || '').toUpperCase();
  if (v.includes('BUY')) return 'BUY';
  if (v.includes('SELL')) return 'SELL';
  return null;
}
function field(obj, name, fallback = undefined) {
  return obj && typeof obj === 'object' ? (obj[name] ?? fallback) : fallback;
}
function managed(items, symbol) {
  return (items || []).filter(x => field(x, 'symbol') === symbol && (String(field(x, 'clientId', '')) === CLIENT_ID || Number(field(x, 'magic', 0)) === MAGIC));
}

class Listener {
  constructor(runtime) { this.runtime = runtime; }
  async onSymbolPriceUpdated(instanceIndex, price) { await this.runtime.onTick(price); }
  async onTicksUpdated(instanceIndex, ticks) {
    for (const tick of ticks || []) await this.runtime.onTick(tick);
  }
  async onPositionsUpdated() { await this.runtime.reconcile(); }
  async onOrdersUpdated() { await this.runtime.reconcile(); }
  async onDisconnected() {
    this.runtime.streamActive = false;
    this.runtime.activity = 'MetaApi streaming connection disconnected';
    console.log(`STREAM_DISCONNECTED account=${this.runtime.accountId}`, flush());
  }
}

// JavaScript port of Strategy 002 (Velocity Expansion).
// A non-zero live tick-to-tick movement creates an immediate directional entry.
class Runtime {
  constructor(accountId) {
    this.accountId = accountId;
    this.symbol = DEFAULT_SYMBOL;
    this.strategy = '002';
    this.state = 'READY';
    this.activity = 'Runner online';
    this.connection = null;
    this.listener = null;
    this.tickCount = 0;
    this.lastTick = null;
    this.previousPrice = 0;
    this.previousTime = 0;
    this.lastEntrySide = null;
    this.runningSide = null;
    this.pipSize = 0.01;
    this.streamActive = false;
    this.reconciling = false;
  }

  async connect(symbol = DEFAULT_SYMBOL) {
    if (!TOKEN) throw new Error('METAAPI_TOKEN is not configured');
    this.symbol = String(symbol || DEFAULT_SYMBOL).trim();
    metaApi ||= new MetaApi(TOKEN);
    const account = await metaApi.metatraderAccountApi.getAccount(this.accountId);
    if (account.state !== 'DEPLOYED') await account.deploy();
    if (account.connectionStatus !== 'CONNECTED') await account.waitConnected();
    this.connection = account.getStreamingConnection();
    this.listener = new Listener(this);
    this.connection.addSynchronizationListener(this.listener);
    await this.connection.connect();
    await this.connection.waitSynchronized({ timeoutInSeconds: 120 });
    const spec = this.connection.terminalState.specification(this.symbol);
    this.pipSize = Number(field(spec, 'pipSize', field(spec, 'point', this.pipSize))) || this.pipSize;
    await this.connection.subscribeToMarketData(this.symbol, [{ type: 'ticks' }], 30);
    this.state = 'RUNNING';
    this.activity = `Strategy 002 running on MetaApi tick stream (${this.symbol})`;
    console.log(`STREAM_CONNECTED account=${this.accountId} symbol=${this.symbol}`, 'flush');
  }

  async onTick(tick) {
    if (this.state !== 'RUNNING') return;
    const bid = Number(field(tick, 'bid', 0));
    const ask = Number(field(tick, 'ask', 0));
    const price = bid > 0 && ask > 0 ? (bid + ask) / 2 : Number(field(tick, 'last', 0));
    if (!(price > 0)) return;
    const rawTime = field(tick, 'time', Date.now());
    const time = rawTime instanceof Date ? rawTime.getTime() / 1000 : (Number(rawTime) || Date.now() / 1000);
    this.lastTick = { symbol: this.symbol, bid, ask, price, time };
    this.tickCount++;
    if (!this.streamActive) {
      this.streamActive = true;
      this.activity = `TICK_STREAM_ACTIVE strategy=002 symbol=${this.symbol}`;
      console.log(this.activity, 'flush');
    }

    const previous = this.previousPrice;
    const previousTime = this.previousTime;
    this.previousPrice = price;
    this.previousTime = time;
    if (!(previous > 0) || !(time > previousTime)) {
      await this.reconcile();
      return;
    }

    const velocity = (price - previous) / (time - previousTime);
    const direction = velocity > 0 ? 'BUY' : velocity < 0 ? 'SELL' : null;
    if (direction && direction !== this.lastEntrySide && !this.hasManagedPosition()) {
      this.lastEntrySide = direction;
      await this.enter(direction, price);
    }
    await this.reconcile();
  }

  hasManagedPosition() {
    return managed(this.connection?.terminalState?.positions || [], this.symbol).length > 0;
  }

  async enter(direction, price) {
    if (!LIVE_TRADING) {
      this.activity = `002 ${direction} ${this.symbol} — execution gated`;
      console.log(`EXECUTION_GATED strategy=002 side=${direction} symbol=${this.symbol}`);
      return;
    }
    if (!(EXECUTION_VOLUME > 0)) {
      this.activity = 'Execution blocked: PIPSLIFE_EXECUTION_VOLUME must be positive';
      console.log('ORDER_ERROR reason=invalid_execution_volume');
      return;
    }
    const distance = TRAIL_PIPS * this.pipSize;
    const stop = direction === 'BUY' ? price - distance : price + distance;
    const options = { comment: 'PipsLife002', clientId: CLIENT_ID, magic: MAGIC };
    try {
      const started = performance.now();
      const result = direction === 'BUY'
        ? await this.connection.createMarketBuyOrder(this.symbol, EXECUTION_VOLUME, stop, undefined, options)
        : await this.connection.createMarketSellOrder(this.symbol, EXECUTION_VOLUME, stop, undefined, options);
      const ack = (performance.now() - started) * 1000;
      this.runningSide = direction;
      this.activity = `002 ${direction} ${this.symbol} order acknowledged`;
      console.log(`ORDER_ACK strategy=002 side=${direction} symbol=${this.symbol} volume=${EXECUTION_VOLUME} ack_us=${ack.toFixed(0)} result=${JSON.stringify(result)}`);
    } catch (error) {
      this.lastEntrySide = null;
      this.activity = `002 order error: ${error instanceof Error ? error.message : String(error)}`;
      console.log(`ORDER_ERROR strategy=002 symbol=${this.symbol} error=${this.activity}`);
    }
  }

  async reconcile() {
    if (this.reconciling || !this.connection || this.state !== 'RUNNING') return;
    this.reconciling = true;
    try {
      const positions = managed(this.connection.terminalState.positions || [], this.symbol);
      const orders = managed(this.connection.terminalState.orders || [], this.symbol);
      if (!positions.length) return;

      // If the opposite stop has fired and both sides briefly coexist on a
      // hedging account, close the previous running side first. The new side
      // then becomes the sole running position.
      const newestSide = side(field(positions[positions.length - 1], 'type'));
      if (newestSide) {
        for (const position of positions) {
          const positionSide = side(field(position, 'type'));
          const id = String(field(position, 'id', ''));
          if (id && positionSide && positionSide !== newestSide) {
            await this.connection.closePosition(id);
            console.log(`CLOSE_ACK strategy=002 symbol=${this.symbol} reason=opposite-stop-trigger`);
          }
        }
        this.runningSide = newestSide;
      }

      const current = this.lastTick?.price || 0;
      if (!(current > 0)) return;
      const distance = TRAIL_PIPS * this.pipSize;
      const position = managed(this.connection.terminalState.positions || [], this.symbol)
        .find(p => side(field(p, 'type')) === this.runningSide) || positions[positions.length - 1];
      const positionSide = side(field(position, 'type'));
      const volume = Number(field(position, 'volume', EXECUTION_VOLUME));
      if (!positionSide || !(volume > 0)) return;
      const wantedSide = positionSide === 'BUY' ? 'SELL' : 'BUY';
      const desired = positionSide === 'BUY' ? current - distance : current + distance;
      if (!(desired > 0)) return;

      const existing = orders.find(o => side(field(o, 'type')) === wantedSide && String(field(o, 'clientId', '')) === CLIENT_ID);
      if (!existing) {
        const options = { comment: `PipsLife002:${String(field(position, 'id', ''))}`, clientId: CLIENT_ID, magic: MAGIC };
        const result = wantedSide === 'SELL'
          ? await this.connection.createStopSellOrder(this.symbol, volume, desired, undefined, undefined, options)
          : await this.connection.createStopBuyOrder(this.symbol, volume, desired, undefined, undefined, options);
        console.log(`STOP_ACK strategy=002 side=${wantedSide} symbol=${this.symbol} volume=${volume} price=${desired} result=${JSON.stringify(result)}`);
      } else {
        const old = Number(field(existing, 'openPrice', 0));
        const improves = positionSide === 'BUY' ? desired > old : desired < old;
        if (improves && field(existing, 'id')) await this.connection.modifyOrder(String(field(existing, 'id')), desired, undefined, undefined);
      }
    } catch (error) {
      console.log(`STOP_ERROR strategy=002 symbol=${this.symbol} error=${error instanceof Error ? error.message : String(error)}`);
    } finally {
      this.reconciling = false;
    }
  }

  async stop() {
    this.state = 'STOPPING';
    if (this.connection) {
      try { if (this.listener) this.connection.removeSynchronizationListener(this.listener); } catch {}
      try { await this.connection.unsubscribeFromMarketData(this.symbol); } catch {}
      try { await this.connection.close(); } catch {}
    }
    this.connection = null;
    this.listener = null;
    this.streamActive = false;
    this.state = 'STOPPED';
    this.activity = 'Trading stopped';
  }

  stateJson() {
    return {
      configured: Boolean(TOKEN),
      state: this.state,
      strategy: this.strategy,
      activity: this.activity,
      accountId: this.accountId,
      symbol: this.symbol,
      ticks: this.tickCount,
      streamActive: this.streamActive,
      lastTick: this.lastTick,
      liveTradingEnabled: LIVE_TRADING,
      executionVolumeConfigured: EXECUTION_VOLUME > 0
    };
  }
}

async function control(data) {
  const accountId = String(data.accountId || process.env.METAAPI_ACCOUNT_ID || '').trim();
  if (!accountId) throw new Error('accountId is required');
  let runtime = runtimes.get(accountId);
  if (!runtime) { runtime = new Runtime(accountId); runtimes.set(accountId, runtime); }
  const action = String(data.action || '').toLowerCase();
  if (action === 'select') {
    if (data.strategy !== '002' && data.strategy !== '001') throw new Error('strategy must be 001 or 002');
    runtime.strategy = data.strategy;
    runtime.activity = `Strategy ${data.strategy} selected`;
    return runtime.stateJson();
  }
  if (action === 'start') {
    if (data.strategy === '001') throw new Error('Strategy 001 requires its existing QOF/options engine; JavaScript live stream is enabled for Strategy 002');
    runtime.strategy = '002';
    await runtime.connect(data.symbol || DEFAULT_SYMBOL);
    return runtime.stateJson();
  }
  if (action === 'stop') { await runtime.stop(); return runtime.stateJson(); }
  if (action === 'subscribe') { if (!runtime.connection) throw new Error('runner is not streaming'); await runtime.connection.subscribeToMarketData(String(data.symbol), [{ type: 'ticks' }], 30); return runtime.stateJson(); }
  throw new Error('action must be start, stop, select or subscribe');
}

const server = http.createServer(async (req, res) => {
  if (!auth(req)) return json(res, 401, { error: 'Unauthorized' });
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  try {
    if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/health')) {
      const first = runtimes.values().next().value;
      return json(res, 200, {
        ok: true, runner: 'online', transport: 'metaapi-javascript-sdk-streaming', execution: 'tick-event-driven',
        metaapiConfigured: Boolean(TOKEN), liveTradingEnabled: LIVE_TRADING,
        executionVolumeConfigured: EXECUTION_VOLUME > 0, accounts: runtimes.size,
        state: first ? first.stateJson() : null
      });
    }
    if (req.method === 'GET' && url.pathname === '/state') {
      const id = url.searchParams.get('accountId');
      const runtime = id ? runtimes.get(id) : runtimes.values().next().value;
      return json(res, 200, runtime ? runtime.stateJson() : { configured: Boolean(TOKEN), state: 'READY', strategy: '001', activity: 'Runner online' });
    }
    if (req.method === 'POST' && (url.pathname === '/' || url.pathname === '/control')) return json(res, 200, await control(await readBody(req)));
    return json(res, 404, { error: 'Not found' });
  } catch (error) {
    return json(res, 500, { ok: false, state: 'ERROR', error: error instanceof Error ? error.message : String(error) });
  }
});

server.listen(PORT, () => console.log(`Pips-life JS MetaApi streaming runner listening on ${PORT}`));
process.on('SIGTERM', async () => { for (const r of runtimes.values()) await r.stop(); server.close(() => process.exit(0)); });
process.on('SIGINT', async () => { for (const r of runtimes.values()) await r.stop(); server.close(() => process.exit(0)); });

import { metaApi } from './metaapi';

const SYMBOL = process.env.PIPSLIFE_SYMBOL?.trim() || 'XAUUSD';
const MAGIC = 100002;
const CLIENT_ID = 'PIPS002';
const VOLUME = 0.01;
const TRAIL_DISTANCE = 100 * 0.01;
const MAX_STREAM_MS = 290_000;

type Price = { symbol?: string; bid?: number; ask?: number; last?: number; time?: string };
type Position = { id?: string; type?: string; symbol?: string; volume?: number; clientId?: string; magic?: number };
type Order = { id?: string; type?: string; symbol?: string; volume?: number; openPrice?: number; comment?: string; clientId?: string; magic?: number };

type Runner = {
  running: boolean;
  connection: any;
  listener: any;
  lastPrice: number;
  busy: boolean;
  startedAt: number;
  promise: Promise<void>;
};

const runners = new Map<string, Runner>();

function priceOf(tick: Price) {
  const bid = Number(tick.bid ?? 0);
  const ask = Number(tick.ask ?? 0);
  const last = Number(tick.last ?? 0);
  return bid > 0 && ask > 0 ? (bid + ask) / 2 : last;
}

function side(type: unknown): 'BUY' | 'SELL' | undefined {
  const value = String(type ?? '').toUpperCase();
  if (value.includes('BUY')) return 'BUY';
  if (value.includes('SELL')) return 'SELL';
  return undefined;
}

function managed<T extends { symbol?: string; clientId?: string; magic?: number }>(items: T[]) {
  return items.filter(item => item.symbol === SYMBOL && (Number(item.magic) === MAGIC || item.clientId === CLIENT_ID));
}

async function maintainStops(runner: Runner, currentPrice: number) {
  const terminal = runner.connection.terminalState;
  const positions = managed((terminal.positions ?? []) as Position[]);
  const orders = managed((terminal.orders ?? []) as Order[]);
  const used = new Set<string>();

  await Promise.all(positions.map(async position => {
    if (!position.id) return;
    const positionSide = side(position.type);
    if (!positionSide) return;

    const wanted = positionSide === 'BUY' ? 'SELL' : 'BUY';
    const desired = positionSide === 'BUY' ? currentPrice - TRAIL_DISTANCE : currentPrice + TRAIL_DISTANCE;
    if (!(desired > 0)) return;

    const marker = `PipsLife002:${position.id}`;
    const existing = orders.find(order =>
      order.id && !used.has(order.id) &&
      (order.comment === marker || order.comment === 'PipsLife002') &&
      side(order.type) === wanted
    );

    if (!existing) {
      if (positionSide === 'BUY') {
        await runner.connection.createStopSellOrder(SYMBOL, Number(position.volume ?? VOLUME), Number(desired.toFixed(2)), null, null, {
          comment: marker, clientId: CLIENT_ID, magic: MAGIC,
        });
      } else {
        await runner.connection.createStopBuyOrder(SYMBOL, Number(position.volume ?? VOLUME), Number(desired.toFixed(2)), null, null, {
          comment: marker, clientId: CLIENT_ID, magic: MAGIC,
        });
      }
      return;
    }

    used.add(existing.id);
    const oldPrice = Number(existing.openPrice ?? 0);
    const shouldMove = positionSide === 'BUY' ? desired > oldPrice : desired < oldPrice;
    if (shouldMove) await runner.connection.modifyOrder(existing.id, Number(desired.toFixed(2)), null, null);
  }));
}

async function handlePrice(accountId: string, runner: Runner, tick: Price) {
  if (!runner.running || runner.busy || tick.symbol !== SYMBOL) return;
  const currentPrice = priceOf(tick);
  if (!(currentPrice > 0)) return;

  const previous = runner.lastPrice;
  runner.lastPrice = currentPrice;
  const delta = previous > 0 ? currentPrice - previous : 0;
  if (delta === 0) return;

  // The first non-zero streamed price change is the trigger. There is no polling or threshold.
  runner.busy = true;
  try {
    const direction = delta > 0 ? 'BUY' : 'SELL';
    if (direction === 'BUY') {
      await runner.connection.createMarketBuyOrder(SYMBOL, VOLUME, null, null, {
        comment: 'PipsLife002', clientId: CLIENT_ID, magic: MAGIC,
      });
    } else {
      await runner.connection.createMarketSellOrder(SYMBOL, VOLUME, null, null, {
        comment: 'PipsLife002', clientId: CLIENT_ID, magic: MAGIC,
      });
    }
    await maintainStops(runner, currentPrice);
    console.log(`[Strategy002][STREAM] ${direction} ${SYMBOL} delta=${delta.toFixed(5)} price=${currentPrice.toFixed(2)} account=${accountId}`);
  } catch (error) {
    console.error(`[Strategy002][STREAM] execution error account=${accountId}`, error);
  } finally {
    runner.busy = false;
  }
}

export async function startStrategy002Stream(accountId: string) {
  const existing = runners.get(accountId);
  if (existing?.running) {
    return { state: 'RUNNING', strategy: '002', activity: 'Strategy 002 is already connected to the MetaApi live stream.' };
  }

  const api = await metaApi();
  const account = await api.metatraderAccountApi.getAccount(accountId);
  const connection = account.getStreamingConnection();
  const runner = {
    running: true,
    connection,
    listener: null,
    lastPrice: 0,
    busy: false,
    startedAt: Date.now(),
    promise: Promise.resolve(),
  } as Runner;

  const { SynchronizationListener } = await import('metaapi.cloud-sdk');
  class Strategy002Listener extends SynchronizationListener {
    async onSymbolPriceUpdated(_instanceIndex: number, price: Price) {
      await handlePrice(accountId, runner, price);
    }
  }

  const listener = new Strategy002Listener();
  runner.listener = listener;
  connection.addSynchronizationListener(listener);
  runners.set(accountId, runner);

  runner.promise = (async () => {
    try {
      await connection.connect();
      await connection.waitSynchronized();
      await connection.subscribeToMarketData(SYMBOL);
      await new Promise<void>(resolve => setTimeout(resolve, MAX_STREAM_MS));
    } catch (error) {
      console.error(`[Strategy002][STREAM] connection error account=${accountId}`, error);
    } finally {
      runner.running = false;
      try { connection.removeSynchronizationListener(listener); } catch {}
      try { await connection.unsubscribeFromMarketData(SYMBOL); } catch {}
      try { await connection.close(); } catch {}
      if (runners.get(accountId) === runner) runners.delete(accountId);
    }
  })();

  return {
    state: 'RUNNING',
    strategy: '002',
    activity: `Strategy 002 connected to MetaApi live ${SYMBOL} stream. Every non-zero price change is an immediate direction trigger.`,
  };
}

export async function stopStrategy002Stream(accountId: string) {
  const runner = runners.get(accountId);
  if (!runner) return;
  runner.running = false;
  try { runner.connection.removeSynchronizationListener(runner.listener); } catch {}
  try { await runner.connection.unsubscribeFromMarketData(SYMBOL); } catch {}
  try { await runner.connection.close(); } catch {}
  runners.delete(accountId);
}

export function strategy002StreamRunning(accountId: string) {
  return Boolean(runners.get(accountId)?.running);
}

export function strategy002StreamPromise(accountId: string) {
  return runners.get(accountId)?.promise;
}

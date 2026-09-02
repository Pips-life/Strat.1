import { getCache } from '@vercel/functions';

const PROVISIONING = process.env.METAAPI_PROVISIONING_URL ?? 'https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai';
const CLIENT_DEFAULT = process.env.METAAPI_CLIENT_API_URL ?? 'https://mt-client-api-v1.new-york.agiliumtrade.ai';
const SYMBOL = process.env.PIPSLIFE_SYMBOL?.trim() || 'XAUUSD';
const MAGIC = 100002;
const CLIENT_ID = 'PIPS002';
const TRAIL_DISTANCE = 100 * 0.01;
const VOLUME = 0.01;

type Tick = { bid?: number; ask?: number; last?: number; time?: string };
type Position = { id?: string; type?: string; symbol?: string; volume?: number; openPrice?: number; time?: string; clientId?: string; magic?: number };
type Order = { id?: string; type?: string; symbol?: string; volume?: number; openPrice?: number; comment?: string; clientId?: string; magic?: number };

type Account = { server?: string; region?: string };

async function json(response: Response): Promise<any> {
  const text = await response.text();
  try { return text ? JSON.parse(text) : null; } catch { return { error: text }; }
}

async function metaFetch(url: string, init?: RequestInit) {
  const token = process.env.METAAPI_TOKEN?.trim();
  if (!token) throw new Error('METAAPI_TOKEN is not configured');
  const response = await fetch(url, {
    ...init,
    headers: {
      accept: 'application/json',
      ...(init?.body ? { 'content-type': 'application/json' } : {}),
      'auth-token': token,
      ...(init?.headers ?? {}),
    },
    cache: 'no-store',
  });
  const data = await json(response);
  if (!response.ok) throw new Error(String(data?.message ?? data?.error ?? `MetaApi request failed (${response.status})`));
  return data;
}

function p(t: Tick) {
  const bid = Number(t.bid ?? 0), ask = Number(t.ask ?? 0), last = Number(t.last ?? 0);
  return bid > 0 && ask > 0 ? (bid + ask) / 2 : last;
}

function side(type: unknown): 'BUY' | 'SELL' | undefined {
  const s = String(type ?? '').toUpperCase();
  if (s.includes('BUY')) return 'BUY';
  if (s.includes('SELL')) return 'SELL';
  return undefined;
}

function managed<T extends { symbol?: string; clientId?: string; magic?: number }>(items: T[]) {
  return items.filter(x => x.symbol === SYMBOL && (Number(x.magic) === MAGIC || x.clientId === CLIENT_ID));
}

async function accountInfo(accountId: string): Promise<{ account: Account; api: string }> {
  const account = await metaFetch(`${PROVISIONING}/users/current/accounts/${encodeURIComponent(accountId)}`) as Account;
  const api = account.region ? `https://mt-client-api-v1.${account.region}.agiliumtrade.ai` : CLIENT_DEFAULT;
  return { account, api };
}

async function trade(api: string, accountId: string, body: Record<string, unknown>) {
  return metaFetch(`${api}/users/current/accounts/${encodeURIComponent(accountId)}/trade`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

async function state(accountId: string) {
  return await getCache().get(`pipslife:bot:${accountId}`) as { running?: boolean; strategy?: string } | null;
}

export async function executeStrategy002(accountId: string, startLoop: () => Promise<unknown>) {
  if ((process.env.PIPSLIFE_EXECUTION_MODE ?? 'demo').trim().toLowerCase() !== 'demo') {
    return { configured: true, state: 'READY', strategy: '002', activity: 'Strategy 002 requires demo execution mode.' };
  }

  await startLoop();
  const { account, api } = await accountInfo(accountId);
  const accountPath = encodeURIComponent(accountId);
  const region = api.match(/mt-client-api-v1\.([^.]+)\.agiliumtrade\.ai/)?.[1] ?? 'new-york';
  const marketApi = `https://mt-market-data-client-api-v1.${region}.agiliumtrade.ai`;

  const [info, positionsRaw, ordersRaw, historyRaw, currentRaw] = await Promise.all([
    metaFetch(`${api}/users/current/accounts/${accountPath}/account-information`),
    metaFetch(`${api}/users/current/accounts/${accountPath}/positions`),
    metaFetch(`${api}/users/current/accounts/${accountPath}/orders`),
    metaFetch(`${marketApi}/users/current/accounts/${accountPath}/historical-market-data/symbols/${encodeURIComponent(SYMBOL)}/ticks?limit=2`),
    metaFetch(`${api}/users/current/accounts/${accountPath}/symbols/${encodeURIComponent(SYMBOL)}/current-tick?keepSubscription=true`),
  ]);

  if (info?.tradeAllowed === false) return { configured: true, state: 'ERROR', strategy: '002', activity: 'MT5 account does not allow trading.' };
  const connection = String(info?.connectionStatus ?? '').toUpperCase();
  if (connection && connection !== 'CONNECTED') return { configured: true, state: 'ERROR', strategy: '002', activity: `MetaApi account is ${connection}; waiting for broker connection.` };

  const positions = managed((Array.isArray(positionsRaw) ? positionsRaw : []) as Position[]);
  const orders = managed((Array.isArray(ordersRaw) ? ordersRaw : []) as Order[]);
  const historical = (Array.isArray(historyRaw) ? historyRaw : Array.isArray(historyRaw?.ticks) ? historyRaw.ticks : []) as Tick[];
  const current = currentRaw as Tick;
  const currentPrice = p(current);
  const previous = historical.length ? p(historical[historical.length - 1]) : 0;
  const delta = currentPrice > 0 && previous > 0 ? currentPrice - previous : 0;
  const direction: 'BUY' | 'SELL' | 'WAIT' = delta > 0 ? 'BUY' : delta < 0 ? 'SELL' : 'WAIT';

  // Every managed position gets its own opposite stop. The stop follows the live price,
  // not the original entry, and is moved only in the profitable direction.
  const usedOrderIds = new Set<string>();
  for (const position of positions) {
    const positionSide = side(position.type);
    if (!positionSide || !position.id) continue;
    const wanted = positionSide === 'BUY' ? 'SELL' : 'BUY';
    const desired = positionSide === 'BUY' ? currentPrice - TRAIL_DISTANCE : currentPrice + TRAIL_DISTANCE;
    const marker = `PipsLife002:${position.id}`;
    const existing = orders.find(o => (o.comment === marker || o.comment === 'PipsLife002') && side(o.type) === wanted && !usedOrderIds.has(String(o.id)));
    if (!existing) {
      await trade(api, accountId, {
        actionType: positionSide === 'BUY' ? 'ORDER_TYPE_SELL_STOP' : 'ORDER_TYPE_BUY_STOP',
        symbol: SYMBOL,
        volume: Number(position.volume ?? VOLUME),
        openPrice: Number(desired.toFixed(2)),
        magic: MAGIC,
        clientId: CLIENT_ID,
        comment: marker,
      });
    } else if (existing.id) {
      usedOrderIds.add(existing.id);
      const oldPrice = Number(existing.openPrice ?? 0);
      const shouldMove = positionSide === 'BUY' ? desired > oldPrice : desired < oldPrice;
      if (shouldMove && desired > 0) {
        await trade(api, accountId, {
          actionType: 'ORDER_MODIFY',
          orderId: existing.id,
          openPrice: Number(desired.toFixed(2)),
          magic: MAGIC,
          clientId: CLIENT_ID,
          comment: marker,
        });
      }
    }
  }

  // No position: any non-zero tick movement is enough to enter immediately.
  if (positions.length === 0 && direction !== 'WAIT' && currentPrice > 0) {
    await trade(api, accountId, {
      actionType: direction === 'BUY' ? 'ORDER_TYPE_BUY' : 'ORDER_TYPE_SELL',
      symbol: SYMBOL,
      volume: VOLUME,
      magic: MAGIC,
      clientId: CLIENT_ID,
      comment: 'PipsLife002',
    });
    return { configured: true, state: 'RUNNING', strategy: '002', activity: `VELOCITY ${direction}: ${SYMBOL} delta=${delta.toFixed(5)} · market order opened immediately`, positionCount: 1 };
  }

  return {
    configured: true,
    state: 'RUNNING',
    strategy: '002',
    activity: `VELOCITY ${direction}: ${SYMBOL} price=${currentPrice.toFixed(2)} delta=${delta.toFixed(5)} · ${positions.length} position(s) · opposite stops maintained at 100 pips`,
    positionCount: positions.length,
    pendingCount: orders.length,
  };
}

export async function strategy002State(accountId: string) { return state(accountId); }
export async function strategy002Account(accountId: string) { return accountInfo(accountId); }

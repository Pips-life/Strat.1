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
type TickState = { price: number; time?: string };

async function json(response: Response): Promise<any> { const text = await response.text(); try { return text ? JSON.parse(text) : null; } catch { return { error: text }; } }
async function metaFetch(url: string, init?: RequestInit) {
  const token = process.env.METAAPI_TOKEN?.trim();
  if (!token) throw new Error('METAAPI_TOKEN is not configured');
  const response = await fetch(url, { ...init, headers: { accept: 'application/json', ...(init?.body ? { 'content-type': 'application/json' } : {}), 'auth-token': token, ...(init?.headers ?? {}) }, cache: 'no-store' });
  const data = await json(response);
  if (!response.ok) throw new Error(String(data?.message ?? data?.error ?? `MetaApi request failed (${response.status})`));
  return data;
}
function p(t: Tick) { const bid = Number(t.bid ?? 0), ask = Number(t.ask ?? 0), last = Number(t.last ?? 0); return bid > 0 && ask > 0 ? (bid + ask) / 2 : last; }
function side(type: unknown): 'BUY' | 'SELL' | undefined { const s = String(type ?? '').toUpperCase(); if (s.includes('BUY')) return 'BUY'; if (s.includes('SELL')) return 'SELL'; return undefined; }
function managed<T extends { symbol?: string; clientId?: string; magic?: number }>(items: T[]) { return items.filter(x => x.symbol === SYMBOL && (Number(x.magic) === MAGIC || x.clientId === CLIENT_ID)); }
async function accountInfo(accountId: string): Promise<{ account: Account; api: string }> { const account = await metaFetch(`${PROVISIONING}/users/current/accounts/${encodeURIComponent(accountId)}`) as Account; const api = account.region ? `https://mt-client-api-v1.${account.region}.agiliumtrade.ai` : CLIENT_DEFAULT; return { account, api }; }
async function trade(api: string, accountId: string, body: Record<string, unknown>) { return metaFetch(`${api}/users/current/accounts/${encodeURIComponent(accountId)}/trade`, { method: 'POST', body: JSON.stringify(body) }); }

export async function executeStrategy002(accountId: string, startLoop: () => Promise<unknown>) {
  if ((process.env.PIPSLIFE_EXECUTION_MODE ?? 'demo').trim().toLowerCase() !== 'demo') return { configured: true, state: 'READY', strategy: '002', activity: 'Strategy 002 requires demo execution mode.' };
  await startLoop();
  const { api } = await accountInfo(accountId);
  const accountPath = encodeURIComponent(accountId);
  const region = api.match(/mt-client-api-v1\.([^.]+)\.agiliumtrade\.ai/)?.[1] ?? 'new-york';
  const marketApi = `https://mt-market-data-client-api-v1.${region}.agiliumtrade.ai`;

  const [info, positionsRaw, ordersRaw, currentRaw] = await Promise.all([
    metaFetch(`${api}/users/current/accounts/${accountPath}/account-information`),
    metaFetch(`${api}/users/current/accounts/${accountPath}/positions`),
    metaFetch(`${api}/users/current/accounts/${accountPath}/orders`),
    metaFetch(`${api}/users/current/accounts/${accountPath}/symbols/${encodeURIComponent(SYMBOL)}/current-tick?keepSubscription=true`),
  ]);
  if (info?.tradeAllowed === false) return { configured: true, state: 'ERROR', strategy: '002', activity: 'MT5 account does not allow trading.' };
  const connection = String(info?.connectionStatus ?? '').toUpperCase();
  if (connection && connection !== 'CONNECTED') return { configured: true, state: 'ERROR', strategy: '002', activity: `MetaApi account is ${connection}; waiting for broker connection.` };

  const positions = managed((Array.isArray(positionsRaw) ? positionsRaw : []) as Position[]);
  const orders = managed((Array.isArray(ordersRaw) ? ordersRaw : []) as Order[]);
  const current = currentRaw as Tick;
  const currentPrice = p(current);
  if (!(currentPrice > 0)) return { configured: true, state: 'RUNNING', strategy: '002', activity: `No live ${SYMBOL} tick received yet.`, positionCount: positions.length };

  // Compare the live tick with our own last processed tick. Do not compare against
  // historical data: that can return the same latest tick and produce delta=0 forever.
  const cache = getCache();
  const tickKey = `pipslife:strategy002:tick:${accountId}`;
  const previous = await cache.get(tickKey) as TickState | null;
  const delta = previous?.price > 0 ? currentPrice - previous.price : 0;
  const changed = delta !== 0 || (current.time && current.time !== previous?.time);
  await cache.set(tickKey, { price: currentPrice, time: current.time }, { ttl: 3600, name: `strategy002 tick ${accountId}` });
  const direction: 'BUY' | 'SELL' | 'WAIT' = delta > 0 ? 'BUY' : delta < 0 ? 'SELL' : 'WAIT';

  // Execute the market entry before any trailing-stop maintenance so the entry path
  // is as short as possible when a new tick arrives.
  let opened = false;
  if (changed && direction !== 'WAIT') {
    await trade(api, accountId, { actionType: direction === 'BUY' ? 'ORDER_TYPE_BUY' : 'ORDER_TYPE_SELL', symbol: SYMBOL, volume: VOLUME, magic: MAGIC, clientId: CLIENT_ID, comment: 'PipsLife002' });
    opened = true;
  }

  // Every open position gets its own opposite pending stop, trailing live price by 100 pips.
  const usedOrderIds = new Set<string>();
  await Promise.all(positions.map(async (position) => {
    const positionSide = side(position.type);
    if (!positionSide || !position.id) return;
    const wanted = positionSide === 'BUY' ? 'SELL' : 'BUY';
    const desired = positionSide === 'BUY' ? currentPrice - TRAIL_DISTANCE : currentPrice + TRAIL_DISTANCE;
    const marker = `PipsLife002:${position.id}`;
    const existing = orders.find(o => (o.comment === marker || o.comment === 'PipsLife002') && side(o.type) === wanted && !usedOrderIds.has(String(o.id)));
    if (!existing) {
      await trade(api, accountId, { actionType: positionSide === 'BUY' ? 'ORDER_TYPE_SELL_STOP' : 'ORDER_TYPE_BUY_STOP', symbol: SYMBOL, volume: Number(position.volume ?? VOLUME), openPrice: Number(desired.toFixed(2)), magic: MAGIC, clientId: CLIENT_ID, comment: marker });
    } else if (existing.id) {
      usedOrderIds.add(existing.id);
      const oldPrice = Number(existing.openPrice ?? 0);
      const shouldMove = positionSide === 'BUY' ? desired > oldPrice : desired < oldPrice;
      if (shouldMove && desired > 0) await trade(api, accountId, { actionType: 'ORDER_MODIFY', orderId: existing.id, openPrice: Number(desired.toFixed(2)), magic: MAGIC, clientId: CLIENT_ID, comment: marker });
    }
  }));

  return {
    configured: true,
    state: 'RUNNING',
    strategy: '002',
    activity: opened
      ? `VELOCITY ${direction}: ${SYMBOL} delta=${delta.toFixed(5)} · market order opened on live tick · trailing stops maintained at 100 pips`
      : `VELOCITY WAIT: ${SYMBOL} price=${currentPrice.toFixed(2)} · ${positions.length} position(s) · trailing stops maintained at 100 pips`,
    positionCount: positions.length + (opened ? 1 : 0),
    pendingCount: orders.length,
  };
}

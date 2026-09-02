import { NextResponse } from 'next/server';
import { getCache } from '@vercel/functions';
import { start, getRun } from 'workflow/api';
import { verifyAccountSession } from '@/lib/session';
import { strategy002Loop } from '@/workflows/strategy002-loop';

const PROVISIONING = process.env.METAAPI_PROVISIONING_URL ?? 'https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai';
const CLIENT_DEFAULT = process.env.METAAPI_CLIENT_API_URL ?? 'https://mt-client-api-v1.new-york.agiliumtrade.ai';
const SYMBOL = process.env.PIPSLIFE_SYMBOL?.trim() || 'XAUUSD';
const MAGIC = 100002;
const CLIENT_ID = 'PIPS002';
const TRAIL_PIPS = 100;
const PIP_SIZE = 0.01;
const TRAIL_DISTANCE = TRAIL_PIPS * PIP_SIZE;
const EXECUTION_MODE = (process.env.PIPSLIFE_EXECUTION_MODE ?? 'demo').trim().toLowerCase();

export const runtime = 'nodejs';
export const maxDuration = 300;

type Account = { _id?: string; login?: string | number; server?: string; region?: string; connectionStatus?: string };
type Position = { id?: string; type?: string; symbol?: string; volume?: number; openPrice?: number; time?: string; clientId?: string; magic?: number };
type Order = { id?: string; type?: string; symbol?: string; volume?: number; openPrice?: number; time?: string; clientId?: string; magic?: number; state?: string };
type Tick = { time?: string; bid?: number; ask?: number; last?: number };
type BotState = { running: boolean; strategy: string; runId?: string; loopToken?: string };
type RunnerState = { configured: boolean; state: string; strategy: string; activity: string; error?: string; positionCount?: number; pendingCount?: number };

function cacheKey(accountId: string) { return `pipslife:bot:${accountId}`; }
export async function getState(accountId: string) { return await getCache().get(cacheKey(accountId)) as BotState | null; }
async function saveState(accountId: string, running: boolean, strategy: string, runId?: string, loopToken?: string) {
  await getCache().set(cacheKey(accountId), { running, strategy, ...(runId ? { runId } : {}), ...(loopToken ? { loopToken } : {}) }, { ttl: 86400, tags: [`pipslife-bot-${accountId}`], name: `bot ${accountId}` });
}
async function readJson(response: Response): Promise<any> { const text = await response.text(); try { return text ? JSON.parse(text) : null; } catch { return { error: text }; } }
async function metaFetch(url: string, init?: RequestInit) {
  const token = process.env.METAAPI_TOKEN?.trim();
  if (!token) throw new Error('METAAPI_TOKEN is not configured');
  const response = await fetch(url, { ...init, headers: { accept: 'application/json', ...(init?.body ? { 'content-type': 'application/json' } : {}), 'auth-token': token, ...(init?.headers ?? {}) }, cache: 'no-store' });
  const data = await readJson(response);
  if (!response.ok) throw new Error(String(data?.message ?? data?.error ?? `MetaApi request failed (${response.status})`));
  return data;
}
export async function accountInfo(accountId: string): Promise<{ account: Account; api: string }> {
  const account = await metaFetch(`${PROVISIONING}/users/current/accounts/${encodeURIComponent(accountId)}`) as Account;
  const api = account.region ? `https://mt-client-api-v1.${account.region}.agiliumtrade.ai` : CLIENT_DEFAULT;
  return { account, api };
}
function price(t: Tick) { const bid = Number(t.bid ?? 0), ask = Number(t.ask ?? 0), last = Number(t.last ?? 0); return bid > 0 && ask > 0 ? (bid + ask) / 2 : last; }
function side(v: unknown): 'BUY' | 'SELL' | undefined { const s = String(v ?? '').toUpperCase(); if (s.includes('BUY')) return 'BUY'; if (s.includes('SELL')) return 'SELL'; return undefined; }
function ticks(raw: any): Tick[] { return Array.isArray(raw) ? raw : Array.isArray(raw?.ticks) ? raw.ticks : Array.isArray(raw?.items) ? raw.items : []; }
async function trade(api: string, accountId: string, body: Record<string, unknown>) { return metaFetch(`${api}/users/current/accounts/${encodeURIComponent(accountId)}/trade`, { method: 'POST', body: JSON.stringify(body) }); }

async function ensureStrategy002Loop(accountId: string) {
  if (EXECUTION_MODE !== 'demo') return undefined;
  const saved = await getState(accountId);
  if (saved?.runId && saved.loopToken) {
    try {
      const status = await getRun(saved.runId).status;
      if (status === 'running' || status === 'pending') return saved.runId;
    } catch {}
  }

  const loopToken = crypto.randomUUID();
  await saveState(accountId, true, '002', undefined, loopToken);
  try {
    const run = await start(strategy002Loop, [accountId, loopToken]);
    await saveState(accountId, true, '002', run.runId, loopToken);
    return run.runId;
  } catch (error) {
    await saveState(accountId, false, '002');
    throw error;
  }
}

export async function execute002(accountId: string, api: string, startLoop = true): Promise<RunnerState> {
  if (EXECUTION_MODE !== 'demo') return { configured: true, state: 'READY', strategy: '002', activity: `Execution locked: PIPSLIFE_EXECUTION_MODE=${EXECUTION_MODE}. Demo mode is required for this test build.` };

  if (startLoop) await ensureStrategy002Loop(accountId);

  const region = api.match(/mt-client-api-v1\.([^.]+)\.agiliumtrade\.ai/)?.[1] ?? 'new-york';
  const marketApi = `https://mt-market-data-client-api-v1.${region}.agiliumtrade.ai`;
  const accountPath = `${encodeURIComponent(accountId)}`;
  const [info, positionsRaw, ordersRaw, historyRaw, currentRaw] = await Promise.all([
    metaFetch(`${api}/users/current/accounts/${accountPath}/account-information`),
    metaFetch(`${api}/users/current/accounts/${accountPath}/positions`),
    metaFetch(`${api}/users/current/accounts/${accountPath}/orders`),
    metaFetch(`${marketApi}/users/current/accounts/${accountPath}/historical-market-data/symbols/${encodeURIComponent(SYMBOL)}/ticks?limit=64`),
    metaFetch(`${api}/users/current/accounts/${accountPath}/symbols/${encodeURIComponent(SYMBOL)}/current-tick`)
  ]);

  if (info?.tradeAllowed === false) return { configured: true, state: 'ERROR', strategy: '002', activity: 'MT5 account does not allow trading.' };
  const connection = String(info?.connectionStatus ?? '').toUpperCase();
  if (connection && connection !== 'CONNECTED') return { configured: true, state: 'ERROR', strategy: '002', activity: `MetaApi account is ${connection}; waiting for broker connection.` };

  const positions = (Array.isArray(positionsRaw) ? positionsRaw : []) as Position[];
  const orders = (Array.isArray(ordersRaw) ? ordersRaw : []) as Order[];
  const historical = ticks(historyRaw).filter(t => price(t) > 0);
  const current = (currentRaw && typeof currentRaw === 'object' ? currentRaw : null) as Tick | null;
  const currentPrice = current ? price(current) : 0;
  const previous = historical.length ? price(historical[historical.length - 1]) : 0;
  const delta = currentPrice > 0 && previous > 0 ? currentPrice - previous : 0;
  const direction: 'BUY' | 'SELL' | 'WAIT' = delta > 0 ? 'BUY' : delta < 0 ? 'SELL' : 'WAIT';

  const managedPositions = positions.filter(p => p.symbol === SYMBOL && (Number(p.magic) === MAGIC || p.clientId === CLIENT_ID));
  const managedOrders = orders.filter(o => o.symbol === SYMBOL && (Number(o.magic) === MAGIC || o.clientId === CLIENT_ID));
  const livePosition = [...managedPositions].sort((a, b) => new Date(String(b.time ?? 0)).getTime() - new Date(String(a.time ?? 0)).getTime())[0];
  const liveSide = side(livePosition?.type);

  if (managedPositions.length > 1) {
    const sorted = [...managedPositions].sort((a, b) => new Date(String(a.time ?? 0)).getTime() - new Date(String(b.time ?? 0)).getTime());
    for (const old of sorted.slice(0, -1)) if (old.id) await trade(api, accountId, { actionType: 'POSITION_CLOSE_ID', positionId: old.id, magic: MAGIC, clientId: CLIENT_ID, comment: 'PipsLife002' });
  }

  const wantedStopSide = liveSide === 'BUY' ? 'SELL' : liveSide === 'SELL' ? 'BUY' : undefined;
  const pending = wantedStopSide ? managedOrders.find(o => side(o.type) === wantedStopSide) : undefined;

  if (livePosition && !pending) {
    const entry = Number(livePosition.openPrice ?? currentPrice);
    const stop = liveSide === 'BUY' ? entry - TRAIL_DISTANCE : entry + TRAIL_DISTANCE;
    if (entry > 0 && liveSide) await trade(api, accountId, { actionType: liveSide === 'BUY' ? 'ORDER_TYPE_SELL_STOP' : 'ORDER_TYPE_BUY_STOP', symbol: SYMBOL, volume: Number(livePosition.volume ?? 0.01), openPrice: Number(stop.toFixed(2)), magic: MAGIC, clientId: CLIENT_ID, comment: 'PipsLife002' });
    return { configured: true, state: 'RUNNING', strategy: '002', activity: `LIVE ${liveSide}: ${SYMBOL} ${Number(livePosition.volume ?? 0.01).toFixed(2)} · 100-pip opposite stop ${stop.toFixed(2)}`, positionCount: managedPositions.length, pendingCount: 1 };
  }

  if (!livePosition && direction !== 'WAIT' && currentPrice > 0) {
    const actionType = direction === 'BUY' ? 'ORDER_TYPE_BUY' : 'ORDER_TYPE_SELL';
    await trade(api, accountId, { actionType, symbol: SYMBOL, volume: 0.01, magic: MAGIC, clientId: CLIENT_ID, comment: 'PipsLife002' });
    const afterRaw = await metaFetch(`${api}/users/current/accounts/${accountPath}/positions`);
    const after = (Array.isArray(afterRaw) ? afterRaw : []) as Position[];
    const filled = after.filter(p => p.symbol === SYMBOL && (Number(p.magic) === MAGIC || p.clientId === CLIENT_ID)).sort((a, b) => new Date(String(b.time ?? 0)).getTime() - new Date(String(a.time ?? 0)).getTime())[0];
    const entry = Number(filled?.openPrice ?? currentPrice);
    const stop = direction === 'BUY' ? entry - TRAIL_DISTANCE : entry + TRAIL_DISTANCE;
    if (entry > 0) await trade(api, accountId, { actionType: direction === 'BUY' ? 'ORDER_TYPE_SELL_STOP' : 'ORDER_TYPE_BUY_STOP', symbol: SYMBOL, volume: Number(filled?.volume ?? 0.01), openPrice: Number(stop.toFixed(2)), magic: MAGIC, clientId: CLIENT_ID, comment: 'PipsLife002' });
    return { configured: true, state: 'RUNNING', strategy: '002', activity: `TRADE EXECUTED: ${direction} 0.01 ${SYMBOL} at ${entry.toFixed(2)} · opposite stop ${stop.toFixed(2)} · live delta ${delta.toFixed(5)}`, positionCount: 1, pendingCount: 1 };
  }

  return { configured: true, state: 'RUNNING', strategy: '002', activity: `LIVE MARKET ${SYMBOL}: ${currentPrice > 0 ? currentPrice.toFixed(2) : 'no tick'} · delta ${delta.toFixed(5)} · ${historical.length} history ticks loaded · waiting for next directional tick`, positionCount: managedPositions.length, pendingCount: managedOrders.length };
}

export async function GET(request: Request) {
  const accountId = new URL(request.url).searchParams.get('accountId')?.trim();
  if (!accountId) return NextResponse.json({ error: 'accountId is required' }, { status: 400 });
  try {
    verifyAccountSession(request, accountId);
    const saved = await getState(accountId);
    if (!saved) return NextResponse.json({ configured: true, state: 'READY', strategy: '001', activity: 'Vercel bot engine ready' }, { headers: { 'cache-control': 'no-store' } });
    if (!saved.running) return NextResponse.json({ configured: true, state: 'SELECTED', strategy: saved.strategy, activity: `Strategy ${saved.strategy} selected; trading stopped.` }, { headers: { 'cache-control': 'no-store' } });
    const { account, api } = await accountInfo(accountId);
    if (saved.strategy === '002') return NextResponse.json(await execute002(accountId, api), { headers: { 'cache-control': 'no-store' } });
    return NextResponse.json({ configured: true, state: 'RUNNING', strategy: saved.strategy, activity: `Strategy ${saved.strategy} running on ${account.server ?? SYMBOL}` }, { headers: { 'cache-control': 'no-store' } });
  } catch (e) { if (e instanceof Response) return e; return NextResponse.json({ configured: true, state: 'ERROR', strategy: '002', error: e instanceof Error ? e.message : 'Bot execution failed' }, { status: 502, headers: { 'cache-control': 'no-store' } }); }
}

export async function POST(request: Request) {
  let requestedStrategy: string | undefined;
  try {
    const body = await request.json() as { action?: string; strategy?: string; accountId?: string };
    const accountId = body.accountId?.trim();
    if (!accountId) return NextResponse.json({ error: 'accountId is required' }, { status: 400 });
    verifyAccountSession(request, accountId);
    const rawAction = String(body.action ?? '').trim().toLowerCase();
    const selection = rawAction.match(/^select:(.+)$/);
    let action = rawAction;
    requestedStrategy = String(body.strategy ?? '').trim() || undefined;
    if (selection) { action = 'select'; requestedStrategy = selection[1].trim(); }
    if (!['select', 'start', 'stop'].includes(action)) return NextResponse.json({ error: 'unsupported action' }, { status: 400 });
    if (requestedStrategy && !['001', '002'].includes(requestedStrategy)) return NextResponse.json({ error: 'strategy must be 001 or 002' }, { status: 400 });
    const existing = await getState(accountId);
    const strategy = requestedStrategy ?? existing?.strategy ?? '001';
    if (action === 'select') { await saveState(accountId, false, strategy); return NextResponse.json({ configured: true, state: 'SELECTED', strategy, activity: `Strategy ${strategy} selected in Vercel Bot Engine.` }, { headers: { 'cache-control': 'no-store' } }); }
    if (action === 'stop') { await saveState(accountId, false, strategy); return NextResponse.json({ configured: true, state: 'STOPPED', strategy, activity: 'Trading stopped. Existing positions are left untouched.' }, { headers: { 'cache-control': 'no-store' } }); }
    await saveState(accountId, true, strategy);
    if (strategy === '002') { const { api } = await accountInfo(accountId); return NextResponse.json(await execute002(accountId, api), { headers: { 'cache-control': 'no-store' } }); }
    return NextResponse.json({ configured: true, state: 'RUNNING', strategy, activity: `Strategy ${strategy} running in Vercel Bot Engine.` }, { headers: { 'cache-control': 'no-store' } });
  } catch (e) { if (e instanceof Response) return e; return NextResponse.json({ configured: true, state: 'ERROR', strategy: requestedStrategy ?? '001', error: e instanceof Error ? e.message : 'Bot control failed' }, { status: 502, headers: { 'cache-control': 'no-store' } }); }
}

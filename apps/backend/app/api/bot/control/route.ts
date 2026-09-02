import { NextResponse } from 'next/server';
import { getCache } from '@vercel/functions';
import { verifyAccountSession } from '@/lib/session';

const PROVISIONING = process.env.METAAPI_PROVISIONING_URL ?? 'https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai';
const CLIENT_DEFAULT = process.env.METAAPI_CLIENT_API_URL ?? 'https://mt-client-api-v1.new-york.agiliumtrade.ai';
const SYMBOL = process.env.PIPSLIFE_SYMBOL?.trim() || 'XAUUSD';
const MAGIC = 100002;
const CLIENT_ID = 'PIPS002';
const PIP_SIZE = 0.01;
const TRAIL_PIPS = 100;
const TRAIL_DISTANCE = PIP_SIZE * TRAIL_PIPS;
// Strategy 002 is intentionally momentum-sensitive: any genuine non-zero
// directional tick can trigger an entry. The historical feed is used only to
// establish direction immediately; it is not a restrictive velocity baseline.
const DIRECTION_EPSILON = 1e-9;
const EXECUTION_MODE = (process.env.PIPSLIFE_EXECUTION_MODE ?? 'demo').trim().toLowerCase();

export const runtime = 'nodejs';
export const maxDuration = 300;

type Account = { _id?: string; login?: string | number; name?: string; server?: string; region?: string; state?: string; connectionStatus?: string; tags?: string[] };
type Position = { id?: string; type?: string; symbol?: string; volume?: number; openPrice?: number; time?: string; clientId?: string; magic?: number };
type Order = { id?: string; type?: string; symbol?: string; volume?: number; openPrice?: number; time?: string; clientId?: string; magic?: number; state?: string };
type Tick = { time?: string; bid?: number; ask?: number; last?: number };
type RunnerState = { configured: boolean; state: string; strategy: string; activity: string; error?: string; positionCount?: number; pendingCount?: number };

function cacheKey(accountId: string) { return `pipslife:bot:${accountId}`; }
async function state(accountId: string) { const value = await getCache().get(cacheKey(accountId)); return value as { running: boolean; strategy: string } | null; }
async function saveState(accountId: string, running: boolean, strategy: string) {
  await getCache().set(cacheKey(accountId), { running, strategy }, { ttl: 86400, tags: [`pipslife-bot-${accountId}`], name: `bot ${accountId}` });
}

async function readJson(response: Response): Promise<any> { const text = await response.text(); try { return text ? JSON.parse(text) : null; } catch { return { error: text }; } }
async function metaFetch(url: string, init?: RequestInit) {
  const token = process.env.METAAPI_TOKEN?.trim();
  if (!token) throw new Error('METAAPI_TOKEN is not configured');
  const response = await fetch(url, { ...init, headers: { accept: 'application/json', ...(init?.body ? { 'content-type': 'application/json' } : {}), 'auth-token': token, ...(init?.headers ?? {}) }, cache: 'no-store' });
  const data = await readJson(response); if (!response.ok) throw new Error(String(data?.message ?? data?.error ?? `MetaApi request failed (${response.status})`)); return data;
}
async function accountInfo(accountId: string): Promise<{ account: Account; api: string }> {
  const account = await metaFetch(`${PROVISIONING}/users/current/accounts/${encodeURIComponent(accountId)}`) as Account;
  const api = account.region ? `https://mt-client-api-v1.${account.region}.agiliumtrade.ai` : CLIENT_DEFAULT; return { account, api };
}
function normaliseSide(value: unknown): 'BUY' | 'SELL' | undefined { const v = String(value ?? '').toUpperCase(); if (v.includes('BUY')) return 'BUY'; if (v.includes('SELL')) return 'SELL'; return undefined; }
function priceFromTick(t: Tick) { const bid = Number(t.bid ?? 0), ask = Number(t.ask ?? 0), last = Number(t.last ?? 0); return bid > 0 && ask > 0 ? (bid + ask) / 2 : last; }
function tickArray(raw: any): Tick[] { if (Array.isArray(raw)) return raw as Tick[]; if (Array.isArray(raw?.ticks)) return raw.ticks as Tick[]; if (Array.isArray(raw?.items)) return raw.items as Tick[]; return []; }
function analyseTicks(ticks: Tick[]) {
  const samples = ticks.map(t => ({ t: new Date(String(t.time ?? '')).getTime() / 1000, p: priceFromTick(t) })).filter(x => Number.isFinite(x.t) && x.p > 0).sort((a, b) => a.t - b.t);
  if (samples.length < 2) return { expanding: false, reason: `waiting for live directional tick (${samples.length}/2)`, price: samples.at(-1)?.p ?? 0 };
  const previous = samples.at(-2)!.p;
  const current = samples.at(-1)!.p;
  const delta = current - previous;
  const direction = delta > DIRECTION_EPSILON ? 'BUY' : delta < -DIRECTION_EPSILON ? 'SELL' : 'WAIT';
  return { expanding: direction !== 'WAIT', direction, confidence: direction === 'WAIT' ? 0 : 100, price: current, delta, sampleCount: samples.length };
}
async function trade(api: string, accountId: string, body: Record<string, unknown>) { return await metaFetch(`${api}/users/current/accounts/${encodeURIComponent(accountId)}/trade`, { method: 'POST', body: JSON.stringify(body) }); }

async function execute002(accountId: string, api: string): Promise<RunnerState> {
  if (EXECUTION_MODE !== 'demo') return { configured: true, state: 'READY', strategy: '002', activity: `Execution locked: PIPSLIFE_EXECUTION_MODE=${EXECUTION_MODE}; demo mode is required for this test build.` };
  const region = api.match(/mt-client-api-v1\.([^.]+)\.agiliumtrade\.ai/)?.[1] ?? 'new-york'; const marketApi = `https://mt-market-data-client-api-v1.${region}.agiliumtrade.ai`;
  const [info, positionsRaw, ordersRaw, ticksRaw] = await Promise.all([
    metaFetch(`${api}/users/current/accounts/${encodeURIComponent(accountId)}/account-information`),
    metaFetch(`${api}/users/current/accounts/${encodeURIComponent(accountId)}/positions`),
    metaFetch(`${api}/users/current/accounts/${encodeURIComponent(accountId)}/orders`),
    metaFetch(`${marketApi}/users/current/accounts/${encodeURIComponent(accountId)}/historical-market-data/symbols/${encodeURIComponent(SYMBOL)}/ticks?limit=64`)
  ]);
  if (info?.tradeAllowed === false) return { configured: true, state: 'ERROR', strategy: '002', activity: 'MT5 account does not allow trading.' };
  const positions = (Array.isArray(positionsRaw) ? positionsRaw : []) as Position[]; const orders = (Array.isArray(ordersRaw) ? ordersRaw : []) as Order[]; const ticks = tickArray(ticksRaw);
  const managedPositions = positions.filter(p => p.symbol === SYMBOL && (Number(p.magic) === MAGIC || p.clientId === CLIENT_ID)); const managedOrders = orders.filter(o => o.symbol === SYMBOL && (Number(o.magic) === MAGIC || o.clientId === CLIENT_ID)); const analysis = analyseTicks(ticks);
  if (managedPositions.length > 1) { const sorted = [...managedPositions].sort((a, b) => new Date(String(a.time ?? 0)).getTime() - new Date(String(b.time ?? 0)).getTime()); for (const old of sorted.slice(0, -1)) if (old.id) await trade(api, accountId, { actionType: 'POSITION_CLOSE_ID', positionId: old.id, magic: MAGIC, clientId: CLIENT_ID, comment: 'PipsLife002' }); }
  const livePosition = [...managedPositions].sort((a, b) => new Date(String(b.time ?? 0)).getTime() - new Date(String(a.time ?? 0)).getTime())[0]; const liveSide = normaliseSide(livePosition?.type); const pending = managedOrders.find(o => normaliseSide(o.type) === (liveSide === 'BUY' ? 'SELL' : liveSide === 'SELL' ? 'BUY' : undefined));
  if (livePosition && !pending) { const entry = Number(livePosition.openPrice ?? analysis.price); const opposite = liveSide === 'BUY' ? 'ORDER_TYPE_SELL_STOP' : 'ORDER_TYPE_BUY_STOP'; const stopPrice = liveSide === 'BUY' ? entry - TRAIL_DISTANCE : entry + TRAIL_DISTANCE; await trade(api, accountId, { actionType: opposite, symbol: SYMBOL, volume: Number(livePosition.volume ?? 0.01), openPrice: Number(stopPrice.toFixed(2)), magic: MAGIC, clientId: CLIENT_ID, comment: 'PipsLife002' }); return { configured: true, state: 'RUNNING', strategy: '002', activity: `Strategy 002 running: ${liveSide} ${livePosition.volume ?? 0.01} with ${TRAIL_PIPS}-pip opposite stop at ${stopPrice.toFixed(2)}`, positionCount: managedPositions.length, pendingCount: 1 }; }
  if (!livePosition && analysis.expanding && (analysis.direction === 'BUY' || analysis.direction === 'SELL') && analysis.price > 0) {
    const side = analysis.direction; const actionType = side === 'BUY' ? 'ORDER_TYPE_BUY' : 'ORDER_TYPE_SELL';
    await trade(api, accountId, { actionType, symbol: SYMBOL, volume: 0.01, magic: MAGIC, clientId: CLIENT_ID, comment: 'PipsLife002' });
    // Re-read positions so the 100-pip stop is anchored to the broker-confirmed fill price.
    const afterRaw = await metaFetch(`${api}/users/current/accounts/${encodeURIComponent(accountId)}/positions`); const after = (Array.isArray(afterRaw) ? afterRaw : []) as Position[];
    const filled = after.filter(p => p.symbol === SYMBOL && (Number(p.magic) === MAGIC || p.clientId === CLIENT_ID)).sort((a, b) => new Date(String(b.time ?? 0)).getTime() - new Date(String(a.time ?? 0)).getTime())[0];
    const actualEntry = Number(filled?.openPrice ?? analysis.price); const stopPrice = side === 'BUY' ? actualEntry - TRAIL_DISTANCE : actualEntry + TRAIL_DISTANCE;
    await trade(api, accountId, { actionType: side === 'BUY' ? 'ORDER_TYPE_SELL_STOP' : 'ORDER_TYPE_BUY_STOP', symbol: SYMBOL, volume: Number(filled?.volume ?? 0.01), openPrice: Number(stopPrice.toFixed(2)), magic: MAGIC, clientId: CLIENT_ID, comment: 'PipsLife002' });
    return { configured: true, state: 'RUNNING', strategy: '002', activity: `TRADE EXECUTED: ${side} 0.01 ${SYMBOL} at ${actualEntry.toFixed(2)}; opposite stop ${stopPrice.toFixed(2)}.`, positionCount: 1, pendingCount: 1 };
  }
  const wait = analysis.expanding ? `direction ${analysis.direction} detected (${analysis.delta?.toFixed(5)} price change)` : analysis.reason ?? 'waiting for directional movement'; return { configured: true, state: 'RUNNING', strategy: '002', activity: `Strategy 002 monitoring ${SYMBOL}: ${wait}`, positionCount: managedPositions.length, pendingCount: managedOrders.length };
}

export async function GET(request: Request) {
  const accountId = new URL(request.url).searchParams.get('accountId')?.trim(); if (!accountId) return NextResponse.json({ error: 'accountId is required' }, { status: 400 });
  try { verifyAccountSession(request, accountId); const saved = await state(accountId); if (!saved) return NextResponse.json({ configured: true, state: 'READY', strategy: '001', activity: 'Vercel bot engine ready' }, { headers: { 'cache-control': 'no-store' } }); if (!saved.running) return NextResponse.json({ configured: true, state: 'SELECTED', strategy: saved.strategy, activity: `Strategy ${saved.strategy} selected; trading stopped.` }, { headers: { 'cache-control': 'no-store' } }); const { account, api } = await accountInfo(accountId); if (saved.strategy === '002') return NextResponse.json(await execute002(accountId, api), { headers: { 'cache-control': 'no-store' } }); return NextResponse.json({ configured: true, state: 'RUNNING', strategy: saved.strategy, activity: `Strategy ${saved.strategy} running on ${account.server ?? SYMBOL}` }, { headers: { 'cache-control': 'no-store' } }); }
  catch (e) { if (e instanceof Response) return e; return NextResponse.json({ configured: true, state: 'ERROR', strategy: '002', error: e instanceof Error ? e.message : 'Bot execution failed' }, { status: 502, headers: { 'cache-control': 'no-store' } }); }
}

export async function POST(request: Request) {
  let requestedStrategy: string | undefined;
  try { const body = await request.json() as { action?: string; strategy?: string; accountId?: string }; const accountId = body.accountId?.trim(); if (!accountId) return NextResponse.json({ error: 'accountId is required' }, { status: 400 }); verifyAccountSession(request, accountId); const rawAction = String(body.action ?? '').trim().toLowerCase(); const selection = rawAction.match(/^select:(.+)$/); let action = rawAction; requestedStrategy = String(body.strategy ?? '').trim() || undefined; if (selection) { action = 'select'; requestedStrategy = selection[1].trim(); } if (!['select', 'start', 'stop'].includes(action)) return NextResponse.json({ error: 'unsupported action' }, { status: 400 }); if (requestedStrategy && !['001', '002'].includes(requestedStrategy)) return NextResponse.json({ error: 'strategy must be 001 or 002' }, { status: 400 }); const existing = await state(accountId); const strategy = requestedStrategy ?? existing?.strategy ?? '001'; if (action === 'select') { await saveState(accountId, false, strategy); return NextResponse.json({ configured: true, state: 'SELECTED', strategy, activity: `Strategy ${strategy} selected in Vercel Bot Engine.` }, { headers: { 'cache-control': 'no-store' } }); } if (action === 'stop') { await saveState(accountId, false, strategy); return NextResponse.json({ configured: true, state: 'STOPPED', strategy, activity: 'Trading stopped. Existing positions are left untouched.' }, { headers: { 'cache-control': 'no-store' } }); } await saveState(accountId, true, strategy); if (strategy === '002') { const { api } = await accountInfo(accountId); return NextResponse.json(await execute002(accountId, api), { headers: { 'cache-control': 'no-store' } }); } return NextResponse.json({ configured: true, state: 'RUNNING', strategy, activity: `Strategy ${strategy} running in Vercel Bot Engine.` }, { headers: { 'cache-control': 'no-store' } }); }
  catch (e) { if (e instanceof Response) return e; return NextResponse.json({ configured: true, state: 'ERROR', strategy: requestedStrategy ?? '001', error: e instanceof Error ? e.message : 'Bot control failed' }, { status: 502, headers: { 'cache-control': 'no-store' } }); }
}

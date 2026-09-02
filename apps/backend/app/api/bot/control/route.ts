import { NextResponse } from 'next/server';
import { getCache } from '@vercel/functions';
import { start, getRun } from 'workflow/api';
import { verifyAccountSession } from '@/lib/session';
import { strategy002Loop } from '@/workflows/strategy002-loop';
import { executeStrategy002 } from '@/lib/strategy002-engine';

export const runtime = 'nodejs';
export const maxDuration = 300;

type BotState = { running: boolean; strategy: string; runId?: string; loopToken?: string };
const key = (accountId: string) => `pipslife:bot:${accountId}`;
const readState = async (accountId: string) => await getCache().get(key(accountId)) as BotState | null;
const writeState = async (accountId: string, running: boolean, strategy: string, runId?: string, loopToken?: string) => {
  await getCache().set(key(accountId), { running, strategy, ...(runId ? { runId } : {}), ...(loopToken ? { loopToken } : {}) }, { ttl: 86400, tags: [`pipslife-bot-${accountId}`], name: `bot ${accountId}` });
};

async function ensureLoop(accountId: string) {
  const state = await readState(accountId);
  if (state?.runId && state.loopToken) {
    try {
      const status = await getRun(state.runId).status;
      if (status === 'running' || status === 'pending') return state.runId;
    } catch {}
  }
  const loopToken = crypto.randomUUID();
  await writeState(accountId, true, '002', undefined, loopToken);
  const run = await start(strategy002Loop, [accountId, loopToken]);
  await writeState(accountId, true, '002', run.runId, loopToken);
  return run.runId;
}

async function run002(accountId: string) {
  return executeStrategy002(accountId, () => ensureLoop(accountId));
}

export async function GET(request: Request) {
  const accountId = new URL(request.url).searchParams.get('accountId')?.trim();
  if (!accountId) return NextResponse.json({ error: 'accountId is required' }, { status: 400 });
  const token = process.env.METAAPI_TOKEN?.trim();
  const internal = Boolean(token) && request.headers.get('x-pipslife-workflow') === token;
  try {
    if (!internal) verifyAccountSession(request, accountId);
    const state = await readState(accountId);
    if (!state) return NextResponse.json({ configured: true, state: 'READY', strategy: '001', activity: 'Vercel bot engine ready' });
    if (!state.running) return NextResponse.json({ configured: true, state: 'SELECTED', strategy: state.strategy, activity: `Strategy ${state.strategy} selected; trading stopped.` });
    if (state.strategy === '002') return NextResponse.json(await run002(accountId), { headers: { 'cache-control': 'no-store' } });
    return NextResponse.json({ configured: true, state: 'RUNNING', strategy: state.strategy, activity: `Strategy ${state.strategy} running in Vercel Bot Engine.` });
  } catch (e) {
    if (e instanceof Response) return e;
    return NextResponse.json({ configured: true, state: 'ERROR', strategy: '002', error: e instanceof Error ? e.message : 'Bot execution failed' }, { status: 502 });
  }
}

export async function POST(request: Request) {
  try {
    const body = await request.json() as { action?: string; strategy?: string; accountId?: string };
    const accountId = body.accountId?.trim();
    if (!accountId) return NextResponse.json({ error: 'accountId is required' }, { status: 400 });
    verifyAccountSession(request, accountId);
    let action = String(body.action ?? '').trim().toLowerCase();
    let strategy = String(body.strategy ?? '').trim() || (await readState(accountId))?.strategy || '001';
    const match = action.match(/^select:(.+)$/);
    if (match) { action = 'select'; strategy = match[1].trim(); }
    if (!['001', '002'].includes(strategy)) return NextResponse.json({ error: 'strategy must be 001 or 002' }, { status: 400 });
    if (!['select', 'start', 'stop'].includes(action)) return NextResponse.json({ error: 'unsupported action' }, { status: 400 });
    if (action === 'select') {
      await writeState(accountId, false, strategy);
      return NextResponse.json({ configured: true, state: 'SELECTED', strategy, activity: `Strategy ${strategy} selected in Vercel Bot Engine.` });
    }
    if (action === 'stop') {
      await writeState(accountId, false, strategy);
      return NextResponse.json({ configured: true, state: 'STOPPED', strategy, activity: 'Trading stopped. Existing positions are left untouched.' });
    }
    await writeState(accountId, true, strategy);
    if (strategy === '002') return NextResponse.json(await run002(accountId), { headers: { 'cache-control': 'no-store' } });
    return NextResponse.json({ configured: true, state: 'RUNNING', strategy, activity: `Strategy ${strategy} running in Vercel Bot Engine.` });
  } catch (e) {
    if (e instanceof Response) return e;
    return NextResponse.json({ configured: true, state: 'ERROR', strategy: '002', error: e instanceof Error ? e.message : 'Bot control failed' }, { status: 502 });
  }
}

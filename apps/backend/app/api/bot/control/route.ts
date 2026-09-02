import { NextResponse, after } from 'next/server';
import { verifyAccountSession } from '@/lib/session';
import { startStrategy002Stream, stopStrategy002Stream, strategy002StreamPromise } from '@/lib/strategy002-stream';

export const runtime = 'nodejs';
export const maxDuration = 300;

type BotState = { running: boolean; strategy: string };
const botStates = new Map<string, BotState>();
const readState = async (accountId: string) => botStates.get(accountId) ?? null;
const writeState = async (accountId: string, running: boolean, strategy: string) => {
  botStates.set(accountId, { running, strategy });
};

async function runStream(accountId: string) {
  const result = await startStrategy002Stream(accountId);
  const promise = strategy002StreamPromise(accountId);
  if (promise) after(() => promise);
  return result;
}

export async function GET(request: Request) {
  const accountId = new URL(request.url).searchParams.get('accountId')?.trim();
  if (!accountId) return NextResponse.json({ error: 'accountId is required' }, { status: 400 });

  try {
    verifyAccountSession(request, accountId);
    const state = await readState(accountId);
    if (!state) return NextResponse.json({ configured: true, state: 'READY', strategy: '001', activity: 'Vercel bot engine ready' });
    if (!state.running) return NextResponse.json({ configured: true, state: 'SELECTED', strategy: state.strategy, activity: `Strategy ${state.strategy} selected; trading stopped.` });
    if (state.strategy === '002') {
      const result = await runStream(accountId);
      return NextResponse.json({ configured: true, ...result }, { headers: { 'cache-control': 'no-store' } });
    }
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
      if (strategy !== '002') await stopStrategy002Stream(accountId);
      await writeState(accountId, false, strategy);
      return NextResponse.json({ configured: true, state: 'SELECTED', strategy, activity: `Strategy ${strategy} selected in Bot Engine.` });
    }

    if (action === 'stop') {
      await stopStrategy002Stream(accountId);
      await writeState(accountId, false, strategy);
      return NextResponse.json({ configured: true, state: 'STOPPED', strategy, activity: 'Trading stopped. Existing positions are left untouched.' });
    }

    await writeState(accountId, true, strategy);
    if (strategy === '002') {
      const result = await runStream(accountId);
      return NextResponse.json({ configured: true, ...result }, { headers: { 'cache-control': 'no-store' } });
    }
    return NextResponse.json({ configured: true, state: 'RUNNING', strategy, activity: `Strategy ${strategy} running in Bot Engine.` });
  } catch (e) {
    if (e instanceof Response) return e;
    return NextResponse.json({ configured: true, state: 'ERROR', strategy: '002', error: e instanceof Error ? e.message : 'Bot control failed' }, { status: 502 });
  }
}

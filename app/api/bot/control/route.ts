import { NextResponse } from 'next/server';
import { verifyAccountSession } from '@/lib/session';

const control = () => process.env.PIPSLIFE_RUNNER_URL?.trim() || process.env.PIPSLIFE_BOT_CONTROL_URL?.trim();
export const runtime = 'nodejs';

type RunnerState = { configured?: boolean; state?: string; strategy?: string; activity?: string; error?: string };

async function currentRunnerState(url: string, accountId?: string): Promise<RunnerState | null> {
  try {
    const target = accountId ? `${url}${url.includes('?') ? '&' : '?'}accountId=${encodeURIComponent(accountId)}` : url;
    const r = await fetch(target, { headers: { accept: 'application/json' }, cache: 'no-store' });
    if (!r.ok) return null;
    const data = await r.json().catch(() => null);
    return data && typeof data === 'object' ? data as RunnerState : null;
  } catch {
    return null;
  }
}

const validStrategy = (value: unknown): value is '001' | '002' | '003' => value === '001' || value === '002' || value === '003';

export async function GET(request: Request) {
  const url = control();
  if (!url) return NextResponse.json({ configured: false, state: 'RUNNER_NOT_CONFIGURED', strategy: '001', activity: 'Bot runner control is not configured' }, { status: 503 });
  const accountId = new URL(request.url).searchParams.get('accountId')?.trim();
  if (!accountId) return NextResponse.json({ error: 'accountId is required' }, { status: 400 });
  try { verifyAccountSession(request, accountId); } catch { return NextResponse.json({ error: 'Unauthorized' }, { status: 401 }); }
  const state = await currentRunnerState(url, accountId);
  const strategy = validStrategy(state?.strategy) ? state!.strategy : '001';
  return NextResponse.json({ configured: true, state: state?.state ?? 'UNKNOWN', strategy, activity: state?.activity ?? 'Bot runner control online' }, { headers: { 'cache-control': 'no-store' } });
}

export async function POST(request: Request) {
  const url = control();
  if (!url) return NextResponse.json({ configured: false, state: 'RUNNER_NOT_CONFIGURED', strategy: '001', error: 'PIPSLIFE_RUNNER_URL is not configured' }, { status: 503 });
  try {
    const body = await request.json() as { action?: string; strategy?: string; accountId?: string };
    const rawAction = String(body.action ?? '').trim().toLowerCase();
    if (!rawAction) return NextResponse.json({ error: 'action is required' }, { status: 400 });
    const accountId = String(body.accountId ?? '').trim();
    if (!accountId) return NextResponse.json({ error: 'accountId is required' }, { status: 400 });
    verifyAccountSession(request, accountId);

    let action = rawAction;
    let strategy = validStrategy(body.strategy) ? body.strategy : undefined;
    const selection = rawAction.match(/^select:(001|002|003)$/);
    if (selection) { action = 'select'; strategy = selection[1]; }

    if (!strategy && (action === 'start' || action === 'stop')) {
      const current = await currentRunnerState(url, accountId);
      strategy = validStrategy(current?.strategy) ? current.strategy : '001';
    }

    const payload: Record<string, unknown> = { action, accountId };
    if (strategy) payload.strategy = strategy;

    const runnerToken = process.env.PIPSLIFE_BOT_CONTROL_TOKEN?.trim()
      || process.env.METAAPI_TOKEN?.trim()
      || process.env.META_API_TOKEN?.trim()
      || process.env.METAAPI_KEY?.trim()
      || process.env.META_API_KEY?.trim();
    const r = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...(runnerToken ? { authorization: `Bearer ${runnerToken}` } : {}) },
      body: JSON.stringify(payload)
    });
    const data = await r.json().catch(() => ({}));
    const response = data && typeof data === 'object' ? data as RunnerState : {};
    return NextResponse.json({ ...response, strategy: validStrategy(response.strategy) ? response.strategy : strategy ?? '001' }, { status: r.status });
  } catch (e) {
    const status = e instanceof Response && e.status === 401 ? 401 : 502;
    return NextResponse.json({ configured: true, state: 'ERROR', strategy: '001', error: status === 401 ? 'Unauthorized' : (e instanceof Error ? e.message : 'Bot control failed') }, { status });
  }
}
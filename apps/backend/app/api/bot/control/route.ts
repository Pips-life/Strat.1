import { NextResponse } from 'next/server';

const control = () => process.env.PIPSLIFE_BOT_CONTROL_URL;
export const runtime = 'nodejs';

type RunnerState = {
  configured?: boolean;
  state?: string;
  strategy?: string;
  activity?: string;
  error?: string;
};

export async function GET(request: Request) {
  const url = control();
  if (!url) {
    return NextResponse.json(
      { configured: false, state: 'RUNNER_NOT_CONFIGURED', strategy: '001', activity: 'Bot runner control not configured' },
      { status: 503 }
    );
  }

  try {
    const accountId = new URL(request.url).searchParams.get('accountId')?.trim();
    const target = accountId
      ? `${url}${url.includes('?') ? '&' : '?'}accountId=${encodeURIComponent(accountId)}`
      : url;
    const r = await fetch(target, { headers: { accept: 'application/json' }, cache: 'no-store' });
    const data = await r.json().catch(() => ({}));
    const state = data && typeof data === 'object' ? data as RunnerState : {};
    return NextResponse.json({
      ...state,
      configured: true,
      strategy: state.strategy === '002' ? '002' : state.strategy === '001' ? '001' : '001'
    }, { status: r.ok ? 200 : r.status, headers: { 'cache-control': 'no-store' } });
  } catch (e) {
    return NextResponse.json(
      { configured: true, state: 'ERROR', strategy: '001', error: e instanceof Error ? e.message : 'Bot control failed' },
      { status: 502 }
    );
  }
}

export async function POST(request: Request) {
  const url = control();
  if (!url) {
    return NextResponse.json(
      { configured: false, state: 'RUNNER_NOT_CONFIGURED', strategy: '001', error: 'PIPSLIFE_BOT_CONTROL_URL is not configured' },
      { status: 503 }
    );
  }

  try {
    const body = await request.json() as { action?: string; strategy?: string; accountId?: string };
    const rawAction = String(body.action ?? '').trim().toLowerCase();
    if (!rawAction) return NextResponse.json({ error: 'action is required' }, { status: 400 });

    let action = rawAction;
    let strategy = body.strategy === '002' ? '002' : body.strategy === '001' ? '001' : undefined;
    const selection = rawAction.match(/^select:(001|002)$/);
    if (selection) {
      action = 'select';
      strategy = selection[1];
    }

    const payload: Record<string, unknown> = { action, accountId: body.accountId };
    if (strategy) payload.strategy = strategy;

    const r = await fetch(url, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        ...(process.env.PIPSLIFE_BOT_CONTROL_TOKEN ? { authorization: `Bearer ${process.env.PIPSLIFE_BOT_CONTROL_TOKEN}` } : {})
      },
      body: JSON.stringify(payload)
    });
    const data = await r.json().catch(() => ({}));
    const response = data && typeof data === 'object' ? data as RunnerState : {};

    return NextResponse.json(
      { ...response, strategy: response.strategy === '002' ? '002' : response.strategy === '001' ? '001' : strategy ?? '001' },
      { status: r.status }
    );
  } catch (e) {
    return NextResponse.json(
      { configured: true, state: 'ERROR', strategy: '001', error: e instanceof Error ? e.message : 'Bot control failed' },
      { status: 502 }
    );
  }
}

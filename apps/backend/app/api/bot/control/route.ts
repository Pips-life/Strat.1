import { NextResponse } from 'next/server';
import { verifyAccountSession } from '@/lib/session';

const BOT_CONTROL_URL = process.env.PIPSLIFE_BOT_CONTROL_URL?.trim();
const BOT_CONTROL_TOKEN = process.env.PIPSLIFE_BOT_CONTROL_TOKEN?.trim();

export async function GET(request: Request) {
  const accountId = new URL(request.url).searchParams.get('accountId')?.trim();
  if (accountId) { try { verifyAccountSession(request, accountId); } catch (error) { if (error instanceof Response) return error; throw error; } }
  if (!BOT_CONTROL_URL) return NextResponse.json({ configured: false, state: 'RUNNER_NOT_CONFIGURED', strategy: '001' });
  try {
    const response = await fetch(`${BOT_CONTROL_URL.replace(/\/$/, '')}/status`, { headers: BOT_CONTROL_TOKEN ? { authorization: `Bearer ${BOT_CONTROL_TOKEN}` } : {}, cache: 'no-store' });
    const body = await response.text();
    return new NextResponse(body, { status: response.status, headers: { 'content-type': response.headers.get('content-type') ?? 'application/json', 'cache-control': 'no-store' } });
  } catch (error) { return NextResponse.json({ configured: true, state: 'RUNNER_UNREACHABLE', error: error instanceof Error ? error.message : 'runner unavailable' }, { status: 502 }); }
}

export async function POST(request: Request) {
  if (!BOT_CONTROL_URL) return NextResponse.json({ configured: false, state: 'RUNNER_NOT_CONFIGURED', error: 'Strategy runner control endpoint is not configured' }, { status: 503 });
  try {
    const payload = await request.json() as { action?: 'start' | 'stop'; strategy?: string; accountId?: string };
    if (!payload.accountId) return NextResponse.json({ error: 'accountId is required' }, { status: 400 });
    verifyAccountSession(request, payload.accountId);
    if (payload.action !== 'start' && payload.action !== 'stop') return NextResponse.json({ error: 'action must be start or stop' }, { status: 400 });
    const response = await fetch(`${BOT_CONTROL_URL.replace(/\/$/, '')}/${payload.action}`, { method: 'POST', headers: { 'content-type': 'application/json', ...(BOT_CONTROL_TOKEN ? { authorization: `Bearer ${BOT_CONTROL_TOKEN}` } : {}) }, body: JSON.stringify({ strategy: payload.strategy ?? '001', accountId: payload.accountId }), cache: 'no-store' });
    const body = await response.text();
    return new NextResponse(body, { status: response.status, headers: { 'content-type': response.headers.get('content-type') ?? 'application/json', 'cache-control': 'no-store' } });
  } catch (error) { if (error instanceof Response) return error; return NextResponse.json({ error: error instanceof Error ? error.message : 'runner command failed' }, { status: 502 }); }
}

import { NextResponse } from 'next/server';
import { verifyAccountSession } from '@/lib/session';

export const runtime = 'nodejs';
export const maxDuration = 30;

const RUNNER_URL = process.env.PIPSLIFE_RUNNER_URL?.trim().replace(/\/$/, '');
const RUNNER_TOKEN = process.env.PIPSLIFE_BOT_CONTROL_TOKEN?.trim();

type ControlBody = { action?: string; strategy?: string; accountId?: string };

function runnerHeaders() {
  return {
    accept: 'application/json',
    'content-type': 'application/json',
    ...(RUNNER_TOKEN ? { authorization: `Bearer ${RUNNER_TOKEN}` } : {}),
  };
}

async function runnerRequest(path: string, init: RequestInit = {}) {
  if (!RUNNER_URL) throw new Error('PIPSLIFE_RUNNER_URL is not configured');
  const response = await fetch(`${RUNNER_URL}${path}`, {
    ...init,
    headers: { ...runnerHeaders(), ...(init.headers ?? {}) },
    cache: 'no-store',
  });
  const text = await response.text();
  let data: unknown = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { error: text }; }
  if (!response.ok) throw new Error(String((data as { detail?: string; error?: string } | null)?.detail ?? (data as { error?: string } | null)?.error ?? `Runner returned ${response.status}`));
  return data;
}

function validateStrategy(value: string) {
  if (!['001', '002'].includes(value)) throw new Error('strategy must be 001 or 002');
}

export async function GET(request: Request) {
  const accountId = new URL(request.url).searchParams.get('accountId')?.trim();
  if (!accountId) return NextResponse.json({ error: 'accountId is required' }, { status: 400 });
  try {
    verifyAccountSession(request, accountId);
    const data = await runnerRequest(`/?accountId=${encodeURIComponent(accountId)}`);
    return NextResponse.json(data, { headers: { 'cache-control': 'no-store' } });
  } catch (error) {
    if (error instanceof Response) return error;
    return NextResponse.json({ configured: true, state: 'ERROR', strategy: '001', error: error instanceof Error ? error.message : 'Runner unavailable' }, { status: 503 });
  }
}

export async function POST(request: Request) {
  let body: ControlBody;
  try { body = await request.json() as ControlBody; } catch { return NextResponse.json({ error: 'invalid JSON' }, { status: 400 }); }
  const accountId = body.accountId?.trim();
  if (!accountId) return NextResponse.json({ error: 'accountId is required' }, { status: 400 });
  try {
    verifyAccountSession(request, accountId);
    let action = String(body.action ?? '').trim().toLowerCase();
    let strategy = String(body.strategy ?? '').trim();
    const match = action.match(/^select:(.+)$/);
    if (match) { action = 'select'; strategy = match[1].trim(); }
    if (!['select', 'start', 'stop'].includes(action)) return NextResponse.json({ error: 'unsupported action' }, { status: 400 });
    if (action !== 'stop') {
      validateStrategy(strategy || '001');
      strategy = strategy || '001';
    }
    const data = await runnerRequest('/', { method: 'POST', body: JSON.stringify({ action, strategy: strategy || undefined, accountId }) });
    return NextResponse.json(data, { headers: { 'cache-control': 'no-store' } });
  } catch (error) {
    if (error instanceof Response) return error;
    return NextResponse.json({ configured: true, state: 'ERROR', strategy: strategy || '001', error: error instanceof Error ? error.message : 'Bot control failed' }, { status: 503 });
  }
}

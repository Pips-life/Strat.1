import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';

const schema = z.object({
  login: z.string().regex(/^\d+$/),
  password: z.string().min(1),
  server: z.string().min(1),
  name: z.string().min(1).max(80).default('Strat.1 MT5 account')
});

const METAAPI_ACCOUNTS = 'https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai/users/current/accounts';

export async function POST(request: NextRequest) {
  const token = process.env.METAAPI_TOKEN;
  if (!token) return NextResponse.json({ error: 'METAAPI_TOKEN is not configured' }, { status: 503 });

  const parsed = schema.safeParse(await request.json());
  if (!parsed.success) return NextResponse.json({ error: 'Invalid MT5 connection details' }, { status: 400 });

  const { login, password, server, name } = parsed.data;
  const transactionId = crypto.randomUUID().replaceAll('-', '').padEnd(32, '0').slice(0, 32);

  const upstream = await fetch(METAAPI_ACCOUNTS, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'auth-token': token, 'transaction-id': transactionId },
    body: JSON.stringify({ login, password, server, name, platform: 'mt5', magic: 0, type: 'cloud-g2', keywords: [server] }),
    cache: 'no-store'
  });

  const body = await upstream.text();
  if (!upstream.ok) return NextResponse.json({ error: 'MT5 connection validation failed', details: safeMetaApiError(body) }, { status: upstream.status });

  const result = JSON.parse(body) as { id?: string; state?: string };
  return NextResponse.json({ accountId: result.id ?? null, state: result.state ?? 'UNKNOWN', server }, { status: upstream.status });
}

function safeMetaApiError(body: string) {
  try {
    const parsed = JSON.parse(body) as Record<string, unknown>;
    return { error: parsed.error, message: parsed.message, details: parsed.details };
  } catch {
    return { message: 'MetaApi returned an unreadable error response' };
  }
}

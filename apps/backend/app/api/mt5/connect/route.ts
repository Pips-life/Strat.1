import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';

const schema = z.object({
  login: z.string().regex(/^\d+$/),
  password: z.string().min(1),
  server: z.string().min(1).max(120),
  broker: z.string().min(1).max(120).optional(),
  name: z.string().min(1).max(80).default('Strat.1 MT5 account')
});

const METAAPI_ACCOUNTS = 'https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai/users/current/accounts';

export async function POST(request: NextRequest) {
  const token = process.env.METAAPI_TOKEN;
  if (!token) return NextResponse.json({ error: 'METAAPI_TOKEN is not configured' }, { status: 503 });

  let input: unknown;
  try {
    input = await request.json();
  } catch {
    return NextResponse.json({ error: 'Invalid JSON request' }, { status: 400 });
  }

  const parsed = schema.safeParse(input);
  if (!parsed.success) return NextResponse.json({ error: 'Invalid MT5 connection details' }, { status: 400 });

  const { login, password, server, broker, name } = parsed.data;
  const transactionId = crypto.randomUUID().replaceAll('-', '').padEnd(32, '0').slice(0, 32);
  const keywords = broker ? [broker, server] : [server];

  try {
    const upstream = await fetch(METAAPI_ACCOUNTS, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        'auth-token': token,
        'transaction-id': transactionId
      },
      body: JSON.stringify({
        login,
        password,
        server,
        name,
        platform: 'mt5',
        magic: 0,
        type: 'cloud-g2',
        keywords
      }),
      cache: 'no-store'
    });

    const body = await upstream.text();
    const retryAfter = upstream.headers.get('retry-after');

    if (upstream.status === 202) {
      return NextResponse.json(
        { state: 'PROCESSING', message: 'MetaApi is validating the MT5 account. Retry after the supplied interval.' },
        { status: 202, headers: retryAfter ? { 'retry-after': retryAfter } : undefined }
      );
    }

    if (!upstream.ok) {
      return NextResponse.json(
        { error: 'MT5 connection validation failed', details: safeMetaApiError(body) },
        { status: upstream.status }
      );
    }

    const result = JSON.parse(body) as { id?: string; state?: string };
    return NextResponse.json({
      accountId: result.id ?? null,
      state: result.state ?? 'UNKNOWN',
      server
    });
  } catch {
    return NextResponse.json({ error: 'Unable to reach MetaApi account provisioning service' }, { status: 502 });
  }
}

function safeMetaApiError(body: string) {
  try {
    const parsed = JSON.parse(body) as Record<string, unknown>;
    return { error: parsed.error, message: parsed.message, details: parsed.details };
  } catch {
    return { message: 'MetaApi returned an unreadable error response' };
  }
}

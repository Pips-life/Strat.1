import { NextResponse } from 'next/server';
import { z } from 'zod';
import { requireBackendKey } from '@/lib/auth';

const bodySchema = z.object({
  login: z.string().regex(/^\d+$/),
  password: z.string().min(1),
  server: z.string().trim().min(1).max(150),
  name: z.string().trim().min(1).max(100).default('Strat.1 MT5 account')
});

const METAAPI_PROVISIONING_URL = 'https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai';

export async function POST(request: Request) {
  try {
    requireBackendKey(request);
    const token = process.env.METAAPI_TOKEN;
    if (!token) return NextResponse.json({ error: 'MetaApi token is not configured' }, { status: 503 });

    const parsed = bodySchema.safeParse(await request.json());
    if (!parsed.success) return NextResponse.json({ error: 'Invalid MT5 connection payload' }, { status: 400 });

    const response = await fetch(`${METAAPI_PROVISIONING_URL}/users/current/accounts`, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'auth-token': token,
        'transaction-id': crypto.randomUUID().replaceAll('-', '')
      },
      body: JSON.stringify({
        login: parsed.data.login,
        password: parsed.data.password,
        name: parsed.data.name,
        server: parsed.data.server,
        platform: 'mt5',
        magic: 0,
        type: 'cloud-g2'
      }),
      cache: 'no-store'
    });

    const body = await response.text();
    let payload: unknown;
    try { payload = JSON.parse(body); } catch { payload = { message: body }; }

    if (!response.ok) {
      return NextResponse.json(payload, { status: response.status >= 500 ? 502 : response.status });
    }

    const result = payload as { id?: string; state?: string };
    return NextResponse.json({
      accountId: result.id ?? null,
      state: result.state ?? 'UNKNOWN',
      server: parsed.data.server
    }, { status: response.status });
  } catch (error) {
    if (error instanceof Response) return error;
    return NextResponse.json({ error: 'Unable to connect MT5 account' }, { status: 502 });
  }
}

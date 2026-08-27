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
  try { input = await request.json(); }
  catch { return NextResponse.json({ error: 'Invalid JSON request' }, { status: 400 }); }

  const parsed = schema.safeParse(input);
  if (!parsed.success) return NextResponse.json({ error: 'Invalid MT5 connection details' }, { status: 400 });

  const { login, password, server, broker, name } = parsed.data;
  const headers = { Accept: 'application/json', 'auth-token': token };
  const keywords = broker ? [broker, server] : [server];

  try {
    const existingResponse = await fetch(`${METAAPI_ACCOUNTS}?query=${encodeURIComponent(login)}&type=cloud-g2`, {
      headers,
      cache: 'no-store',
      signal: AbortSignal.timeout(10_000)
    });

    if (existingResponse.ok) {
      const existing = await existingResponse.json() as Array<{ _id?: string; id?: string; login?: string; server?: string; state?: string }>;
      const account = existing.find(item => String(item.login ?? '') === login);
      if (account?._id || account?.id) {
        const accountId = account._id ?? account.id!;
        const updateResponse = await fetch(`${METAAPI_ACCOUNTS}/${encodeURIComponent(accountId)}`, {
          method: 'PUT',
          headers: { ...headers, 'Content-Type': 'application/json' },
          body: JSON.stringify({ password, name, server }),
          cache: 'no-store',
          signal: AbortSignal.timeout(15_000)
        });
        if (!updateResponse.ok) {
          const body = await updateResponse.text();
          return NextResponse.json({ error: 'Existing MT5 account update failed', details: safeMetaApiError(body) }, { status: updateResponse.status });
        }
        return NextResponse.json({ accountId, state: account.state ?? 'DEPLOYED', broker, server, reused: true });
      }
    }

    const transactionId = crypto.randomUUID().replaceAll('-', '').padEnd(32, '0').slice(0, 32);
    const createResponse = await fetch(METAAPI_ACCOUNTS, {
      method: 'POST',
      headers: { ...headers, 'Content-Type': 'application/json', 'transaction-id': transactionId },
      body: JSON.stringify({ login, password, server, name, platform: 'mt5', magic: 0, type: 'cloud-g2', keywords }),
      cache: 'no-store',
      signal: AbortSignal.timeout(15_000)
    });

    const body = await createResponse.text();
    const retryAfter = createResponse.headers.get('retry-after');
    if (createResponse.status === 202) {
      return NextResponse.json({ state: 'PROCESSING', message: 'MetaApi is validating the MT5 account. Retry after the supplied interval.' }, { status: 202, headers: retryAfter ? { 'retry-after': retryAfter } : undefined });
    }
    if (!createResponse.ok) return NextResponse.json({ error: 'MT5 connection validation failed', details: safeMetaApiError(body) }, { status: createResponse.status });

    const result = JSON.parse(body) as { id?: string; state?: string };
    return NextResponse.json({ accountId: result.id ?? null, state: result.state ?? 'UNKNOWN', broker, server, reused: false });
  } catch {
    return NextResponse.json({ error: 'Unable to reach MetaApi account provisioning service' }, { status: 502 });
  }
}

function safeMetaApiError(body: string) {
  try {
    const parsed = JSON.parse(body) as Record<string, unknown>;
    return { error: parsed.error, message: parsed.message, details: parsed.details };
  } catch { return { message: 'MetaApi returned an unreadable error response' }; }
}

import { NextResponse } from 'next/server';
import { z } from 'zod';
import { requireBackendKey } from '@/lib/auth';

const querySchema = z.object({ query: z.string().trim().min(1).max(100) });
const METAAPI_PROVISIONING_URL = 'https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai';

export async function GET(request: Request) {
  try {
    requireBackendKey(request);
    const token = process.env.METAAPI_TOKEN;
    if (!token) return NextResponse.json({ error: 'MetaApi token is not configured' }, { status: 503 });

    const requestUrl = new URL(request.url);
    const parsed = querySchema.safeParse({ query: requestUrl.searchParams.get('query') ?? '' });
    if (!parsed.success) return NextResponse.json({ error: 'query must contain 1-100 characters' }, { status: 400 });

    const upstream = new URL(`${METAAPI_PROVISIONING_URL}/known-mt-servers/5/search`);
    upstream.searchParams.set('query', parsed.data.query);
    const response = await fetch(upstream, {
      headers: { Accept: 'application/json', 'auth-token': token },
      cache: 'no-store'
    });

    const body = await response.text();
    if (!response.ok) {
      return NextResponse.json({ error: 'MetaApi server discovery failed', upstreamStatus: response.status }, { status: response.status >= 500 ? 502 : response.status });
    }

    const grouped = JSON.parse(body) as Record<string, string[]>;
    const servers = Object.entries(grouped).flatMap(([brokerName, names]) =>
      names.map((serverName) => ({
        id: `${brokerName}:${serverName}`,
        brokerName,
        serverName,
        environment: /demo|practice/i.test(serverName) ? 'demo' : 'live'
      }))
    );

    return NextResponse.json({ servers });
  } catch (error) {
    if (error instanceof Response) return error;
    return NextResponse.json({ error: 'Unable to discover MT5 servers' }, { status: 502 });
  }
}

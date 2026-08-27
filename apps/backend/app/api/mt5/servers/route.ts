import { NextRequest, NextResponse } from 'next/server';

const METAAPI_SERVERS = 'https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai/known-mt-servers/5/search';

export async function GET(request: NextRequest) {
  const token = process.env.METAAPI_TOKEN;
  if (!token) return NextResponse.json({ error: 'METAAPI_TOKEN is not configured' }, { status: 503 });

  const query = (request.nextUrl.searchParams.get('query') ?? request.nextUrl.searchParams.get('q') ?? '').trim();
  if (!query || query.length < 2 || query.length > 80) return NextResponse.json({ brokers: [] });

  try {
    const upstream = await fetch(`${METAAPI_SERVERS}?query=${encodeURIComponent(query)}`, {
      headers: { Accept: 'application/json', 'auth-token': token },
      cache: 'no-store'
    });
    const body = await upstream.text();
    if (!upstream.ok) return NextResponse.json({ error: 'MetaApi server discovery failed' }, { status: 502 });

    const grouped = JSON.parse(body) as Record<string, string[]>;
    const brokers = Object.entries(grouped).map(([broker, servers]) => ({
      broker,
      servers: Array.from(new Set(Array.isArray(servers) ? servers : [])).slice(0, 10)
    }));
    return NextResponse.json({ brokers });
  } catch {
    return NextResponse.json({ error: 'MT5 server discovery unavailable' }, { status: 502 });
  }
}

import { NextRequest, NextResponse } from 'next/server';

const METAAPI_SERVERS = 'https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai/known-mt-servers/5/search';

export async function GET(request: NextRequest) {
  const token = process.env.METAAPI_TOKEN;
  if (!token) return NextResponse.json({ error: 'METAAPI_TOKEN is not configured' }, { status: 503 });

  const query = request.nextUrl.searchParams.get('q')?.trim();
  if (!query || query.length < 2) return NextResponse.json({ brokers: [] });

  const upstream = await fetch(`${METAAPI_SERVERS}?query=${encodeURIComponent(query)}`, {
    headers: { Accept: 'application/json', 'auth-token': token },
    cache: 'no-store'
  });

  const body = await upstream.text();
  if (!upstream.ok) {
    return NextResponse.json({ error: 'MetaApi server discovery failed', details: body }, { status: upstream.status });
  }

  const grouped = JSON.parse(body) as Record<string, string[]>;
  const brokers = Object.entries(grouped).map(([broker, servers]) => ({ broker, servers }));
  return NextResponse.json({ brokers });
}

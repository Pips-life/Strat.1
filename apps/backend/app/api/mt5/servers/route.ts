import { NextResponse } from 'next/server';

const PROVISIONING_URL = 'https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai';

export async function GET(request: Request) {
  const token = process.env.METAAPI_TOKEN;
  if (!token) {
    return NextResponse.json({ error: 'METAAPI_TOKEN is not configured' }, { status: 500 });
  }

  const query = new URL(request.url).searchParams.get('query')?.trim();
  if (!query || query.length < 2) {
    return NextResponse.json({ error: 'query must contain at least 2 characters' }, { status: 400 });
  }

  try {
    const upstream = await fetch(
      `${PROVISIONING_URL}/known-mt-servers/5/search?query=${encodeURIComponent(query)}`,
      {
        headers: {
          Accept: 'application/json',
          'auth-token': token,
        },
        cache: 'no-store',
      },
    );

    const body = await upstream.json().catch(() => ({}));
    if (!upstream.ok) {
      return NextResponse.json(
        { error: 'MetaApi broker/server discovery failed', details: body },
        { status: upstream.status },
      );
    }

    const brokers = Object.entries(body as Record<string, unknown>).flatMap(([broker, value]) => {
      if (!Array.isArray(value)) return [];
      return [{
        broker,
        servers: value.filter((server): server is string => typeof server === 'string'),
      }];
    });

    return NextResponse.json({ platform: 'mt5', query, brokers });
  } catch {
    return NextResponse.json({ error: 'Unable to reach MetaApi provisioning service' }, { status: 502 });
  }
}

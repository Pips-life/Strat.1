import { NextResponse } from 'next/server';

const PROVISIONING_API = process.env.METAAPI_PROVISIONING_URL ?? 'https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai';
const METAAPI_TOKEN = process.env.METAAPI_TOKEN;

function errorMessage(body: string, fallback: string) {
  try {
    const json = JSON.parse(body) as { message?: string; error?: string };
    return json.message || json.error || fallback;
  } catch {
    return fallback;
  }
}

export async function POST(request: Request) {
  if (!METAAPI_TOKEN) return NextResponse.json({ error: 'METAAPI_TOKEN is not configured on the backend' }, { status: 503 });
  try {
    const body = await request.json() as { login?: string; password?: string; server?: string; name?: string; broker?: string };
    const login = body.login?.trim();
    const password = body.password;
    const server = body.server?.trim();
    const broker = body.broker?.trim();
    if (!login || !password || !server) return NextResponse.json({ error: 'login, password and server are required' }, { status: 400 });
    if (!/^\d+$/.test(login)) return NextResponse.json({ error: 'MT5 login must contain digits only' }, { status: 400 });

    const headers = { accept: 'application/json', 'content-type': 'application/json', 'auth-token': METAAPI_TOKEN };
    const existingResponse = await fetch(`${PROVISIONING_API}/users/current/accounts?query=${encodeURIComponent(login)}&limit=20`, { headers, cache: 'no-store' });
    if (existingResponse.ok) {
      const existing = await existingResponse.json() as Array<{ _id?: string; login?: string | number; server?: string; state?: string; connectionStatus?: string }>;
      const match = existing.find(account => String(account.login) === login && account.server === server);
      if (match?._id) {
        return NextResponse.json({ accountId: match._id, state: match.state ?? 'DEPLOYED', connectionStatus: match.connectionStatus ?? 'UNKNOWN', server: match.server ?? server, reused: true });
      }
    }

    const response = await fetch(`${PROVISIONING_API}/users/current/accounts`, {
      method: 'POST',
      headers: { ...headers, 'transaction-id': crypto.randomUUID().replaceAll('-', '') },
      body: JSON.stringify({ login, password, server, name: body.name || `Pips-life MT5 ${login}`, platform: 'mt5', magic: 100001, manualTrades: false, type: 'cloud-g2', keywords: broker ? [broker] : undefined }),
    });
    const responseBody = await response.text();
    if (!response.ok) return NextResponse.json({ error: errorMessage(responseBody, 'MetaApi account creation failed') }, { status: response.status });
    const result = JSON.parse(responseBody) as { id?: string; state?: string };
    return NextResponse.json({ accountId: result.id, state: result.state ?? 'DEPLOYED', connectionStatus: 'CONNECTING', server }, { status: response.status });
  } catch (error) {
    return NextResponse.json({ error: error instanceof Error ? error.message : 'MT5 connection request failed' }, { status: 500 });
  }
}

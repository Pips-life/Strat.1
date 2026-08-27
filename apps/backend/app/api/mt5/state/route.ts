import { NextResponse } from 'next/server';

const PROVISIONING_API = process.env.METAAPI_PROVISIONING_URL ?? 'https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai';
const METAAPI_TOKEN = process.env.METAAPI_TOKEN;

function clientApi(region?: string) {
  const normalized = (region ?? 'new-york').trim();
  return `https://mt-client-api-v1.${normalized}.agiliumtrade.ai`;
}

async function readJson(response: Response) {
  const text = await response.text();
  try { return JSON.parse(text); } catch { return { error: text || 'MetaApi returned an empty response' }; }
}

export async function GET(request: Request) {
  if (!METAAPI_TOKEN) return NextResponse.json({ error: 'METAAPI_TOKEN is not configured on the backend' }, { status: 503 });
  const accountId = new URL(request.url).searchParams.get('accountId')?.trim();
  if (!accountId) return NextResponse.json({ error: 'accountId is required' }, { status: 400 });
  try {
    const authHeaders = { accept: 'application/json', 'auth-token': METAAPI_TOKEN };
    const accountResponse = await fetch(`${PROVISIONING_API}/users/current/accounts/${encodeURIComponent(accountId)}`, { headers: authHeaders, cache: 'no-store' });
    const account = await readJson(accountResponse) as { region?: string; login?: string | number; server?: string; state?: string; connectionStatus?: string; baseCurrency?: string };
    if (!accountResponse.ok) return NextResponse.json({ error: account.error || 'Unable to read MetaApi account' }, { status: accountResponse.status });

    const api = clientApi(account.region);
    const [infoResponse, positionsResponse] = await Promise.all([
      fetch(`${api}/users/current/accounts/${encodeURIComponent(accountId)}/account-information`, { headers: authHeaders, cache: 'no-store' }),
      fetch(`${api}/users/current/accounts/${encodeURIComponent(accountId)}/positions`, { headers: authHeaders, cache: 'no-store' }),
    ]);
    const accountInformation = await readJson(infoResponse);
    const positions = await readJson(positionsResponse);
    return NextResponse.json({
      account: {
        id: accountId,
        login: account.login ?? accountInformation.login ?? null,
        server: account.server ?? accountInformation.server ?? null,
        state: account.state ?? null,
        connectionStatus: account.connectionStatus ?? null,
        region: account.region ?? null,
        currency: accountInformation.currency ?? account.baseCurrency ?? null,
        balance: accountInformation.balance ?? null,
        equity: accountInformation.equity ?? null,
        margin: accountInformation.margin ?? null,
        freeMargin: accountInformation.freeMargin ?? null,
        tradeAllowed: accountInformation.tradeAllowed ?? null,
      },
      positions: Array.isArray(positions) ? positions : [],
      fetchedAt: new Date().toISOString(),
    });
  } catch (error) {
    return NextResponse.json({ error: error instanceof Error ? error.message : 'Unable to read MT5 state' }, { status: 500 });
  }
}

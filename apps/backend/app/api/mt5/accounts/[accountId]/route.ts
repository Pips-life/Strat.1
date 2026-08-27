import { NextResponse } from 'next/server';

const PROVISIONING_API = process.env.METAAPI_PROVISIONING_URL ?? 'https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai';
const CLIENT_API = process.env.METAAPI_CLIENT_API ?? 'https://mt-client-api-v1.new-york.agiliumtrade.ai';
const METAAPI_TOKEN = process.env.METAAPI_TOKEN;

export async function GET(_request: Request, context: { params: Promise<{ accountId: string }> }) {
  if (!METAAPI_TOKEN) return NextResponse.json({ error: 'METAAPI_TOKEN is not configured on the backend' }, { status: 503 });
  const { accountId } = await context.params;
  if (!accountId) return NextResponse.json({ error: 'accountId is required' }, { status: 400 });
  const headers = { accept: 'application/json', 'auth-token': METAAPI_TOKEN };
  try {
    const accountResponse = await fetch(`${PROVISIONING_API}/users/current/accounts/${encodeURIComponent(accountId)}`, { headers, cache: 'no-store' });
    const accountText = await accountResponse.text();
    if (!accountResponse.ok) return NextResponse.json({ error: 'Unable to read MetaApi account', detail: accountText }, { status: accountResponse.status });
    const account = JSON.parse(accountText) as Record<string, unknown>;
    const base = `${CLIENT_API}/users/current/accounts/${encodeURIComponent(accountId)}`;
    const [infoResponse, positionsResponse] = await Promise.all([
      fetch(`${base}/account-information?refreshTerminalState=true`, { headers, cache: 'no-store' }),
      fetch(`${base}/positions?refreshTerminalState=true`, { headers, cache: 'no-store' }),
    ]);
    const info = infoResponse.ok ? await infoResponse.json() : null;
    const positions = positionsResponse.ok ? await positionsResponse.json() : [];
    return NextResponse.json({
      account: {
        id: account._id ?? account.id ?? accountId,
        login: account.login ?? info?.login ?? null,
        server: account.server ?? info?.server ?? null,
        state: account.state ?? null,
        connectionStatus: account.connectionStatus ?? null,
        type: account.type ?? null,
      },
      accountInformation: info,
      positions,
      fetchedAt: new Date().toISOString(),
    }, { headers: { 'cache-control': 'no-store' } });
  } catch (error) {
    return NextResponse.json({ error: error instanceof Error ? error.message : 'MT5 account snapshot failed' }, { status: 500 });
  }
}

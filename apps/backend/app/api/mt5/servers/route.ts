import { NextResponse } from 'next/server';
import { metaApi } from '@/lib/metaapi';

export const runtime = 'nodejs';

const UPSTREAM = process.env.MT5_SERVER_DIRECTORY_URL ?? 'https://broker-servers.apis.tradevps.net/';

type UpstreamServer = { name: string; type?: string };
type UpstreamBroker = { id?: string; name: string; servers?: UpstreamServer[] };

async function discover(query: string) {
  const response = await fetch(UPSTREAM, { headers: { accept: 'application/json' }, next: { revalidate: 3600 } });
  if (!response.ok) throw new Error('Broker server directory unavailable');
  const data = await response.json() as { brokers?: UpstreamBroker[]; metadata?: Record<string, unknown> };
  const brokers = (data.brokers ?? [])
    .filter(b => !query || b.name.toLowerCase().includes(query) || b.servers?.some(s => s.name.toLowerCase().includes(query)))
    .flatMap(b => (b.servers ?? []).map(s => ({ id: `${b.id ?? b.name}:${s.name}`, brokerName: b.name, serverName: s.name, environment: s.type === 'demo' ? 'demo' : 'real' })));
  return { brokers, metadata: data.metadata ?? null };
}

async function closeConnection(connection: { close: () => Promise<void> }) {
  try { await connection.close(); } catch { /* connection cleanup must not mask data */ }
}

export async function GET(request: Request) {
  const url = new URL(request.url);
  const action = url.searchParams.get('action');
  const accountId = url.searchParams.get('accountId');

  try {
    if (action === 'status' || action === 'positions') {
      if (!accountId) return NextResponse.json({ error: 'accountId is required' }, { status: 400 });
      const account = await metaApi().then(api => api.metatraderAccountApi.getAccount(accountId));
      const connection = account.getRPCConnection();
      try {
        await connection.connect();
        await connection.waitSynchronized();
        if (action === 'status') {
          const info = await connection.getAccountInformation();
          const positions = await connection.getPositions();
          return NextResponse.json({
            account: { id: account.id, login: account.login, server: account.server, state: account.state, connectionStatus: account.connectionStatus },
            accountInformation: info,
            positions,
            bot: { running: false, controlAvailable: Boolean(process.env.PIPSLIFE_BOT_CONTROL_URL), strategy: '001', activity: process.env.PIPSLIFE_BOT_CONTROL_URL ? 'BACKEND CONNECTED — BOT CONTROL READY' : 'BACKEND CONNECTED — BOT CONTROL SERVICE NOT CONFIGURED' }
          }, { headers: { 'cache-control': 'no-store' } });
        }
        const positions = await connection.getPositions();
        return NextResponse.json({ positions }, { headers: { 'cache-control': 'no-store' } });
      } finally { await closeConnection(connection); }
    }

    const query = (url.searchParams.get('q') ?? '').trim().toLowerCase();
    return NextResponse.json(await discover(query), { headers: { 'cache-control': 'public, max-age=300, stale-while-revalidate=3600' } });
  } catch (error) {
    return NextResponse.json({ error: error instanceof Error ? error.message : 'Backend request failed' }, { status: 502 });
  }
}

export async function POST(request: Request) {
  const body = await request.json() as { action?: string; login?: string; password?: string; server?: string; name?: string; accountId?: string; running?: boolean };
  try {
    if (body.action === 'bot') {
      if (!process.env.PIPSLIFE_BOT_CONTROL_URL) return NextResponse.json({ error: 'Bot control service is not configured' }, { status: 503 });
      const response = await fetch(process.env.PIPSLIFE_BOT_CONTROL_URL, {
        method: 'POST',
        headers: { 'content-type': 'application/json', ...(process.env.PIPSLIFE_BOT_CONTROL_TOKEN ? { authorization: `Bearer ${process.env.PIPSLIFE_BOT_CONTROL_TOKEN}` } : {}) },
        body: JSON.stringify({ running: body.running === true, strategy: '001' })
      });
      const data = await response.json().catch(() => ({}));
      return NextResponse.json(data, { status: response.status });
    }

    const login = body.login?.trim();
    const password = body.password;
    const server = body.server?.trim();
    if (!login || !password || !server) return NextResponse.json({ error: 'login, password and server are required' }, { status: 400 });
    if (!/^\d+$/.test(login)) return NextResponse.json({ error: 'MT5 login must contain digits only' }, { status: 400 });

    const api = await metaApi();
    const accounts = await api.metatraderAccountApi.getAccountsWithInfiniteScrollPagination({ limit: 100, offset: 0, query: login });
    const existing = accounts.find(candidate => String(candidate.login) === login && candidate.server === server);
    const account = existing ?? await api.metatraderAccountApi.createAccount({
      login, password, name: body.name?.trim() || `Pips-life MT5 ${login}`, server,
      platform: 'mt5', magic: 1001, type: 'cloud-g2', reliability: 'high',
      quoteStreamingIntervalInSeconds: 2.5, tags: ['pips-life', 'strategy-001']
    });

    if (existing) {
      await account.update({ password, name: body.name?.trim() || `Pips-life MT5 ${login}`, server, quoteStreamingIntervalInSeconds: 2.5 });
    }
    if (account.state !== 'DEPLOYED') await account.deploy();
    return NextResponse.json({ accountId: account.id, state: account.state, connectionStatus: account.connectionStatus, server: account.server, login: account.login });
  } catch (error) {
    return NextResponse.json({ error: error instanceof Error ? error.message : 'MT5 connection failed' }, { status: 502 });
  }
}

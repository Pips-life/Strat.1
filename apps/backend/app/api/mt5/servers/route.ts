import { NextResponse } from 'next/server';
const UPSTREAM = process.env.MT5_SERVER_DIRECTORY_URL ?? 'https://broker-servers.apis.tradevps.net/';
type UpstreamServer = { name: string; type?: string };
type UpstreamBroker = { id?: string; name: string; servers?: UpstreamServer[] };
export async function GET(request: Request) {
  const query = (new URL(request.url).searchParams.get('q') ?? '').trim().toLowerCase();
  try {
    const response = await fetch(UPSTREAM, { headers: { accept: 'application/json' }, next: { revalidate: 3600 } });
    if (!response.ok) return NextResponse.json({ error: 'Broker server directory unavailable' }, { status: 502 });
    const data = await response.json() as { brokers?: UpstreamBroker[]; metadata?: Record<string, unknown> };
    const brokers = (data.brokers ?? []).filter(b => !query || b.name.toLowerCase().includes(query) || b.servers?.some(s => s.name.toLowerCase().includes(query))).flatMap(b => (b.servers ?? []).map(s => ({ id: `${b.id ?? b.name}:${s.name}`, brokerName: b.name, serverName: s.name, environment: s.type === 'demo' ? 'demo' : 'real' })));
    return NextResponse.json({ brokers, metadata: data.metadata ?? null }, { headers: { 'cache-control': 'public, max-age=300, stale-while-revalidate=3600' } });
  } catch { return NextResponse.json({ error: 'Unable to reach broker server directory' }, { status: 502 }); }
}

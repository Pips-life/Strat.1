import { NextResponse } from 'next/server';

const UPSTREAM = process.env.MT5_SERVER_DIRECTORY_URL ?? 'https://broker-servers.apis.tradevps.net/';
type S = { name: string; type?: string };
type B = { id?: string; name: string; servers?: S[] };
export const runtime = 'nodejs';

const normalize = (v: string) => v.toLowerCase().replace(/[^a-z0-9]/g, '');
const hfmAliases = new Set(['hfm', 'hfmarket', 'hfmarkets']);
const matches = (q: string, b: B, s: S) => {
  if (!q) return true;
  const qn = normalize(q);
  const names = [normalize(b.name), normalize(s.name)];
  if (hfmAliases.has(qn)) return names.some(n => n.includes('hfm') || n.includes('hfmarket'));
  return names.some(n => n.includes(qn));
};

// HFM publishes the authoritative server names. Keep these as a fallback so
// the app remains usable even if the third-party directory temporarily omits HFM.
const HFM_FALLBACK: S[] = [
  { name: 'HFMarketsGlobal-Live1', type: 'real' },
  { name: 'HFMarketsGlobal-Demo', type: 'demo' },
  { name: 'HFMarketsGlobal-Live3', type: 'real' },
  { name: 'HFMarketsGlobal-Demo3', type: 'demo' },
  { name: 'HFMarketsGlobal-Live4', type: 'real' },
  { name: 'HFMarketsGlobal-Demo4', type: 'demo' },
  { name: 'HFMarketsGlobal-Live5', type: 'real' },
  { name: 'HFMarketsGlobal-Live7', type: 'real' },
  { name: 'HFMarketsGlobal-Live8', type: 'real' },
  { name: 'HFMarketsGlobal-Live9', type: 'real' },
  { name: 'HFMarketsGlobal-Live10', type: 'real' },
  { name: 'HFMarketsGlobal-Live11', type: 'real' },
  { name: 'HFMarketsGlobal-Live12', type: 'real' },
  { name: 'HFMarketsGlobal-Live13', type: 'real' },
  { name: 'HFMarketsGlobal-Live14', type: 'real' },
  { name: 'HFMarketsGlobal-Live15', type: 'real' },
  { name: 'HFMarketsGlobal-Live16', type: 'real' },
  { name: 'HFMarketsGlobal-Live17', type: 'real' },
  { name: 'HFMarketsGlobal-Live18', type: 'real' },
  { name: 'HFMarketsGlobal-Live19', type: 'real' },
  { name: 'HFMarketsGlobal-Live20', type: 'real' },
];

const toResult = (b: B, s: S) => ({
  id: `${b.id ?? b.name}:${s.name}`,
  brokerName: b.name,
  serverName: s.name,
  environment: s.type === 'demo' ? 'demo' : 'real',
});

export async function GET(request: Request) {
  try {
    const q = (new URL(request.url).searchParams.get('q') ?? '').trim();
    let upstreamResults: ReturnType<typeof toResult>[] = [];

    const r = await fetch(UPSTREAM, { headers: { accept: 'application/json' }, next: { revalidate: 3600 } });
    if (r.ok) {
      const d = await r.json() as { brokers?: B[] };
      upstreamResults = (d.brokers ?? []).flatMap(b => (b.servers ?? []).filter(s => matches(q, b, s)).map(s => toResult(b, s)));
    }

    if (hfmAliases.has(normalize(q)) && upstreamResults.length === 0) {
      upstreamResults = HFM_FALLBACK.map(s => toResult({ id: 'HFM', name: 'HFM' }, s));
    }

    if (!r.ok && upstreamResults.length === 0 && !hfmAliases.has(normalize(q))) {
      return NextResponse.json({ error: 'Broker server directory unavailable' }, { status: 502 });
    }

    return NextResponse.json(
      { brokers: upstreamResults },
      { headers: { 'cache-control': 'public,max-age=300,stale-while-revalidate=3600' } },
    );
  } catch (e) {
    if (hfmAliases.has(normalize((new URL(request.url).searchParams.get('q') ?? '').trim()))) {
      return NextResponse.json({ brokers: HFM_FALLBACK.map(s => toResult({ id: 'HFM', name: 'HFM' }, s)) });
    }
    return NextResponse.json({ error: e instanceof Error ? e.message : 'Server discovery failed' }, { status: 502 });
  }
}

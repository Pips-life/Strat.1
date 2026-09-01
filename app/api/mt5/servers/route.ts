import { NextResponse } from 'next/server';

const UPSTREAM = process.env.MT5_SERVER_DIRECTORY_URL ?? 'https://broker-servers.apis.tradevps.net/';
type S = { name: string; type?: string };
type B = { id?: string; name: string; servers?: S[] };
export const runtime = 'nodejs';

const normalize = (v: string) =>
  v.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]/g, '');

const aliases: Record<string, string[]> = {
  hfm: ['hfm', 'hfmarket', 'hfmarkets', 'hotforex', 'hfmarketsglobal'],
};

const aliasKey = (q: string) => {
  const n = normalize(q);
  return Object.keys(aliases).find(key => n === key || aliases[key].includes(n));
};

const matches = (q: string, b: B, s: S) => {
  const n = normalize(q);
  if (!n) return true;
  const key = aliasKey(q);
  const names = [normalize(b.name), normalize(s.name)];
  if (key) return aliases[key].some(a => names.some(name => name.includes(normalize(a))));
  return names.some(name => name.includes(n) || n.includes(name));
};

const score = (q: string, b: B, s: S) => {
  const n = normalize(q);
  const key = aliasKey(q);
  const names = [normalize(b.name), normalize(s.name)];
  if (key) return names.some(name => aliases[key].some(a => name === normalize(a))) ? 100 : 90;
  if (names.includes(n)) return 100;
  if (names.some(name => name.startsWith(n))) return 80;
  if (names.some(name => name.includes(n))) return 60;
  return 40;
};

// HFM publishes the authoritative server names. Keep a local safety net so
// discovery still works if the external directory is stale or unreachable.
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
  { name: 'HFMarketsSA-Live1', type: 'real' },
  { name: 'HFMarketsSA-Demo', type: 'demo' },
];

const toResult = (b: B, s: S) => ({
  id: `${b.id ?? b.name}:${s.name}`,
  brokerName: b.name,
  serverName: s.name,
  environment: s.type === 'demo' ? 'demo' : 'real',
});

async function loadUpstream(): Promise<B[]> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 8000);
  try {
    const r = await fetch(UPSTREAM, {
      headers: { accept: 'application/json' },
      signal: controller.signal,
      cache: 'no-store',
    });
    if (!r.ok) return [];
    const d = await r.json() as { brokers?: B[] };
    return Array.isArray(d.brokers) ? d.brokers : [];
  } finally {
    clearTimeout(timeout);
  }
}

export async function GET(request: Request) {
  const q = (new URL(request.url).searchParams.get('q') ?? '').trim();
  if (q.length < 2) return NextResponse.json({ brokers: [] });

  try {
    const brokers = await loadUpstream();
    const results = brokers
      .flatMap(b => (b.servers ?? []).filter(s => matches(q, b, s)).map(s => ({ result: toResult(b, s), rank: score(q, b, s) })))
      .sort((a, b) => b.rank - a.rank)
      .map(x => x.result);

    const key = aliasKey(q);
    if (key === 'hfm') {
      results.push(...HFM_FALLBACK.map(s => toResult({ id: 'HFM', name: 'HFM' }, s)));
    }

    const unique = Array.from(new Map(results.map(r => [`${normalize(r.brokerName)}:${normalize(r.serverName)}`, r])).values());

    return NextResponse.json(
      { brokers: unique },
      { headers: { 'cache-control': 'public,max-age=300,stale-while-revalidate=3600' } },
    );
  } catch (e) {
    if (aliasKey(q) === 'hfm') {
      return NextResponse.json({ brokers: HFM_FALLBACK.map(s => toResult({ id: 'HFM', name: 'HFM' }, s)) });
    }
    return NextResponse.json({ error: e instanceof Error ? e.message : 'Server discovery failed' }, { status: 502 });
  }
}

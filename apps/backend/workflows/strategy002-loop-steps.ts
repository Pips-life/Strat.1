import { getCache } from '@vercel/functions';

type Strategy002State = {
  running?: boolean;
  strategy?: string;
  loopToken?: string;
};

export async function readStrategy002State(accountId: string): Promise<Strategy002State | null> {
  'use step';
  return await getCache().get(`pipslife:bot:${accountId}`) as Strategy002State | null;
}

export async function executeStrategy002Tick(accountId: string) {
  'use step';

  const base = process.env.VERCEL_URL
    ? `https://${process.env.VERCEL_URL}`
    : (process.env.PIPSLIFE_APP_URL ?? 'https://strat-1.vercel.app');
  const token = process.env.METAAPI_TOKEN?.trim();
  if (!token) throw new Error('METAAPI_TOKEN is not configured');

  const url = `${base}/api/bot/control?accountId=${encodeURIComponent(accountId)}`;
  const response = await fetch(url, {
    headers: {
      accept: 'application/json',
      'x-pipslife-workflow': token,
      'cache-control': 'no-cache',
    },
    cache: 'no-store',
  });

  const text = await response.text();
  let data: any;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = { error: text };
  }

  if (!response.ok) {
    throw new Error(String(
      data?.error ?? data?.activity ?? `Strategy 002 tick failed (${response.status})`,
    ));
  }

  return data;
}

import { getCache } from '@vercel/functions';
import { getWorkflowMetadata, sleep } from 'workflow';

export async function strategy002Loop(accountId: string, loopToken: string) {
  'use workflow';

  const { workflowRunId } = getWorkflowMetadata();
  console.log(`[Strategy002] durable loop started run=${workflowRunId}`);

  while (true) {
    const state = await readBotState(accountId);
    if (!state?.running || state.strategy !== '002' || state.loopToken !== loopToken) {
      console.log(`[Strategy002] durable loop stopping run=${workflowRunId}`);
      return { status: 'stopped', runId: workflowRunId };
    }

    const result = await executeTick(accountId);
    console.log(`[Strategy002] ${JSON.stringify(result)}`);
    await sleep('1s');
  }
}

async function readBotState(accountId: string) {
  'use step';
  return await getCache().get(`pipslife:bot:${accountId}`) as { running?: boolean; strategy?: string; loopToken?: string } | null;
}

async function executeTick(accountId: string) {
  'use step';
  const base = process.env.VERCEL_URL ? `https://${process.env.VERCEL_URL}` : (process.env.PIPSLIFE_APP_URL ?? 'https://strat-1.vercel.app');
  const token = process.env.METAAPI_TOKEN?.trim();
  if (!token) throw new Error('METAAPI_TOKEN is not configured');
  const url = `${base}/api/bot/control?accountId=${encodeURIComponent(accountId)}`;
  const response = await fetch(url, {
    headers: {
      accept: 'application/json',
      'x-pipslife-workflow': token,
      'cache-control': 'no-cache'
    },
    cache: 'no-store'
  });
  const text = await response.text();
  let data: any;
  try { data = text ? JSON.parse(text) : null; } catch { data = { error: text }; }
  if (!response.ok) throw new Error(String(data?.error ?? data?.activity ?? `Strategy 002 tick failed (${response.status})`));
  return data;
}

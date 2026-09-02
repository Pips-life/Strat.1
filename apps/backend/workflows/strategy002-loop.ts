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
  const module = await import('../app/api/bot/control/route');
  const { api } = await module.accountInfo(accountId);
  return module.execute002(accountId, api, false);
}

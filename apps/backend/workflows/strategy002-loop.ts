import { getCache } from '@vercel/functions';
import { getWorkflowMetadata, sleep } from 'workflow';

export async function strategy002Loop(accountId: string) {
  'use workflow';

  const { workflowRunId } = getWorkflowMetadata();

  while (true) {
    const state = await readBotState(accountId);
    if (!state?.running || state.strategy !== '002' || state.runId !== workflowRunId) {
      console.log(`[Strategy002] loop ${workflowRunId} stopping`);
      return { status: 'stopped', runId: workflowRunId };
    }

    const result = await executeTick(accountId);
    console.log(`[Strategy002] ${JSON.stringify(result)}`);
    await sleep('1s');
  }
}

async function readBotState(accountId: string) {
  'use step';
  return await getCache().get(`pipslife:bot:${accountId}`) as { running?: boolean; strategy?: string; runId?: string } | null;
}

async function executeTick(accountId: string) {
  'use step';
  const module = await import('../app/api/bot/control/route');
  const { api } = await module.accountInfo(accountId);
  return module.execute002(accountId, api);
}

import { sleep } from 'workflow';

export async function strategy002Loop(accountId: string) {
  'use workflow';

  while (true) {
    const result = await executeTick(accountId);
    console.log(`[Strategy002] ${JSON.stringify(result)}`);
    await sleep('1s');
  }
}

async function executeTick(accountId: string) {
  'use step';

  const module = await import('../app/api/bot/control/route');
  const { account, api } = await module.accountInfo(accountId);
  return module.execute002(accountId, api);
}

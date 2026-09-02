import { sleep } from 'workflow';
import { executeStrategy002Tick, readStrategy002State } from './strategy002-loop-steps';

export async function strategy002Loop(accountId: string, loopToken: string) {
  'use workflow';

  while (true) {
    const state = await readStrategy002State(accountId);
    if (!state?.running || state.strategy !== '002' || state.loopToken !== loopToken) {
      return { status: 'stopped' as const };
    }

    await executeStrategy002Tick(accountId);
    await sleep('1s');
  }
}

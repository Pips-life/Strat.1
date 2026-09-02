import { getCache } from '@vercel/functions';
import { executeStrategy002 } from '../lib/strategy002-engine';

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
  // Execute the engine directly inside the step. This removes the extra
  // workflow -> HTTP -> route -> engine round trip from the tick path.
  return executeStrategy002(accountId, async () => undefined);
}

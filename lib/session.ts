import { createHmac, timingSafeEqual } from 'node:crypto';

function secret() {
  // MetaApi is the authoritative backend connection. Do not require a
  // second user-facing/session secret when the MetaApi backend token exists.
  const value = process.env.PIPSLIFE_SESSION_SECRET?.trim() || process.env.METAAPI_TOKEN?.trim();
  if (!value) throw new Error('MetaApi backend is not configured');
  return value;
}

export function issueAccountSession(accountId: string) {
  return `${accountId}.${createHmac('sha256', secret()).update(accountId).digest('hex')}`;
}

export function verifyAccountSession(request: Request, accountId: string) {
  const provided = request.headers.get('x-pipslife-session') ?? '';
  const expected = issueAccountSession(accountId);
  const a = Buffer.from(provided);
  const b = Buffer.from(expected);
  if (a.length !== b.length || !timingSafeEqual(a, b)) throw new Response('Unauthorized', { status: 401 });
}

import { createHmac, timingSafeEqual } from 'node:crypto';

function secret() {
  const value = process.env.PIPSLIFE_SESSION_SECRET?.trim();
  if (!value) throw new Error('PIPSLIFE_SESSION_SECRET is not configured');
  return value;
}

export function issueAccountSession(accountId: string) {
  const signature = createHmac('sha256', secret()).update(accountId).digest('hex');
  return `${accountId}.${signature}`;
}

export function verifyAccountSession(request: Request, accountId: string) {
  const provided = request.headers.get('x-pipslife-session') ?? '';
  const expected = issueAccountSession(accountId);
  const a = Buffer.from(provided);
  const b = Buffer.from(expected);
  if (a.length !== b.length || !timingSafeEqual(a, b)) throw new Response('Unauthorized', { status: 401 });
}

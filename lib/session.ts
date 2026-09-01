import { createHmac, timingSafeEqual } from 'node:crypto';

function secret() {
  const value = process.env.PIPSLIFE_SESSION_SECRET?.trim()
    || process.env.METAAPI_TOKEN?.trim()
    || process.env.META_API_TOKEN?.trim()
    || process.env.METAAPI_KEY?.trim()
    || process.env.META_API_KEY?.trim();
  if (!value) throw new Error('MetaApi backend is not configured: set METAAPI_TOKEN in the production Vercel environment');
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

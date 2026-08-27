export function requireBackendKey(request: Request): void {
  const expected = process.env.BACKEND_API_KEY;
  if (!expected) throw new Error('BACKEND_API_KEY is not configured');
  const provided = request.headers.get('x-api-key');
  if (!provided || provided !== expected) throw new Response('Unauthorized', { status: 401 });
}

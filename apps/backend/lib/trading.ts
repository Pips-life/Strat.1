function config() {
  const token = process.env.METAAPI_TOKEN;
  const base = process.env.METAAPI_CLIENT_API_URL;
  if (!token || !base) throw new Error('METAAPI_TOKEN and METAAPI_CLIENT_API_URL are required');
  return { token, base: base.replace(/\/$/, '') };
}

export async function metaApiRequest(accountId: string, path: string) {
  if (!/^[0-9a-fA-F-]{20,80}$/.test(accountId)) throw new Error('Invalid MetaApi account id');
  const { token, base } = config();
  const response = await fetch(`${base}/users/current/accounts/${accountId}${path}`, {
    headers: { accept: 'application/json', 'auth-token': token },
    cache: 'no-store'
  });
  const text = await response.text();
  let body: unknown = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = { message: text }; }
  if (!response.ok) {
    const message = typeof body === 'object' && body && 'message' in body ? String((body as { message?: unknown }).message) : text;
    throw new Error(`MetaApi ${response.status}: ${message}`);
  }
  return body;
}

export async function readAccount(accountId: string) {
  return metaApiRequest(accountId, '/account-information?refreshTerminalState=true');
}

export async function readPositions(accountId: string) {
  return metaApiRequest(accountId, '/positions?refreshTerminalState=true');
}

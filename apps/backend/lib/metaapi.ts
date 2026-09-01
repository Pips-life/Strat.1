import type MetaApi from 'metaapi.cloud-sdk';
let client: MetaApi | undefined;
export async function metaApi(): Promise<MetaApi> {
  const token = process.env.METAAPI_TOKEN;
  if (!token) throw new Error('METAAPI_TOKEN is not configured');
  if (!client) { const { default: MetaApiClient } = await import('metaapi.cloud-sdk'); client = new MetaApiClient(token); }
  return client;
}

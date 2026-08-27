import type MetaApi from 'metaapi.cloud-sdk';

let client: MetaApi | undefined;

export async function metaApi(): Promise<MetaApi> {
  const token = process.env.METAAPI_TOKEN;
  if (!token) throw new Error('METAAPI_TOKEN is not configured');
  if (!client) {
    const { default: MetaApiClient } = await import('metaapi.cloud-sdk');
    client = new MetaApiClient(token);
  }
  return client;
}

export async function getAccount(accountId: string) {
  const api = await metaApi();
  return api.metatraderAccountApi.getAccount(accountId);
}

export async function deployAccount(accountId: string) {
  const account = await getAccount(accountId);
  await account.deploy();
  return { id: account.id, state: account.state };
}

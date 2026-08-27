import MetaApi from 'metaapi.cloud-sdk';

let client: MetaApi | undefined;

export function metaApi(): MetaApi {
  const token = process.env.METAAPI_TOKEN;
  if (!token) throw new Error('METAAPI_TOKEN is not configured');
  client ??= new MetaApi(token);
  return client;
}

export async function getAccount(accountId: string) {
  return metaApi().metatraderAccountApi.getAccount(accountId);
}

export async function deployAccount(accountId: string) {
  const account = await getAccount(accountId);
  await account.deploy();
  return { id: account.id, state: account.state };
}

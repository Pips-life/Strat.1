import MetaApi from 'metaapi.cloud-sdk';

const token = process.env.METAAPI_TOKEN;
if (!token) throw new Error('METAAPI_TOKEN is not configured');

export const metaApi = new MetaApi(token);

export async function getAccount(accountId: string) {
  return metaApi.metatraderAccountApi.getAccount(accountId);
}

export async function getTerminalSnapshot(accountId: string) {
  const account = await getAccount(accountId);
  const connection = account.getRPCConnection();
  await connection.connect();
  await connection.waitSynchronized();
  const [accountInformation, positions] = await Promise.all([
    connection.getAccountInformation(),
    connection.getPositions()
  ]);
  await connection.close();
  return { account, accountInformation, positions };
}

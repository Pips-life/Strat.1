import { NextResponse } from 'next/server';
import { requireBackendKey } from '@/lib/auth';
import { metaApi } from '@/lib/metaapi';

export async function GET(request: Request) {
  try {
    requireBackendKey(request);
    const api = await metaApi();
    const accounts = await api.metatraderAccountApi.getAccountsWithInfiniteScrollPagination();
    return NextResponse.json(accounts.map((a: any) => ({
      id: a.id,
      name: a.name,
      platform: a.platform,
      state: a.state,
      connectionStatus: a.connectionStatus,
      type: a.type
    })));
  } catch (error) {
    if (error instanceof Response) return error;
    return NextResponse.json({ error: 'Unable to read MetaApi accounts' }, { status: 502 });
  }
}

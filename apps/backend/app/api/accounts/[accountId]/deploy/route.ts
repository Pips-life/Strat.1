import { NextResponse } from 'next/server';
import { requireBackendKey } from '@/lib/auth';
import { deployAccount } from '@/lib/metaapi';

export async function POST(request: Request, context: { params: Promise<{ accountId: string }> }) {
  try {
    requireBackendKey(request);
    const { accountId } = await context.params;
    return NextResponse.json(await deployAccount(accountId));
  } catch (error) {
    if (error instanceof Response) return error;
    return NextResponse.json({ error: 'Unable to deploy MetaApi account' }, { status: 502 });
  }
}

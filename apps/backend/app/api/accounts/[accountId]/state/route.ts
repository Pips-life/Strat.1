import { NextResponse } from 'next/server';
import { requireBackendKey } from '@/lib/auth';
import { readAccount, readPositions } from '@/lib/trading';

export async function GET(request: Request, context: { params: Promise<{ accountId: string }> }) {
  try {
    requireBackendKey(request);
    const { accountId } = await context.params;
    const [account, positions] = await Promise.all([readAccount(accountId), readPositions(accountId)]);
    return NextResponse.json({ account, positions });
  } catch (error) {
    if (error instanceof Response) return error;
    return NextResponse.json({ error: 'Unable to read MT5 account state' }, { status: 502 });
  }
}

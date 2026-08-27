import { NextResponse } from 'next/server';
import { verifyAccountSession } from '@/lib/session';
import { readAccount, readPositions } from '@/lib/trading';

export async function GET(request: Request, context: { params: Promise<{ accountId: string }> }) {
  try {
    const { accountId } = await context.params;
    verifyAccountSession(request, accountId);
    const [account, positions] = await Promise.all([readAccount(accountId), readPositions(accountId)]);
    return NextResponse.json({ account, positions, serverTime: new Date().toISOString() }, { headers: { 'cache-control': 'no-store' } });
  } catch (error) {
    if (error instanceof Response) return error;
    return NextResponse.json({ error: error instanceof Error ? error.message : 'Unable to read MT5 account state' }, { status: 502 });
  }
}

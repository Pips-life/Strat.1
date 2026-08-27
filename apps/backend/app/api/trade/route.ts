import { NextResponse } from 'next/server';
import { requireBackendKey } from '@/lib/auth';
import { executeTrade } from '@/lib/trading';

export async function POST(request: Request) {
  try {
    requireBackendKey(request);
    const body = await request.json();
    const result = await executeTrade(body);
    return NextResponse.json(result);
  } catch (error) {
    if (error instanceof Response) return error;
    return NextResponse.json({ error: error instanceof Error ? error.message : 'Trade request failed' }, { status: 502 });
  }
}

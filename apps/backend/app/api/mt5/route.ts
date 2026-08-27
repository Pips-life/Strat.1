import { NextResponse } from 'next/server';

export async function GET() {
  return NextResponse.json({ service: 'mt5', status: 'ok' });
}

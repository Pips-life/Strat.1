import { NextResponse } from 'next/server';

export async function GET() {
  return NextResponse.json({
    service: 'strat1-backend',
    status: 'ok',
    executionModes: ['REPLAY', 'DEMO', 'LIVE']
  });
}

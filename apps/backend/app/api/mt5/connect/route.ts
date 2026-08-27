import { NextResponse } from 'next/server';
import { z } from 'zod';
import { metaApi } from '@/lib/metaapi';

const payloadSchema = z.object({
  login: z.string().regex(/^\d+$/, 'MT5 account number must contain digits only'),
  password: z.string().min(1),
  server: z.string().min(1),
  name: z.string().min(1).max(100).default('Strat.1 MT5 account'),
  broker: z.string().max(120).optional(),
});

export async function POST(request: Request) {
  try {
    const payload = payloadSchema.parse(await request.json());
    const api = await metaApi();
    const account = await api.metatraderAccountApi.createAccount({
      name: payload.name,
      type: 'cloud-g2',
      login: payload.login,
      password: payload.password,
      server: payload.server,
      platform: 'mt5',
      application: 'MetaApi',
      magic: 1001,
      keywords: payload.broker ? [payload.broker] : undefined,
    });

    return NextResponse.json({
      accountId: account.id,
      state: account.state,
      server: account.server,
      connectionStatus: account.connectionStatus,
    });
  } catch (error) {
    if (error instanceof z.ZodError) {
      return NextResponse.json({ error: 'Invalid MT5 connection request', details: error.issues }, { status: 400 });
    }
    return NextResponse.json({ error: error instanceof Error ? error.message : 'MT5 connection failed' }, { status: 502 });
  }
}

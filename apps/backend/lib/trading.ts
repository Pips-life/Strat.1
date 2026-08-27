import { z } from 'zod';

const tradeSchema = z.object({
  accountId: z.string().min(1),
  action: z.enum(['BUY', 'SELL', 'CLOSE_POSITION', 'CLOSE_SYMBOL']),
  symbol: z.string().optional(),
  positionId: z.string().optional(),
  volume: z.number().positive().optional(),
  stopLoss: z.number().optional(),
  takeProfit: z.number().optional(),
  clientId: z.string().max(26).optional(),
  magic: z.number().int().nonnegative().optional(),
  comment: z.string().max(26).optional()
});

export type TradeCommand = z.infer<typeof tradeSchema>;

function config() {
  const token = process.env.METAAPI_TOKEN;
  const base = process.env.METAAPI_CLIENT_API_URL;
  if (!token || !base) throw new Error('METAAPI_TOKEN and METAAPI_CLIENT_API_URL are required');
  return { token, base: base.replace(/\/$/, '') };
}

export async function metaApiRequest(accountId: string, path: string, init?: RequestInit) {
  const { token, base } = config();
  const response = await fetch(`${base}/users/current/accounts/${accountId}${path}`, {
    ...init,
    headers: { accept: 'application/json', 'content-type': 'application/json', 'auth-token': token, ...(init?.headers ?? {}) },
    cache: 'no-store'
  });
  const text = await response.text();
  const body = text ? JSON.parse(text) : null;
  if (!response.ok) throw new Error(`MetaApi ${response.status}: ${body?.message ?? text}`);
  return body;
}

export async function readAccount(accountId: string) {
  return metaApiRequest(accountId, '/account-information?refreshTerminalState=true');
}

export async function readPositions(accountId: string) {
  return metaApiRequest(accountId, '/positions?refreshTerminalState=true');
}

export async function executeTrade(input: unknown) {
  const command = tradeSchema.parse(input);
  const actionType = command.action === 'BUY' ? 'ORDER_TYPE_BUY' :
    command.action === 'SELL' ? 'ORDER_TYPE_SELL' :
    command.action === 'CLOSE_POSITION' ? 'POSITION_CLOSE_ID' : 'POSITIONS_CLOSE_SYMBOL';

  const payload: Record<string, unknown> = { actionType };
  if (command.action === 'BUY' || command.action === 'SELL') {
    Object.assign(payload, {
      symbol: command.symbol,
      volume: command.volume,
      stopLoss: command.stopLoss,
      takeProfit: command.takeProfit,
      clientId: command.clientId,
      magic: command.magic,
      comment: command.comment
    });
  } else if (command.action === 'CLOSE_POSITION') {
    payload.positionId = command.positionId;
    payload.clientId = command.clientId;
    payload.magic = command.magic;
    payload.comment = command.comment;
  } else {
    payload.symbol = command.symbol;
    payload.clientId = command.clientId;
    payload.magic = command.magic;
    payload.comment = command.comment;
  }
  return metaApiRequest(command.accountId, '/trade', { method: 'POST', body: JSON.stringify(payload) });
}

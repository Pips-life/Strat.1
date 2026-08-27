import { NextRequest, NextResponse } from "next/server";

const METAAPI_URL = "https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai";

function auth(token: string, transactionId?: string) {
  return { accept: "application/json", "content-type": "application/json", "auth-token": token, ...(transactionId ? { "transaction-id": transactionId } : {}) };
}

export async function GET(request: NextRequest) {
  const token = process.env.METAAPI_TOKEN;
  if (!token) return NextResponse.json({ error: "MetaApi is not configured" }, { status: 503 });
  const query = request.nextUrl.searchParams.get("query")?.trim();
  if (!query || query.length < 2) return NextResponse.json({ error: "query must contain at least 2 characters" }, { status: 400 });
  const upstream = new URL(`${METAAPI_URL}/known-mt-servers/5/search`);
  upstream.searchParams.set("query", query);
  const response = await fetch(upstream, { headers: auth(token), cache: "no-store" });
  return new NextResponse(await response.text(), { status: response.status, headers: { "content-type": response.headers.get("content-type") ?? "application/json" } });
}

export async function POST(request: NextRequest) {
  const token = process.env.METAAPI_TOKEN;
  if (!token) return NextResponse.json({ error: "MetaApi is not configured" }, { status: 503 });
  const input = await request.json().catch(() => null) as { login?: string; password?: string; server?: string; broker?: string } | null;
  if (!input?.login || !/^\d+$/.test(input.login) || !input.password || !input.server) return NextResponse.json({ error: "login, password and server are required" }, { status: 400 });
  const response = await fetch(`${METAAPI_URL}/users/current/accounts`, {
    method: "POST",
    headers: auth(token, crypto.randomUUID().replaceAll("-", "")),
    body: JSON.stringify({ login: input.login, password: input.password, name: `Strat.1 MT5 ${input.login}`, server: input.server, platform: "mt5", magic: 1001, type: "cloud-g2", keywords: input.broker ? [input.broker] : undefined, manualTrades: true }),
    cache: "no-store",
  });
  return new NextResponse(await response.text(), { status: response.status, headers: { "content-type": response.headers.get("content-type") ?? "application/json" } });
}

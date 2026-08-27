import { NextRequest, NextResponse } from "next/server";

const METAAPI_URL = "https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai";

export async function GET(request: NextRequest) {
  const token = process.env.METAAPI_TOKEN;
  if (!token) return NextResponse.json({ error: "MetaApi is not configured" }, { status: 503 });

  const query = request.nextUrl.searchParams.get("query")?.trim();
  if (!query || query.length < 2) return NextResponse.json({ error: "query must contain at least 2 characters" }, { status: 400 });

  const upstream = new URL(`${METAAPI_URL}/known-mt-servers/5/search`);
  upstream.searchParams.set("query", query);
  const response = await fetch(upstream, { headers: { accept: "application/json", "auth-token": token }, cache: "no-store" });
  const body = await response.text();
  return new NextResponse(body, { status: response.status, headers: { "content-type": response.headers.get("content-type") ?? "application/json" } });
}

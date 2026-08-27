import { NextResponse } from 'next/server';

const GITHUB_RELEASE_ASSET = 'https://api.github.com/repos/Pips-life/Strat.1/releases/assets/';

export async function GET(request: Request) {
  const token = process.env.GITHUB_RELEASE_TOKEN;
  if (!token) return NextResponse.json({ error: 'GITHUB_RELEASE_TOKEN is not configured' }, { status: 503 });

  const assetId = new URL(request.url).searchParams.get('asset');
  if (!assetId || !/^\d+$/.test(assetId)) return NextResponse.json({ error: 'A numeric release asset id is required' }, { status: 400 });

  try {
    const response = await fetch(`${GITHUB_RELEASE_ASSET}${assetId}`, {
      headers: {
        accept: 'application/octet-stream',
        authorization: `Bearer ${token}`,
        'X-GitHub-Api-Version': '2026-03-10',
        'User-Agent': 'Pips-life-release-gateway'
      },
      cache: 'no-store',
      redirect: 'follow'
    });
    if (!response.ok || !response.body) return NextResponse.json({ error: `GitHub asset download failed (${response.status})` }, { status: 502 });

    return new Response(response.body, {
      status: 200,
      headers: {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Disposition': 'attachment; filename="Pips-life-update.apk"',
        'Cache-Control': 'no-store'
      }
    });
  } catch {
    return NextResponse.json({ error: 'Release download gateway failed' }, { status: 502 });
  }
}

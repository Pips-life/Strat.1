import { NextResponse } from 'next/server';

const GITHUB_API = 'https://api.github.com/repos/Pips-life/Strat.1/releases/latest';

export async function GET() {
  const token = process.env.GITHUB_RELEASE_TOKEN;
  if (!token) return NextResponse.json({ error: 'GITHUB_RELEASE_TOKEN is not configured' }, { status: 503 });

  try {
    const response = await fetch(GITHUB_API, {
      headers: {
        accept: 'application/vnd.github+json',
        authorization: `Bearer ${token}`,
        'X-GitHub-Api-Version': '2026-03-10',
        'User-Agent': 'Pips-life-release-gateway'
      },
      cache: 'no-store'
    });
    const body = await response.text();
    if (!response.ok) return NextResponse.json({ error: `GitHub release lookup failed (${response.status})` }, { status: 502 });

    const release = JSON.parse(body) as {
      tag_name?: string;
      name?: string;
      body?: string;
      html_url?: string;
      assets?: Array<{ id?: number; name?: string; size?: number; digest?: string; content_type?: string }>;
    };
    const asset = release.assets?.find(a => a.name?.toLowerCase().endsWith('.apk'));
    if (!asset?.id) return NextResponse.json({ error: 'Latest GitHub release has no APK asset' }, { status: 404 });

    const notes = release.body ?? '';
    const versionName = /^Version:\s*([^\r\n]+)/mi.exec(notes)?.[1]?.trim() ?? release.tag_name?.replace(/^v/, '') ?? '';
    const noteVersionCode = /^VersionCode:\s*(\d+)/mi.exec(notes)?.[1];
    const assetVersionCode = /^pips-life-[0-9]+\.[0-9]+\.[0-9]+-(\d+)\.apk$/i.exec(asset.name ?? '')?.[1];
    const versionCode = Number(noteVersionCode ?? assetVersionCode ?? 0);

    return NextResponse.json({
      tag: release.tag_name ?? '',
      name: release.name ?? release.tag_name ?? 'Pips-life update',
      versionName,
      versionCode,
      assetId: asset.id,
      assetName: asset.name,
      assetSize: asset.size ?? 0,
      digest: asset.digest ?? '',
      releaseUrl: release.html_url ?? ''
    }, { headers: { 'Cache-Control': 'no-store' } });
  } catch {
    return NextResponse.json({ error: 'Release gateway failed' }, { status: 502 });
  }
}

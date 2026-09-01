import { NextResponse } from 'next/server';
const UPSTREAM=process.env.MT5_SERVER_DIRECTORY_URL??'https://broker-servers.apis.tradevps.net/';
type S={name:string;type?:string};type B={id?:string;name:string;servers?:S[]};
export const runtime='nodejs';
const normalize=(v:string)=>v.toLowerCase().replace(/[^a-z0-9]/g,'');
const matches=(q:string,b:B,s:S)=>{if(!q)return true;const qn=normalize(q);const names=[normalize(b.name),normalize(s.name)];const aliases=qn==='hfm'||qn==='hfmarket'||qn==='hfmarkets'?['hfm','hfmarket','hfmarkets']:[];return names.some(n=>n.includes(qn)||aliases.some(a=>n.includes(a)));};
export async function GET(request:Request){try{const q=(new URL(request.url).searchParams.get('q')??'').trim();const r=await fetch(UPSTREAM,{headers:{accept:'application/json'},next:{revalidate:3600}});if(!r.ok)return NextResponse.json({error:'Broker server directory unavailable'},{status:502});const d=await r.json() as {brokers?:B[]};const brokers=(d.brokers??[]).flatMap(b=>(b.servers??[]).filter(s=>matches(q,b,s)).map(s=>({id:`${b.id??b.name}:${s.name}`,brokerName:b.name,serverName:s.name,environment:s.type==='demo'?'demo':'real'})));return NextResponse.json({brokers},{headers:{'cache-control':'public,max-age=300,stale-while-revalidate=3600'}})}catch(e){return NextResponse.json({error:e instanceof Error?e.message:'Server discovery failed'},{status:502})}}

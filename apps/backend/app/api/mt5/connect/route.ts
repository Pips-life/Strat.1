import { NextResponse } from 'next/server';
import { issueAccountSession } from '@/lib/session';
const API = process.env.METAAPI_PROVISIONING_URL ?? 'https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai';
export const runtime = 'nodejs';
export async function POST(request: Request) {
  const token = process.env.METAAPI_TOKEN;
  if (!token || !process.env.PIPSLIFE_SESSION_SECRET) return NextResponse.json({error:'Backend MetaApi/session secrets are not configured'},{status:503});
  try {
    const body = await request.json() as {login?:string;password?:string;server?:string;broker?:string};
    const login=body.login?.trim(), password=body.password, server=body.server?.trim();
    if(!login||!password||!server) return NextResponse.json({error:'login, password and server are required'},{status:400});
    if(!/^\d+$/.test(login)) return NextResponse.json({error:'MT5 login must contain digits only'},{status:400});
    const headers={accept:'application/json','content-type':'application/json','auth-token':token};
    const existingResponse=await fetch(`${API}/users/current/accounts?query=${encodeURIComponent(login)}&limit=20`,{headers,cache:'no-store'});
    if(existingResponse.ok){const list=await existingResponse.json() as Array<{_id?:string;login?:string|number;server?:string;state?:string;connectionStatus?:string}>;const match=list.find(x=>String(x.login)===login&&x.server===server);if(match?._id)return NextResponse.json({accountId:match._id,state:match.state??'DEPLOYED',connectionStatus:match.connectionStatus??'UNKNOWN',server:match.server??server,sessionToken:issueAccountSession(match._id),reused:true});}
    const response=await fetch(`${API}/users/current/accounts`,{method:'POST',headers:{...headers,'transaction-id':crypto.randomUUID().replaceAll('-','')},body:JSON.stringify({login,password,server,name:`Pips-life MT5 ${login}`,platform:'mt5',magic:100001,type:'cloud-g2',manualTrades:false,keywords:body.broker?[body.broker]:undefined})});
    const text=await response.text();let data:Record<string,unknown>={};try{data=JSON.parse(text)}catch{}
    if(!response.ok)return NextResponse.json({error:String(data.message??data.error??'MetaApi account creation failed')},{status:response.status});
    const id=String(data.id??'');if(!id)return NextResponse.json({error:'MetaApi returned no account id'},{status:502});
    return NextResponse.json({accountId:id,state:String(data.state??'DEPLOYED'),connectionStatus:'CONNECTING',server,sessionToken:issueAccountSession(id),reused:false},{status:response.status});
  }catch(e){return NextResponse.json({error:e instanceof Error?e.message:'MT5 connection failed'},{status:500});}
}

import { NextResponse } from 'next/server';
import { z } from 'zod';
import { issueMobileSession } from '@/lib/mobileAuth';
import { metaApi } from '@/lib/metaapi';
const schema=z.object({login:z.string().regex(/^\d+$/),password:z.string().min(1),server:z.string().min(1),name:z.string().min(1).max(100).default('Pips-life MT5 account'),broker:z.string().max(120).optional()});
export async function POST(request:Request){ try{ const p=schema.parse(await request.json()); const api=await metaApi(); const a=await api.metatraderAccountApi.createAccount({name:p.name,type:'cloud-g2',login:p.login,password:p.password,server:p.server,platform:'mt5',magic:1001,keywords:p.broker?[p.broker]:undefined}); return NextResponse.json({accountId:a.id,state:a.state,connectionStatus:a.connectionStatus,server:a.server,sessionToken:issueMobileSession(a.id)}); }catch(e){ if(e instanceof z.ZodError)return NextResponse.json({error:'Invalid MT5 connection request',details:e.issues},{status:400}); return NextResponse.json({error:e instanceof Error?e.message:'MT5 connection failed'},{status:502}); } }

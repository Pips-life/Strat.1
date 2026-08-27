import { NextResponse } from 'next/server';
import { requireMobileSession } from '@/lib/mobileAuth';
import { readAccount, readPositions } from '@/lib/trading';
export async function GET(request:Request){ try{ const accountId=requireMobileSession(request); const [account,positions]=await Promise.all([readAccount(accountId),readPositions(accountId)]); return NextResponse.json({account,positions}); }catch(e){ if(e instanceof Response)return e; return NextResponse.json({error:e instanceof Error?e.message:'Unable to read MT5 account state'},{status:502}); } }

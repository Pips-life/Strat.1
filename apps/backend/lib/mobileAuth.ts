import { createHmac, timingSafeEqual } from 'node:crypto';
function secret(){ const s=process.env.METAAPI_TOKEN; if(!s) throw new Error('METAAPI_TOKEN is not configured'); return s; }
function b64(v:string){ return Buffer.from(v).toString('base64url'); }
export function issueMobileSession(accountId:string){ const payload=b64(JSON.stringify({accountId,exp:Date.now()+30*24*60*60*1000})); const sig=createHmac('sha256',secret()).update(payload).digest('base64url'); return `${payload}.${sig}`; }
export function requireMobileSession(request:Request):string{
 const token=request.headers.get('authorization')?.replace(/^Bearer\s+/i,''); if(!token) throw new Response('Unauthorized',{status:401});
 const [payload,sig]=token.split('.'); if(!payload||!sig) throw new Response('Unauthorized',{status:401});
 const expected=createHmac('sha256',secret()).update(payload).digest(); const provided=Buffer.from(sig,'base64url');
 if(provided.length!==expected.length||!timingSafeEqual(provided,expected)) throw new Response('Unauthorized',{status:401});
 const data=JSON.parse(Buffer.from(payload,'base64url').toString('utf8')); if(!data.accountId||Date.now()>data.exp) throw new Response('Session expired',{status:401}); return data.accountId;
}

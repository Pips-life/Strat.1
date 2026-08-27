export async function metaApiRequest(accountId:string,path:string,init?:RequestInit){
 const token=process.env.METAAPI_TOKEN; const base=(process.env.METAAPI_CLIENT_API_URL||'https://mt-client-api-v1.agiliumtrade.agiliumtrade.ai').replace(/\/$/,''); if(!token) throw new Error('METAAPI_TOKEN is not configured');
 const r=await fetch(`${base}/users/current/accounts/${accountId}${path}`,{...init,headers:{accept:'application/json','content-type':'application/json','auth-token':token,...(init?.headers||{})},cache:'no-store'}); const text=await r.text(); const body=text?JSON.parse(text):null; if(!r.ok) throw new Error(`MetaApi ${r.status}: ${body?.message||text}`); return body;
}
export async function readAccount(accountId:string){ return metaApiRequest(accountId,'/account-information?refreshTerminalState=true'); }
export async function readPositions(accountId:string){ return metaApiRequest(accountId,'/positions?refreshTerminalState=true'); }

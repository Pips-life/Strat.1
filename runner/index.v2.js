import http from 'node:http';
import MetaApi from 'metaapi.cloud-sdk';

const PORT = Number(process.env.PORT || 10000);
const TOKEN = (process.env.METAAPI_TOKEN || process.env.META_API_TOKEN || '').trim();
const CONTROL_TOKEN = (process.env.PIPSLIFE_BOT_CONTROL_TOKEN || TOKEN).trim();
const DEFAULT_SYMBOLS = (process.env.PIPSLIFE_WATCHLIST || process.env.PIPSLIFE_SYMBOL || 'XAUUSD').split(',').map(s => s.trim()).filter(Boolean);
const LIVE_TRADING = String(process.env.PIPSLIFE_LIVE_TRADING_ENABLED ?? 'true').toLowerCase() === 'true';
const EXECUTION_VOLUME = Number(process.env.PIPSLIFE_EXECUTION_VOLUME || 0);
const TRAIL_PIPS = Number(process.env.PIPSLIFE_STRATEGY002_TRAIL_PIPS || 70);
const STRATEGIES = [
  { id: '001', engineId: 'strategy_001', name: 'QOF Strategy 001', adapter: 'python-qof' },
  { id: '002', engineId: 'strategy_002', name: 'Velocity Expansion', adapter: 'javascript-metaapi' }
];

let api;
const runtimes = new Map();
const meta = (id) => STRATEGIES.find(s => s.id === id);
const field = (o, k, d) => o && typeof o === 'object' ? (o[k] ?? d) : d;
const side = v => String(v || '').toUpperCase().includes('BUY') ? 'BUY' : String(v || '').toUpperCase().includes('SELL') ? 'SELL' : null;
const json = (res, status, body) => { res.writeHead(status, {'content-type':'application/json','cache-control':'no-store'}); res.end(JSON.stringify(body)); };
async function body(req) { const chunks=[]; for await (const c of req) chunks.push(c); const t=Buffer.concat(chunks).toString(); return t ? JSON.parse(t) : {}; }
function auth(req) { return !CONTROL_TOKEN || req.headers.authorization === `Bearer ${CONTROL_TOKEN}`; }
function managed(items, symbol) { return (items || []).filter(x => field(x,'symbol') === symbol); }

class Runtime {
  constructor(accountId) { this.accountId=accountId; this.strategy='002'; this.symbols=[...DEFAULT_SYMBOLS]; this.state='READY'; this.activity='Direct MetaApi runner ready'; this.connection=null; this.listener=null; this.tickCount=0; this.lastTick={}; this.previousPrice=0; this.previousTime=0; this.runningSide=null; this.pipSize=0.01; }
  async connect(strategy, symbols) {
    if (!TOKEN) throw new Error('METAAPI_TOKEN is not configured');
    const selected=meta(strategy); if (!selected) throw new Error(`Unknown strategy: ${strategy}`);
    this.strategy=strategy; this.symbols=(symbols?.length ? symbols : this.symbols).map(String);
    if (strategy === '001') { this.state='READY'; this.activity='Strategy 001 selected; Python QOF engine remains the canonical execution adapter'; return; }
    api ||= new MetaApi(TOKEN);
    const account=await api.metatraderAccountApi.getAccount(this.accountId);
    if (account.state !== 'DEPLOYED') await account.deploy();
    if (account.connectionStatus !== 'CONNECTED') await account.waitConnected();
    this.connection=account.getStreamingConnection();
    this.listener={
      onSymbolPriceUpdated: async (_, p) => this.onTick(p),
      onTicksUpdated: async (_, ticks) => { for (const t of ticks || []) await this.onTick(t); },
      onPositionsUpdated: async () => {}, onOrdersUpdated: async () => {},
      onDisconnected: async () => { this.state='DISCONNECTED'; this.activity='MetaApi disconnected'; }
    };
    this.connection.addSynchronizationListener(this.listener);
    await this.connection.connect(); await this.connection.waitSynchronized({timeoutInSeconds:120});
    for (const symbol of this.symbols) { const spec=this.connection.terminalState.specification(symbol); this.pipSize=Number(field(spec,'pipSize',field(spec,'point',this.pipSize))) || this.pipSize; await this.connection.subscribeToMarketData(symbol,[{type:'ticks'}],30); }
    this.state='RUNNING'; this.activity=`Strategy 002 connected directly to MetaApi (${this.symbols.join(', ')})`;
  }
  async onTick(tick) {
    if (this.state !== 'RUNNING') return;
    const bid=Number(field(tick,'bid',0)), ask=Number(field(tick,'ask',0)); const price=bid>0&&ask>0?(bid+ask)/2:Number(field(tick,'last',0)); if (!(price>0)) return;
    const symbol=String(field(tick,'symbol',this.symbols[0])); this.lastTick={symbol,bid,ask,price,time:Date.now()}; this.tickCount++;
    const now=Date.now()/1000, prev=this.previousPrice, prevTime=this.previousTime; this.previousPrice=price; this.previousTime=now;
    if (!(prev>0) || !(now>prevTime)) return;
    const velocity=(price-prev)/(now-prevTime), direction=velocity>0?'BUY':velocity<0?'SELL':null; if (!direction) return;
    const positions=managed(this.connection?.terminalState?.positions,symbol); if (positions.length) return;
    if (!LIVE_TRADING || !(EXECUTION_VOLUME>0)) return;
    const distance=TRAIL_PIPS*this.pipSize, stop=direction==='BUY'?price-distance:price+distance;
    const options={comment:'PL2E',clientId:'PIPS002',magic:100002};
    const result=direction==='BUY'?await this.connection.createMarketBuyOrder(symbol,EXECUTION_VOLUME,stop,undefined,options):await this.connection.createMarketSellOrder(symbol,EXECUTION_VOLUME,stop,undefined,options);
    this.runningSide=direction; this.activity=`002 ${direction} ${symbol} order acknowledged`; console.log(JSON.stringify({event:'ORDER_ACK',strategy:'002',symbol,direction,result}));
  }
  async stop() { if (this.connection) { for (const s of this.symbols) { try { await this.connection.unsubscribeFromMarketData(s); } catch {} } try { if (this.listener) this.connection.removeSynchronizationListener(this.listener); } catch {} try { await this.connection.close(); } catch {} } this.connection=null; this.state='STOPPED'; this.activity='Trading stopped'; }
  watchlist() { const specs=this.connection?.terminalState?.specifications || {}; return this.symbols.map(symbol => { const p=this.connection?.terminalState?.price(symbol); const spec=specs[symbol]; return {symbol,bid:Number(field(p,'bid',0)),ask:Number(field(p,'ask',0)),spread:Number(field(p,'ask',0))-Number(field(p,'bid',0)),pipSize:Number(field(spec,'pipSize',field(spec,'point',0))),subscribed:Boolean(this.connection?.subscribedSymbols?.includes(symbol))}; }); }
  stateJson() { return {ok:true,accountId:this.accountId,state:this.state,strategy:this.strategy,strategyInfo:meta(this.strategy),activity:this.activity,symbols:this.symbols,ticks:this.tickCount,lastTick:this.lastTick,liveTradingEnabled:LIVE_TRADING,executionVolumeConfigured:EXECUTION_VOLUME>0,trailPips:TRAIL_PIPS}; }
}

async function control(data) {
  const accountId=String(data.accountId || process.env.METAAPI_ACCOUNT_ID || '').trim(); if (!accountId) throw new Error('accountId is required');
  let r=runtimes.get(accountId); if (!r) { r=new Runtime(accountId); runtimes.set(accountId,r); }
  const action=String(data.action || '').toLowerCase();
  if (action==='select') { if (!meta(data.strategy)) throw new Error('strategy must be 001 or 002'); r.strategy=data.strategy; r.activity=`${meta(r.strategy).name} selected`; return r.stateJson(); }
  if (action==='start') { await r.connect(data.strategy || r.strategy, data.symbols || data.watchlist); return r.stateJson(); }
  if (action==='stop') { await r.stop(); return r.stateJson(); }
  if (action==='watchlist') { r.symbols=Array.isArray(data.symbols)?data.symbols.map(String).filter(Boolean):r.symbols; if (r.connection && r.state==='RUNNING') for (const s of r.symbols) await r.connection.subscribeToMarketData(s,[{type:'ticks'}],30); return {ok:true,symbols:r.symbols,quotes:r.watchlist()}; }
  throw new Error('action must be select, start, stop or watchlist');
}

const server=http.createServer(async (req,res)=>{
  if(!auth(req)) return json(res,401,{error:'Unauthorized'});
  const u=new URL(req.url,`http://${req.headers.host||'localhost'}`);
  try {
    if(req.method==='GET' && u.pathname==='/strategies') return json(res,200,{strategies:STRATEGIES});
    if(req.method==='GET' && u.pathname==='/health') return json(res,200,{ok:true,runner:'direct-metaapi',transport:'metaapi-javascript-sdk-streaming',metaapiConfigured:Boolean(TOKEN),accounts:runtimes.size});
    if(req.method==='GET' && u.pathname==='/state') { const r=runtimes.get(u.searchParams.get('accountId')); return json(res,200,r?r.stateJson():{ok:true,state:'READY',strategy:'002',strategies:STRATEGIES}); }
    if(req.method==='GET' && u.pathname==='/watchlist') { const r=runtimes.get(u.searchParams.get('accountId')); if(!r) return json(res,404,{error:'account runtime not started'}); return json(res,200,{symbols:r.symbols,quotes:r.watchlist()}); }
    if(req.method==='POST' && u.pathname==='/control') return json(res,200,await control(await body(req)));
    return json(res,404,{error:'Not found'});
  } catch(e) { return json(res,500,{ok:false,state:'ERROR',error:e instanceof Error?e.message:String(e)}); }
});
server.listen(PORT,()=>console.log(`Pips-life direct MetaApi runner listening on ${PORT}`));
process.on('SIGTERM',async()=>{for(const r of runtimes.values()) await r.stop(); server.close(()=>process.exit(0));});
process.on('SIGINT',async()=>{for(const r of runtimes.values()) await r.stop(); server.close(()=>process.exit(0));});

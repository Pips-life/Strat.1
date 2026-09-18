package life.pips.strat1

import android.os.SystemClock
import life.pips.strat1.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import kotlin.math.abs

/** S004: pure OHLC price action, 15M context -> 5M setup -> 1M BOS. */
class Strategy004Engine(private val http: OkHttpClient=OkHttpClient()){
 enum class Bias{BULLISH,BEARISH,NEUTRAL}
 enum class SetupState{NO_SETUP,SWEPT,REJECTED,DISPLACED,READY}
 data class Candle(val time:Long,val open:Double,val high:Double,val low:Double,val close:Double)
 data class Plan(val side:TradeSide?,val confidence:Int,val entry:Double?,val stop:Double?,val target:Double?,val reason:String,val contextBias:Bias,val setupState:SetupState,val bos:String,val sweptLiquidity:String,val targetLiquidity:String)
 private data class Setup(val side:TradeSide,val extreme:Double,val range:Double,val text:String)
 @Volatile private var refreshed=0L
 @Volatile private var data:Map<String,List<Candle>>=emptyMap()
 @Volatile private var plan=empty("Waiting for 15M/5M/1M price action")
 suspend fun refresh(a:MetaAccount,token:String,symbol:String,force:Boolean=false):Plan{
  val now=SystemClock.elapsedRealtime()
  if(!force&&data.isNotEmpty()&&now-refreshed<30000)return evaluate()
  return withContext(Dispatchers.IO){runCatching{
   data=listOf("1m","5m","15m").associateWith{fetch(a.region,a.id,token,symbol,it,120)}
   refreshed=SystemClock.elapsedRealtime();evaluate()
  }.getOrElse{plan.copy(reason="S004 data unavailable: "+(it.message?:"candle request failed"))}}
 }
 fun latest()=plan
 fun positionSide(p:MetaPosition)=when{p.type.contains("BUY",true)->TradeSide.BUY;p.type.contains("SELL",true)->TradeSide.SELL;else->null}
 fun exitOnContextReversal(p:Plan,pos:MetaPosition):Boolean{val s=positionSide(pos)?:return false;return(s==TradeSide.BUY&&p.contextBias==Bias.BEARISH)||(s==TradeSide.SELL&&p.contextBias==Bias.BULLISH)}
 private fun evaluate():Plan{
  val m1=data["1m"].orEmpty();val m5=data["5m"].orEmpty();val m15=data["15m"].orEmpty()
  if(m1.size<12||m5.size<20||m15.size<20)return empty("Waiting for enough 15M/5M/1M candles")
  val ctx=bias(m15);val s=setup(m5,ctx)?:return empty("15M "+ctx.name+" • 5M setup not complete").copy(contextBias=ctx)
  val e=m1.last();val level=if(s.side==TradeSide.BUY)swingHigh(m1)else swingLow(m1)
  val bos=if(s.side==TradeSide.BUY)level!=null&&e.close>level else level!=null&&e.close<level
  val sl=if(s.side==TradeSide.BUY)s.extreme-s.range*.10 else s.extreme+s.range*.10
  val tp=target(m5,s.side,e.close);val geom=if(s.side==TradeSide.BUY)sl<e.close&&tp!=null&&tp>e.close else sl>e.close&&tp!=null&&tp<e.close
  val rr=if(tp!=null&&abs(e.close-sl)>0)abs(tp-e.close)/abs(e.close-sl)else 0.0;val ready=bos&&geom&&rr>=1.35
  val r="15M "+ctx.name+" • 5M "+s.text+" → rejection → displacement • 1M "+if(bos)"BOS confirmed"else"waiting BOS"
  return Plan(if(ready)s.side else null,if(ready)90 else 65,e.close,sl,tp,r,ctx,if(ready)SetupState.READY else SetupState.DISPLACED,if(bos)if(s.side==TradeSide.BUY)"BULLISH BOS"else"BEARISH BOS"else"WAIT BOS",s.text,tp?.let{"OPPOSING LIQUIDITY %.5f".format(it)}?:"NO OPPOSING LIQUIDITY").also{plan=it}
 }
 private fun setup(c:List<Candle>,ctx:Bias):Setup?{
  if(ctx==Bias.NEUTRAL)return null
  for(i in (c.size-45).coerceAtLeast(4) until c.size-2){
   val p=c.subList(0,i);val hi=p.takeLast(20).maxOf{it.high};val lo=p.takeLast(20).minOf{it.low};val x=c[i]
   val buy=ctx==Bias.BULLISH&&x.low<lo&&x.close>lo;val sell=ctx==Bias.BEARISH&&x.high>hi&&x.close<hi
   if(!buy&&!sell)continue
   for(j in i+1 until c.size){val q=c[j];if(buy&&q.close>x.high&&disp(q,c,j))return Setup(TradeSide.BUY,x.low,maxOf(x.high-x.low,avg(c.takeLast(8))),"SELL-SIDE SWEEP");if(sell&&q.close<x.low&&disp(q,c,j))return Setup(TradeSide.SELL,x.high,maxOf(x.high-x.low,avg(c.takeLast(8))),"BUY-SIDE SWEEP")}
   return Setup(if(buy)TradeSide.BUY else TradeSide.SELL,if(buy)x.low else x.high,maxOf(x.high-x.low,avg(c.takeLast(8))),if(buy)"SELL-SIDE SWEEP"else"BUY-SIDE SWEEP")
  };return null
 }
 private fun disp(x:Candle,c:List<Candle>,i:Int)=i>0&&abs(x.close-x.open)>=avg(c.subList((i-8).coerceAtLeast(0),i))*1.35
 private fun target(c:List<Candle>,s:TradeSide,e:Double):Double?{val v=mutableListOf<Double>();for(i in 2 until c.size-2){if((i-2..i+2).all{it==i||c[it].high<=c[i].high})v+=c[i].high;if((i-2..i+2).all{it==i||c[it].low>=c[i].low})v+=c[i].low};return if(s==TradeSide.BUY)v.filter{it>e}.minOrNull()else v.filter{it<e}.maxOrNull()}
 private fun swingHigh(c:List<Candle>)=(c.size-10 until c.size-2).mapNotNull{ph(c,it)}.lastOrNull()
 private fun swingLow(c:List<Candle>)=(c.size-10 until c.size-2).mapNotNull{pl(c,it)}.lastOrNull()
 private fun bias(c:List<Candle>):Bias{val h=(2 until c.size-2).mapNotNull{ph(c,it)}.takeLast(4);val l=(2 until c.size-2).mapNotNull{pl(c,it)}.takeLast(4);if(h.size<2||l.size<2)return Bias.NEUTRAL;return when{h.last()>h[h.lastIndex-1]&&l.last()>l[l.lastIndex-1]->Bias.BULLISH;h.last()<h[h.lastIndex-1]&&l.last()<l[l.lastIndex-1]->Bias.BEARISH;else->Bias.NEUTRAL}}
 private fun ph(c:List<Candle>,i:Int):Double?{if(i<2||i>c.size-3)return null;val v=c[i].high;return if((i-2..i+2).all{it==i||c[it].high<=v})v else null}
 private fun pl(c:List<Candle>,i:Int):Double?{if(i<2||i>c.size-3)return null;val v=c[i].low;return if((i-2..i+2).all{it==i||c[it].low>=v})v else null}
 private fun avg(c:List<Candle>)=c.map{it.high-it.low}.average().takeIf{it.isFinite()&&it>0}?:0.0
 private fun fetch(region:String,id:String,token:String,symbol:String,tf:String,n:Int):List<Candle>{val b="https://mt-market-data-client-api-v1."+region.ifBlank{"london"}+".agiliumtrade.ai";val u=b+"/users/current/accounts/"+id+"/historical-market-data/symbols/"+URLEncoder.encode(symbol,"UTF-8")+"/timeframes/"+tf+"/candles?limit="+n;val r=http.newCall(Request.Builder().url(u).addHeader("auth-token",token).get().build()).execute();val body=try{if(!r.isSuccessful)throw IllegalStateException("HTTP "+r.code);r.body?.string().orEmpty()}finally{r.close()};val a=JSONArray(body);return buildList{for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;val t=runCatching{java.time.Instant.parse(x.optString("time")).toEpochMilli()}.getOrNull()?:continue;add(Candle(t,x.optDouble("open"),x.optDouble("high"),x.optDouble("low"),x.optDouble("close")))}}.sortedBy{it.time}}
 private fun empty(r:String)=Plan(null,0,null,null,null,r,Bias.NEUTRAL,SetupState.NO_SETUP,"WAIT BOS","NONE","NONE")
}
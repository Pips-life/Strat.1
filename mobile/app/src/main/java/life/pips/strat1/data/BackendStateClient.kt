package life.pips.strat1.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import life.pips.strat1.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class BackendStateClient(private val baseUrl:String=BuildConfig.BACKEND_BASE_URL, private val http:OkHttpClient=OkHttpClient()) {
 suspend fun state(session:String):BackendState=withContext(Dispatchers.IO){ val o=JSONObject(request("/api/mobile/account/state",session)); val a=o.getJSONObject("account"); val ps=o.optJSONArray("positions"); BackendState(a.optString("login"),a.optString("server"),a.optString("state"),a.optString("connectionStatus"),a.optDouble("balance",0.0),a.optDouble("equity",0.0),a.optString("currency","USD"),a.optBoolean("tradeAllowed",true),buildList{for(i in 0 until (ps?.length()?:0)){val p=ps!!.getJSONObject(i);add(BackendPosition(p.optString("id"),p.optString("symbol"),if(p.optString("type").contains("SELL",true))"SELL" else "BUY",p.optDouble("volume",0.0),p.optDouble("openPrice",0.0),p.optDouble("currentPrice",0.0),p.optDouble("stopLoss",0.0),p.optDouble("takeProfit",0.0),p.optDouble("profit",0.0)))}}) }
 suspend fun bot(session:String,running:Boolean):BotState=withContext(Dispatchers.IO){val o=JSONObject(post("/api/mobile/bot",session,JSONObject().put("action",if(running)"start" else "stop").toString()));BotState(o.optBoolean("running"),o.optString("state"),o.optString("connectionStatus"))}
 private fun request(path:String,session:String):String{http.newCall(Request.Builder().url(baseUrl+path).header("Authorization","Bearer $session").get().build()).execute().use{r->val b=r.body?.string().orEmpty();if(!r.isSuccessful)throw IllegalStateException(error(b,"Backend request failed"));return b}}
 private fun post(path:String,session:String,body:String):String{http.newCall(Request.Builder().url(baseUrl+path).header("Authorization","Bearer $session").post(body.toRequestBody("application/json".toMediaType())).build()).execute().use{r->val b=r.body?.string().orEmpty();if(!r.isSuccessful)throw IllegalStateException(error(b,"Backend request failed"));return b}}
 private fun error(body:String,fallback:String)=runCatching{JSONObject(body).optString("error").ifBlank{fallback}}.getOrDefault(fallback)
}
data class BackendState(val login:String,val server:String,val state:String,val connectionStatus:String,val balance:Double,val equity:Double,val currency:String,val tradeAllowed:Boolean,val positions:List<BackendPosition>)
data class BackendPosition(val id:String,val symbol:String,val side:String,val volume:Double,val entry:Double,val current:Double,val stopLoss:Double,val takeProfit:Double,val profit:Double)
data class BotState(val running:Boolean,val state:String,val connectionStatus:String)

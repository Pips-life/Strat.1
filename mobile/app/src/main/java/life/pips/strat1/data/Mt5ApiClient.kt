package life.pips.strat1.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import life.pips.strat1.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class Mt5ApiClient(private val baseUrl:String=BuildConfig.BACKEND_BASE_URL, private val http:OkHttpClient=OkHttpClient()) {
 suspend fun findServers(query:String):List<Mt5Server> = withContext(Dispatchers.IO){ val r=get("/api/mt5/servers?q=${java.net.URLEncoder.encode(query,"UTF-8")}"); val arr=JSONObject(r).optJSONArray("brokers")?:JSONArray(); buildList{ for(i in 0 until arr.length()){ val b=arr.getJSONObject(i); val name=b.optString("broker"); val ss=b.optJSONArray("servers")?:JSONArray(); for(j in 0 until ss.length()){ val s=ss.optString(j); add(Mt5Server("$name:$s",name,s,if(s.contains("demo",true))"demo" else "real")) } } } }
 suspend fun connect(login:String,password:String,server:String,broker:String):Mt5ConnectionResult = withContext(Dispatchers.IO){ val body=JSONObject().apply{put("login",login);put("password",password);put("server",server);put("broker",broker);put("name","Pips-life MT5 $login")}.toString(); val r=post("/api/mt5/connect",body); val o=JSONObject(r); Mt5ConnectionResult(o.optString("accountId").ifBlank{null},o.optString("state"),o.optString("connectionStatus"),o.optString("server"),o.optString("sessionToken")) }
 private fun get(path:String):String{ http.newCall(Request.Builder().url(baseUrl+path).get().build()).execute().use{r->val b=r.body?.string().orEmpty();if(!r.isSuccessful)throw IllegalStateException(error(b,"Request failed"));return b} }
 private fun post(path:String,body:String):String{ http.newCall(Request.Builder().url(baseUrl+path).post(body.toRequestBody("application/json".toMediaType())).build()).execute().use{r->val b=r.body?.string().orEmpty();if(!r.isSuccessful)throw IllegalStateException(error(b,"Request failed"));return b} }
 private fun error(body:String,fallback:String)=runCatching{JSONObject(body).optString("error").ifBlank{fallback}}.getOrDefault(fallback)
}
data class Mt5ConnectionResult(val accountId:String?,val state:String,val connectionStatus:String,val server:String,val sessionToken:String?)

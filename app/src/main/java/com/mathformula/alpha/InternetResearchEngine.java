package com.mathformula.alpha;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * On-phone Internet research DISCOVERY using an optional user-owned web-search API key.
 * Real search URLs/titles/snippets are collected with source provenance. A hit is NEVER
 * assumed to be a numeric finance fact, pre-event consensus, company attribution or
 * normalized Alpha-30 score. Full XBRL/filing extraction requires separate adapters.
 */
final class InternetResearchEngine {
  private static final String API="https://api.search.brave.com/res/v1/web/search";
  private static final String PREF="mathformula_web_research_v33";
  private static final AtomicBoolean BUSY=new AtomicBoolean(false);
  private static final long COOLDOWN_MS=60*60_000L;
  private final Context app;
  private final SharedPreferences prefs;
  private final SecureGrowwStore secrets;
  InternetResearchEngine(Context context) {
    app=context.getApplicationContext();
    prefs=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);
    secrets=new SecureGrowwStore(app);
  }
  boolean configured(){return !secrets.get("web_search_api_key").isEmpty();}
  boolean running(){return BUSY.get();}
  long completedAt(){return prefs.getLong("completeMs",0L);}
  String status(){return prefs.getString("status",configured()?"NOT RUN":"OPTIONAL SEARCH API NOT CONFIGURED");}
  JSONObject latest(){
    try{return new JSONObject(prefs.getString("latest","{}"));}
    catch(Exception ex){return new JSONObject();}
  }
  private static String today(){return LocalDate.now(MarketSchedule.IST).toString();}
  private static boolean allowedTime(){
    return LocalDiscovery.overnightResearchWindow()&&!MarketSchedule.weekdayTradingTime(System.currentTimeMillis());
  }
  boolean due(){
    if(!configured()||BUSY.get()||!allowedTime())return false;
    long now=System.currentTimeMillis();
    if(now<prefs.getLong("cooldownUntil",0L))return false;
    if(today().equals(prefs.getString("completedDay","")))return false;
    return now-prefs.getLong("lastAttempt",0L)>5*60_000L;
  }
  /** Pure function: bounded 10 queries for 3 priority symbols and 1 market query. */
  static List<String> queries(JSONArray symbols){
    LinkedHashSet<String> unique=new LinkedHashSet<>();
    if(symbols!=null)for(int i=0;i<symbols.length()&&unique.size()<3;i++){
      String symbol=symbols.optString(i,"").toUpperCase(Locale.US);
      if(symbol.matches("[A-Z0-9&-]{1,26}"))unique.add(symbol);
    }
    List<String> queries=new ArrayList<>();
    for(String symbol:unique){
      queries.add(symbol+" NSE quarterly results revenue EBITDA PAT filings site:nseindia.com OR site:bseindia.com");
      queries.add(symbol+" NSE exchange announcement order backlog guidance investor presentation "+LocalDate.now(MarketSchedule.IST).getYear());
      queries.add(symbol+" NSE shareholding pledge governance annual report site:nseindia.com OR site:bseindia.com");
    }
    if(!unique.isEmpty())queries.add("India NSE RBI FII DII institutional flows sector indices macroeconomic policy latest");
    return queries;
  }
  static String evidenceTier(String url){
    if(url==null)return "UNVERIFIED";
    try{
      java.net.URI uri=new java.net.URI(url);
      String domain=uri.getHost();
      if(!"https".equalsIgnoreCase(uri.getScheme())||domain==null)return "UNVERIFIED";
      domain=domain.toLowerCase(Locale.US);
      if(domain.equals("nseindia.com")||domain.endsWith(".nseindia.com")||
         domain.equals("bseindia.com")||domain.endsWith(".bseindia.com")||
         domain.equals("sebi.gov.in")||domain.endsWith(".sebi.gov.in")||
         domain.equals("rbi.org.in")||domain.endsWith(".rbi.org.in")||
         domain.equals("niftyindices.com")||domain.endsWith(".niftyindices.com"))
           return "OFFICIAL_HOST · CONTENT NOT YET EXTRACTED";
      return "EXTERNAL_SEARCH_RESULT · UNVERIFIED";
    }catch(Exception ignored){return "UNVERIFIED";}
  }
  /** Never follow redirects or send credentials to any host other than configured search API. */
  private JSONObject request(String query,String key)throws Exception {
    String path=API+"?q="+URLEncoder.encode(query,"UTF-8")+
      "&count=8&country=IN&search_lang=en";
    HttpURLConnection conn=(HttpURLConnection)new URL(path).openConnection();
    conn.setConnectTimeout(6500);conn.setReadTimeout(8500);
    conn.setInstanceFollowRedirects(false);
    conn.setRequestMethod("GET");
    conn.setRequestProperty("X-Subscription-Token",key);
    conn.setRequestProperty("Accept","application/json");
    try{
      int status=conn.getResponseCode();
      if(status==401||status==403)throw new SecurityException("SEARCH_ACCOUNT_REJECTED");
      if(status==429)throw new IllegalStateException("SEARCH_RATE_LIMITED");
      if(status!=200)throw new java.io.IOException("SEARCH_HTTP_"+status);
      try(InputStream input=conn.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
        byte[] block=new byte[8192];int n;
        while((n=input.read(block))!=-1){
          if(bytes.size()+n>262144)throw new java.io.IOException("SEARCH_RESPONSE_TOO_LARGE");
          bytes.write(block,0,n);
        }
        return new JSONObject(bytes.toString("UTF-8"));
      }
    }finally{conn.disconnect();}
  }
  /**
   * Reads the actual body of one published NSE iXBRL HTML record, never arbitrary
   * sites or PDF binaries. Text is truncated and used only as research evidence.
   */
  private static JSONObject readOfficialHtmlPreview(String source){
    JSONObject report=new JSONObject();
    if(!IxbrlFactExtractor.validNseDocument(source))
      return report.put("status","REJECTED_NON_NSE_IXBRL");
    HttpURLConnection connection=null;
    try{
      connection=(HttpURLConnection)new URL(source).openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(5500);connection.setReadTimeout(6500);
      connection.setRequestProperty("Accept","text/html");
      int http=connection.getResponseCode();
      if(http!=200)return report.put("status","NSE_DOCUMENT_HTTP_"+http);
      String kind=connection.getContentType();
      if(kind==null||!kind.toLowerCase(Locale.US).contains("text/html"))
        return report.put("status","NSE_DOCUMENT_NOT_HTML");
      try(InputStream in=connection.getInputStream();ByteArrayOutputStream b=new ByteArrayOutputStream()){
        byte[] buf=new byte[4096];int n;
        while((n=in.read(buf))!=-1){
          if(b.size()+n>230000)break;
          b.write(buf,0,n);
        }
        String raw=b.toString("UTF-8");
        JSONArray facts=IxbrlFactExtractor.extract(raw,source);
        String clean=raw.replaceAll("(?is)<(script|style)[^>]*>.*?</\\\\1>"," ");
        String text=android.text.Html.fromHtml(clean,
          android.text.Html.FROM_HTML_MODE_COMPACT).toString()
          .replaceAll("[\\\\s\\\\u00A0]+"," ").trim();
        report.put("status","OFFICIAL_DOCUMENT_READ · RAW_FACTS_UNVALIDATED")
          .put("preview",text.substring(0,Math.min(900,text.length())))
          .put("rawXbrlFacts",facts)
          .put("rawXbrlFactCount",facts.length())
          .put("fetchedAtMs",System.currentTimeMillis())
          .put("source",source)
          .put("note","Numeric extraction only; reporting context, consolidated basis and public release time not yet independently verified. Zero Alpha-30 score inferred.");
        return report;
      }
    }catch(Exception e){return report.put("status","NSE_DOCUMENT_UNAVAILABLE");}
    finally{if(connection!=null)connection.disconnect();}
  }

  /** Step-limited, idempotent research. Android can terminate at any point. */
  void refresh(JSONArray priority){
    if(!due()||!BUSY.compareAndSet(false,true))return;
    prefs.edit().putLong("lastAttempt",System.currentTimeMillis()).apply();
    try{
      String day=today();
      List<String> todo=queries(priority);
      if(todo.isEmpty()){
        prefs.edit().putString("status","NO FRESH PRIORITY CANDIDATES").apply();return;
      }
      String key=secrets.get("web_search_api_key");
      if(key.isEmpty())return;
      String cacheDay=prefs.getString("activeDay","");
      int cursor=day.equals(cacheDay)?prefs.getInt("cursor",0):0;
      JSONArray hits=day.equals(cacheDay)?rows():new JSONArray();
      if(!day.equals(cacheDay))prefs.edit().putString("activeDay",day)
        .putInt("cursor",0).putString("rows","[]").apply();
      // 2 requests per execution: budgets, resume, and battery-friendly cadence.
      int processed=0, primaryPagesRead=0;
      for(int i=cursor;i<todo.size()&&processed<2;i++){
        String query=todo.get(i);
        JSONObject result=request(query,key);
        JSONObject web=result.optJSONObject("web");
        JSONArray rows=web==null?null:web.optJSONArray("results");
        if(rows!=null)for(int r=0;r<Math.min(8,rows.length());r++){
          JSONObject hit=rows.optJSONObject(r);if(hit==null)continue;
          String url=hit.optString("url",""),title=hit.optString("title","");
          if(!url.startsWith("https://")||title.isEmpty())continue;
          String description=hit.optString("description","");
          if(title.length()>240)title=title.substring(0,240);
          if(description.length()>850)description=description.substring(0,850);
          JSONObject evidence=new JSONObject().put("query",query).put("url",url)
            .put("title",title).put("excerpt",description)
            .put("tier",evidenceTier(url))
            .put("discoveredMs",System.currentTimeMillis())
            .put("status","SOURCE DISCOVERED · UNSTRUCTURED / NO FACTOR SCORE");
          if(primaryPagesRead<1 && IxbrlFactExtractor.validNseDocument(url)){
            JSONObject extracted=readOfficialHtmlPreview(url);
            evidence.put("documentFetchStatus",extracted.optString("status","NO_DOCUMENT"));
            JSONArray rawFacts=extracted.optJSONArray("rawXbrlFacts");
            if(rawFacts!=null){
              evidence.put("rawXbrlFactCandidates",rawFacts)
                .put("rawXbrlFactCount",rawFacts.length())
                .put("nseDocumentExtract",extracted.optString("preview",""))
                .put("nseDocumentFetchedMs",extracted.optLong("fetchedAtMs",0))
                .put("status","NSE IXBRL STRUCTURED RAW FACTS · NORMALIZED SCORE UNVERIFIED");
            }
            primaryPagesRead++;
          }
          hits.put(evidence);
        }
        processed++;
        prefs.edit().putString("rows",hits.toString())
          .putInt("cursor",i+1).putString("status","RETRIEVING: "+
            (i+1)+"/"+todo.size()+" searches; "+hits.length()+" citations").apply();
        // Respect hosted search API quotas; public filings are separately fetched by official collector.
        if(processed<2)Thread.sleep(650L);
      }
      int done=prefs.getInt("cursor",0);
      if(done>=todo.size()){
        JSONObject batch=new JSONObject().put("asOfMs",System.currentTimeMillis())
          .put("sessionDate",day).put("queries",todo.size())
          .put("citedSources",hits.length()).put("results",hits)
          .put("factorScoresGenerated",0)
          .put("notice","Source discovery is not validated structured financial extraction");
        prefs.edit().putString("latest",batch.toString())
          .putLong("completeMs",System.currentTimeMillis())
          .putString("completedDay",day)
          .putString("status","RESEARCH COMPLETE · "+hits.length()+" UNVERIFIED LINKS").apply();
        new WatchStore(app).audit("WEB_RESEARCH","NSE","RUNNING","COMPLETE",
           "Internet searches "+todo.size()+", linked sources "+hits.length()+
           ", no normalized score fabricated",0);
      }
    }catch(SecurityException denied){
      prefs.edit().putString("status","SEARCH ACCOUNT AUTHENTICATION FAILED")
        .putLong("cooldownUntil",System.currentTimeMillis()+COOLDOWN_MS).apply();
    }catch(Exception ex){
      String label="RETRY_REQUIRED";
      if(ex.getMessage()!=null&&ex.getMessage().contains("RATE_LIMITED")){
        label="SEARCH_RATE_LIMITED";
        prefs.edit().putLong("cooldownUntil",System.currentTimeMillis()+COOLDOWN_MS).apply();
      }
      prefs.edit().putString("status",label).apply();
      new WatchStore(app).audit("WEB_RESEARCH","PROVIDER","RUNNING","FAILED",
        ex.getClass().getSimpleName(),0);
    }finally{BUSY.set(false);}
  }
  private JSONArray rows(){
    try{return new JSONArray(prefs.getString("rows","[]"));}
    catch(Exception ex){return new JSONArray();}
  }
}

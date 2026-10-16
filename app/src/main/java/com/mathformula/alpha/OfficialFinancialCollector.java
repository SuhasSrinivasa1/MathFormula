package com.mathformula.alpha;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * One-device primary-document retrieval that needs NO web-search subscription.
 * Uses only source URLs actually published by an NSE integrated-financial RSS feed.
 * Raw tagged facts are NOT a verified company-quarter series or normalized factors.
 */
final class OfficialFinancialCollector {
  private static final String PREF="mathformula_primary_documents_v34";
  private static final String RSS_PREF="mathformula_official_rss_v33";
  private static final long MIN_INTERVAL=4L*60*60*1000;
  private final Context app;
  private final SharedPreferences state;
  OfficialFinancialCollector(Context c){
    app=c.getApplicationContext();
    state=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);
  }
  String status(){return state.getString("status","No official XBRL document fetched yet");}
  long updated(){return state.getLong("successMs",0);}
  JSONObject latest(){
    try{return new JSONObject(state.getString("latest","{}"));}
    catch(Exception e){return new JSONObject();}
  }
  boolean due(){return System.currentTimeMillis()-state.getLong("attemptMs",0)>MIN_INTERVAL;}
  void refresh(){
    if(!due())return;
    long now=System.currentTimeMillis();
    state.edit().putLong("attemptMs",now).apply();
    try{
      JSONArray filingRows=new JSONArray(app.getSharedPreferences(
        RSS_PREF,Context.MODE_PRIVATE).getString("latest","[]"));
      String url="";long published=0;
      for(int i=0;i<filingRows.length();i++){
        JSONObject candidate=filingRows.optJSONObject(i);if(candidate==null)continue;
        if(!"INTEGRATED_FINANCIALS".equals(candidate.optString("topic","")))continue;
        String link=candidate.optString("url","");
        long publishedMs=candidate.optLong("publishedMs",0);
        if(!IxbrlFactExtractor.validNseDocument(link)||
           publishedMs<=0||publishedMs>now)continue;
        if(link.equals(state.getString("lastSource","")))continue;
        url=link;published=publishedMs;break;
      }
      if(url.isEmpty()){
        state.edit().putString("status",
          "No new supported, timestamped NSE iXBRL HTML document in available financial RSS. "+
          "PDF/XBRL taxonomy adapters still required.").apply();
        return;
      }
      HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();
      try{
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(6000);conn.setReadTimeout(9000);
        conn.setRequestProperty("Accept","text/html");
        int status=conn.getResponseCode();
        if(status!=200)throw new java.io.IOException("NSE_DOCUMENT_HTTP_"+status);
        String type=conn.getContentType();
        if(type==null||!type.toLowerCase(java.util.Locale.US).contains("text/html"))
          throw new java.io.IOException("NOT_HTML_IXBRL");
        try(InputStream input=conn.getInputStream();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
          byte[] buffer=new byte[8192];int read;
          while((read=input.read(buffer))!=-1){
            if(bytes.size()+read>240_000)break;
            bytes.write(buffer,0,read);
          }
          JSONArray facts=IxbrlFactExtractor.extract(bytes.toString("UTF-8"),url);
          JSONObject result=new JSONObject()
            .put("url",url).put("filingPublishedMs",published)
            .put("retrievedMs",System.currentTimeMillis())
            .put("rawCandidateCount",facts.length()).put("rawFacts",facts)
            .put("issuerVerified",false).put("periodVerified",false)
            .put("normalizedScoresGenerated",0)
            .put("status","OFFICIAL DOC FETCHED · FACTS RAW/UNATTRIBUTED · NO ALPHA-30 SCORE");
          state.edit().putString("latest",result.toString())
            .putString("lastSource",url).putLong("successMs",System.currentTimeMillis())
            .putString("status","NSE iXBRL fetched · "+facts.length()+
              " raw tagged numerical candidates, issuer/fiscal basis unverified")
            .apply();
          new WatchStore(app).audit("FINANCIAL_DOCUMENT","NSE","FETCHING","RAW_SAVED",
            facts.length()+" raw tagged amounts · no normalized scores",0);
        }
      }finally{conn.disconnect();}
    }catch(Exception err){
      state.edit().putString("status","PRIMARY_DOCUMENT_FETCH_FAILED · "+
        err.getClass().getSimpleName()).apply();
      new WatchStore(app).audit("FINANCIAL_DOCUMENT","NSE","FETCHING","FAILED",
        err.getClass().getSimpleName(),0);
    }
  }
}

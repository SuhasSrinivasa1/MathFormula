package com.mathformula.alpha;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Direct ON-DEVICE Internet collection from the NSE's public corporate announcements RSS.
 * Stores publication metadata and provenance, NEVER turns unverified headlines into factor
 * C11-C17 normalized scores or live BUY.
 */
final class OfficialNseFeed {
  static final String RSS="https://archives.nseindia.com/content/RSS/Online_announcements.xml";
  private static final String PREF="mathformula_official_rss_v32";
  private static final long INTERVAL=60*60_000L;
  private final Context app;
  private final SharedPreferences state;
  OfficialNseFeed(Context context){
    app=context.getApplicationContext();
    state=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);
  }
  boolean due(){
    return System.currentTimeMillis()-state.getLong("lastAttemptMs",0)>INTERVAL;
  }
  long completed(){return state.getLong("lastSuccessMs",0);}
  String status(){return state.getString("status","Not downloaded");}
  int count(){return state.getInt("items",0);}
  /** Bounded operation; network errors leave previous observations but mark their staleness. */
  void refresh() {
    if(!due())return;
    state.edit().putLong("lastAttemptMs",System.currentTimeMillis()).apply();
    HttpURLConnection connection=null;
    try{
      connection=(HttpURLConnection)new URL(RSS).openConnection();
      connection.setRequestMethod("GET");
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(8000);connection.setReadTimeout(12000);
      connection.setRequestProperty("Accept","application/rss+xml, application/xml, text/xml");
      int http=connection.getResponseCode();
      if(http!=200)throw new java.io.IOException("RSS_HTTP_"+http);
      int len=connection.getContentLength();
      if(len>2_000_000)throw new java.io.IOException("RSS_TOO_LARGE");
      JSONArray found=new JSONArray();
      XmlPullParserFactory factory=XmlPullParserFactory.newInstance();
      factory.setNamespaceAware(false);
      XmlPullParser parser=factory.newPullParser();
      try(InputStream in=new java.io.BufferedInputStream(connection.getInputStream())){
        // XML parser consumes stream; bounded input prevents huge RSS from exhausting memory.
        parser.setInput(new java.io.FilterInputStream(in){
          int count=0;
          @Override public int read()throws java.io.IOException{
            if(++count>2_000_000)throw new java.io.IOException("RSS_TOO_LARGE");
            return super.read();
          }
          @Override public int read(byte[] b,int off,int len)throws java.io.IOException{
            int allowance=2_000_001-count;
            if(allowance<=0)throw new java.io.IOException("RSS_TOO_LARGE");
            int n=super.read(b,off,Math.min(len,allowance));
            if(n>0)count+=n;
            if(count>2_000_000)throw new java.io.IOException("RSS_TOO_LARGE");
            return n;
          }
        },null);
        boolean item=false;String title="",link="",published="",description="";
        int event=parser.getEventType();
        while(event!=XmlPullParser.END_DOCUMENT&&found.length()<120){
          if(event==XmlPullParser.START_TAG){
            String tag=parser.getName();
            if("item".equalsIgnoreCase(tag)){
              item=true;title="";link="";published="";description="";
            }else if(item&&("title".equalsIgnoreCase(tag)||"link".equalsIgnoreCase(tag)||
                "pubDate".equalsIgnoreCase(tag)||"description".equalsIgnoreCase(tag))){
              String data=parser.nextText();
              data=data.length()>1200?data.substring(0,1200):data;
              if("title".equalsIgnoreCase(tag))title=data;
              else if("link".equalsIgnoreCase(tag))link=data;
              else if("pubDate".equalsIgnoreCase(tag))published=data;
              else description=data;
            }
          }else if(event==XmlPullParser.END_TAG && "item".equalsIgnoreCase(parser.getName())){
            item=false;
            if(link.startsWith("https://") && !title.isEmpty()){
              found.put(new JSONObject().put("title",title).put("url",link)
                .put("pubDate",published).put("summary",description));
            }
          }
          event=parser.next();
        }
      }
      state.edit().putString("latest",found.toString())
        .putInt("items",found.length()).putLong("lastSuccessMs",System.currentTimeMillis())
        .putString("status","NSE RSS metadata collected · not normalized for Alpha-30").apply();
    }catch(Exception error){
      String label=error instanceof java.net.SocketTimeoutException?"TIMEOUT":
        error.getMessage()!=null&&error.getMessage().startsWith("RSS_HTTP_")?
        error.getMessage():"RSS_UNAVAILABLE";
      state.edit().putString("status",label).apply();
    }finally{if(connection!=null)connection.disconnect();}
  }
  /** Show only verified exact ticker tokens. RSS company names may not include ticker. */
  JSONArray forSymbols(Set<String> tickerSet){
    JSONArray out=new JSONArray();
    try{
      JSONArray all=new JSONArray(state.getString("latest","[]"));
      Set<String> unique=new HashSet<>();
      for(int i=0;i<all.length();i++){
        JSONObject row=all.optJSONObject(i);if(row==null)continue;
        String body=(" "+row.optString("title")+" "+row.optString("summary")+" ").toUpperCase(java.util.Locale.US);
        for(String symbol:tickerSet){
          if(!symbol.matches("[A-Z0-9&-]{1,26}")||unique.contains(symbol))continue;
          String escaped=java.util.regex.Pattern.quote(symbol);
          if(java.util.regex.Pattern.compile("(?<![A-Z0-9])"+escaped+"(?![A-Z0-9])").matcher(body).find()){
            out.put(new JSONObject(row.toString()).put("symbol",symbol));
            unique.add(symbol);
          }
        }
      }
    }catch(Exception ignored){}
    return out;
  }
}

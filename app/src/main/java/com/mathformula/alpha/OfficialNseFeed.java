package com.mathformula.alpha;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

/**
 * Direct ON-DEVICE approved NSE RSS metadata collection. Four public feeds, no user key.
 * Headline, URL, topic, published timestamp are stored; never silently generate F/C scores.
 */
final class OfficialNseFeed {
  private static final String PREF="mathformula_official_rss_v33";
  private static final long INTERVAL=60*60_000L;
  static final String[][] FEEDS={
    {"ANNOUNCEMENTS","https://archives.nseindia.com/content/RSS/Online_announcements.xml"},
    {"INTEGRATED_FINANCIALS","https://nsearchives.nseindia.com/content/RSS/Integrated_Filing_Financials.xml"},
    {"ANNUAL_REPORTS","https://nsearchives.nseindia.com/content/RSS/Annual_Reports.xml"},
    {"SHAREHOLDING","https://nsearchives.nseindia.com/content/RSS/Shareholding_Pattern.xml"}
  };
  private final Context app;
  private final SharedPreferences state;
  OfficialNseFeed(Context context){
    app=context.getApplicationContext();
    state=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);
  }
  boolean due(){return System.currentTimeMillis()-state.getLong("lastAttemptMs",0)>INTERVAL;}
  long completed(){return state.getLong("lastSuccessMs",0);}
  String status(){return state.getString("status","Not downloaded");}
  int count(){return state.getInt("items",0);}
  static long publishedAt(String raw){
    if(raw==null||raw.isEmpty())return 0;
    try{return java.time.ZonedDateTime.parse(raw,
        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();}
    catch(Exception ignored){return 0;}
  }
  /** Fetches only fixed NSE domains, no redirect credential leakage, strict XML size cap. */
  private static void fetch(String topic,String address,JSONArray output)throws Exception {
    HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();
    connection.setRequestMethod("GET");connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(6000);connection.setReadTimeout(7500);
    connection.setRequestProperty("Accept","application/rss+xml, application/xml, text/xml");
    try{
      int http=connection.getResponseCode();
      if(http!=200)throw new IOException("RSS_HTTP_"+http);
      if(connection.getContentLength()>1_500_000)throw new IOException("RSS_TOO_LARGE");
      XmlPullParserFactory factory=XmlPullParserFactory.newInstance();
      factory.setNamespaceAware(false);
      XmlPullParser parser=factory.newPullParser();
      try(InputStream stream=connection.getInputStream();
          InputStream bounded=new FilterInputStream(stream){
            int loaded=0;
            @Override public int read()throws IOException{
              if(loaded>=1_500_000)throw new IOException("RSS_TOO_LARGE");
              int x=super.read();if(x>=0)loaded++;return x;
            }
            @Override public int read(byte[] b,int off,int len)throws IOException{
              if(loaded>=1_500_000)throw new IOException("RSS_TOO_LARGE");
              int n=super.read(b,off,Math.min(len,1_500_000-loaded));
              if(n>0)loaded+=n;return n;
            }
          }){
        parser.setInput(bounded,null);
        String title="",url="",published="",details="";boolean item=false;
        int event=parser.getEventType(),items=0;
        while(event!=XmlPullParser.END_DOCUMENT&&items<45){
          if(event==XmlPullParser.START_TAG){
            String tag=parser.getName();
            if("item".equalsIgnoreCase(tag)){
              item=true;title="";url="";published="";details="";
            }else if(item&&("title".equalsIgnoreCase(tag)||"link".equalsIgnoreCase(tag)||
                "pubDate".equalsIgnoreCase(tag)||"description".equalsIgnoreCase(tag))){
              String value=parser.nextText();
              if(value.length()>1200)value=value.substring(0,1200);
              if("title".equalsIgnoreCase(tag))title=value;
              else if("link".equalsIgnoreCase(tag))url=value;
              else if("pubDate".equalsIgnoreCase(tag))published=value;
              else details=value;
            }
          }else if(event==XmlPullParser.END_TAG&&
                     "item".equalsIgnoreCase(parser.getName())){
            item=false;items++;
            if(!title.isEmpty()&&url.startsWith("https://")){
              output.put(new JSONObject().put("topic",topic)
                .put("title",title).put("url",url).put("summary",details)
                .put("pubDate",published).put("publishedMs",publishedAt(published))
                .put("collectedMs",System.currentTimeMillis())
                .put("factorStatus","FILING METADATA ONLY · NUMERIC FACTS NOT VERIFIED"));
            }
          }
          event=parser.next();
        }
      }
    }finally{connection.disconnect();}
  }
  /** Individual feed errors do not erase successfully collected information. */
  void refresh(){
    if(!due())return;
    state.edit().putLong("lastAttemptMs",System.currentTimeMillis()).apply();
    JSONArray combined=new JSONArray();
    int worked=0,failed=0;
    StringBuilder failures=new StringBuilder();
    for(String[] feed:FEEDS){
      try{fetch(feed[0],feed[1],combined);worked++;}
      catch(Exception error){
        failed++;
        if(failures.length()>0)failures.append(",");
        failures.append(feed[0]);
      }
      if(Thread.currentThread().isInterrupted())break;
    }
    if(worked>0){
      state.edit().putString("latest",combined.toString())
        .putInt("items",combined.length()).putInt("sourcesSucceeded",worked)
        .putInt("sourcesFailed",failed).putLong("lastSuccessMs",System.currentTimeMillis())
        .putString("status","NSE feeds "+worked+"/"+FEEDS.length+" · "+combined.length()+
          " dated filing references"+(failed>0?" · unavailable: "+failures:"")+
          " · no factors inferred").apply();
    }else state.edit().putString("status","OFFICIAL_NSE_FEEDS_UNAVAILABLE: "+failures).apply();
  }
  /** Symbol matching is only tentative: titles usually use full company names. */
  JSONArray forSymbols(Set<String> tickers){
    JSONArray out=new JSONArray();
    try{
      JSONArray all=new JSONArray(state.getString("latest","[]"));
      Set<String> unique=new HashSet<>();
      for(int i=0;i<all.length();i++){
        JSONObject row=all.optJSONObject(i);if(row==null)continue;
        String body=(" "+row.optString("title")+" "+row.optString("summary")+" ")
          .toUpperCase(java.util.Locale.US);
        for(String ticker:tickers){
          if(!ticker.matches("[A-Z0-9&-]{1,26}"))continue;
          if(unique.contains(ticker+"#"+i))continue;
          String escaped=java.util.regex.Pattern.quote(ticker);
          if(java.util.regex.Pattern.compile("(?<![A-Z0-9])"+escaped+"(?![A-Z0-9])")
              .matcher(body).find()){
            out.put(new JSONObject(row.toString()).put("symbolHint",ticker)
              .put("symbolVerified",false));
            unique.add(ticker+"#"+i);
          }
        }
      }
    }catch(Exception ignored){}
    return out;
  }
}

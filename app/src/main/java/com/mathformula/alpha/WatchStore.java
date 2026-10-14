package com.mathformula.alpha;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

/** Local persistent state and bounded audit log. No tokens, TOTP codes or secrets are recorded. */
final class WatchStore {
  private static final String PREF="mathformula_market_watch_v12";
  static final String ACTION="com.mathformula.alpha.SCAN_RESULT";
  private final Context context;
  private final SharedPreferences p;
  WatchStore(Context c){context=c.getApplicationContext();p=context.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
  /** Preserve discovery baseline, observed peak and last quote across refreshed watch cards. */
  synchronized void put(JSONObject j) {
    String symbol=j.optString("symbol","");
    if(!symbol.matches("[A-Z0-9&-]{1,26}"))return;
    JSONObject prev=get(symbol);
    String past=prev.optString("stage","");
    long now=System.currentTimeMillis();
    double quote=j.optDouble("price",Double.NaN);
    long exchangeMs=j.optLong("tradeMs",0);
    boolean valid=Double.isFinite(quote)&&quote>0&&
       MarketSchedule.isCurrentQuote(exchangeMs,now);
    try {
      double first=prev.optDouble("firstObservedPrice",Double.NaN);
      long firstMs=prev.optLong("firstObservedMs",0);
      double peak=prev.optDouble("peakObservedPrice",Double.NaN);
      if(valid){
        if(!Double.isFinite(first)||first<=0){first=quote;firstMs=now;}
        if(!Double.isFinite(peak)||quote>peak)peak=quote;
      }
      if(Double.isFinite(first)&&first>0){
        j.put("firstObservedPrice",first);j.put("firstObservedMs",firstMs);
        j.put("peakObservedPrice",peak);
        j.put("runnerPct",Double.isFinite(quote)?100*(quote/first-1):JSONObject.NULL);
        j.put("peakRunnerPct",100*(peak/first-1));
        j.put("drawdownFromObservedPeakPct",
          Double.isFinite(quote)&&peak>0?100*(quote/peak-1):JSONObject.NULL);
      }
      boolean late=prev.optBoolean("firstSeenAfterTenPct",false);
      double change=j.optDouble("changePct",Double.NaN);
      if(prev.length()==0&&valid&&Double.isFinite(change)&&change>=10){
        late=true;
        audit("LATE_DISCOVERY",symbol,"NOT OBSERVED","FIRST SEEN >10%",
          "First observation already >=10% above previous close; not a proven missed BUY",exchangeMs);
      }
      j.put("firstSeenAfterTenPct",late);
    } catch(Exception ignored){}
    p.edit().putString("stock_"+symbol,j.toString())
      .putLong("lastUpdated",now).apply();
    String current=j.optString("stage","");
    if(!current.equals(past))audit("STATE",symbol,past.isEmpty()?"INITIAL":past,
      current,j.optString("next",""),exchangeMs);
    journal(j);
  }
  void setEarningsDate(String symbol,String day){
    if(!symbol.matches("[A-Z0-9&-]{1,26}"))return;
    if(!day.isEmpty())java.time.LocalDate.parse(day);
    p.edit().putString("earnings_"+symbol,day).apply();
    audit("EVENT",symbol,"MANUAL","UPDATED",day.isEmpty()?"Earnings date cleared":
      "User-entered earnings date "+day+"; NOT verified NSE filing",0);
  }
  String earningsDate(String symbol){return p.getString("earnings_"+symbol,"");}
  void recordPreClose(String label,String candidates){
    p.edit().putLong("lastPreClose",System.currentTimeMillis())
      .putString("precloseSummary",candidates).apply();
    audit("PRE_CLOSE","WATCHLIST","",label,candidates,0);
  }
  long lastPreClose(){return p.getLong("lastPreClose",0);}
  String precloseSummary(){return p.getString("precloseSummary","No pre-close checkpoint observed yet");}
  /** Bounded minute-by-minute observed market data, without any account, key or device IDs. */
  private synchronized void journal(JSONObject observation){
    try{
      JSONObject row=new JSONObject();
      String[] allowed={
        "symbol","price","changePct","reference","referenceAsOf","tradeMs","polledAt",
        "high","low","volume","vwap","spreadBps","fiveCount","fifteenCount",
        "two5m","fifteenConfirmed","aboveVwap5","aboveVwap15",
        "lastFiveClose","lastFifteenClose","stage","gapPct","modelVersion",
        "firstObservedPrice","peakObservedPrice","runnerPct","peakRunnerPct",
        "drawdownFromObservedPeakPct","decision"
      };
      for(String key:allowed)if(observation.has(key))row.put(key,observation.opt(key));
      boundedAppend("alpha30_market_observations.jsonl",row.toString(),3_000_000);
    }catch(Exception ignored){}
  }
  private void boundedAppend(String name,String jsonLine,long maxBytes) throws Exception {
    File f=new File(context.getFilesDir(),name);
    if(f.length()>maxBytes){
      File previous=new File(context.getFilesDir(),name+".previous");
      if(previous.exists())previous.delete();
      if(!f.renameTo(previous))throw new java.io.IOException("Market journal rotation unavailable");
    }
    try(FileOutputStream out=new FileOutputStream(f,true)){
      out.write((jsonLine+"\n").getBytes(StandardCharsets.UTF_8));
    }
  }
  /** Sanitize free-form log strings, never include tokens, URLs, email addresses or secrets. */
  static String safeExplanation(String source){
    if(source==null)return "";
    String txt=source.length()>600?source.substring(0,600):source;
    txt=txt.replaceAll("(?i)https?://\\S+","[LINK REDACTED]");
    txt=txt.replaceAll("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}","[EMAIL REDACTED]");
    txt=txt.replaceAll("(?i)(token|secret|authorization|password|api[-_ ]?key|bearer)\\s*[:=]\\s*[^\\s,;]+","[CREDENTIAL REDACTED]");
    return txt;
  }
  /** Export only known-safe event fields; exclude sensitive storage and internal settings. */
  String readExportJournal(String name,boolean market) {
    StringBuilder out=new StringBuilder();
    for(String suffix:new String[]{".previous",""}){
      File file=new File(context.getFilesDir(),name+suffix);
      if(!file.exists())continue;
      try(java.io.BufferedReader input=new java.io.BufferedReader(
          new java.io.InputStreamReader(new java.io.FileInputStream(file),StandardCharsets.UTF_8))){
        String line;
        while((line=input.readLine())!=null){
          if(line.length()>12_000)continue;
          try{
            JSONObject raw=new JSONObject(line);JSONObject clean=new JSONObject();
            String[] allowed=market?
              new String[]{"symbol","price","changePct","reference","referenceAsOf","tradeMs",
                "polledAt","high","low","volume","vwap","spreadBps","fiveCount",
                "fifteenCount","two5m","fifteenConfirmed","aboveVwap5","aboveVwap15",
                "lastFiveClose","lastFifteenClose","stage","gapPct","modelVersion",
                "firstObservedPrice","peakObservedPrice","runnerPct","peakRunnerPct",
                "drawdownFromObservedPeakPct","decision"}:
              new String[]{"at","exchangeTime","event","symbol","from","to","version"};
            for(String key:allowed)if(raw.has(key))clean.put(key,raw.opt(key));
            if(!market)clean.put("explanation",safeExplanation(raw.optString("explanation","")));
            out.append(clean.toString()).append('\n');
          }catch(Exception ignored){}
        }
      }catch(Exception ignored){}
    }
    return out.toString();
  }
  JSONObject get(String symbol) {
    try{return new JSONObject(p.getString("stock_"+symbol,"{}"));}
    catch(Exception e){return new JSONObject();}
  }
  void recordScan(boolean success,String reason,int count){
    p.edit().putLong("lastPoll",System.currentTimeMillis()).putBoolean("lastPollOk",success)
      .putString("lastError",success?"":reason).putInt("lastCount",count).apply();
    if(!success)audit("ERROR","SCAN","", "FAILED",reason,0);
  }
  void setEnabled(boolean enabled){p.edit().putBoolean("monitorEnabled",enabled).apply();}
  boolean enabled(){return p.getBoolean("monitorEnabled",false);}
  long lastPoll(){return p.getLong("lastPoll",0);}
  boolean lastPollOk(){return p.getBoolean("lastPollOk",false);}
  String lastError(){return p.getString("lastError","");}
  void audit(String type,String symbol,String previous,String next,String explanation,long exchangeMs){
    try{
      JSONObject event=new JSONObject();
      event.put("at",System.currentTimeMillis());event.put("exchangeTime",exchangeMs);
      event.put("event",type);event.put("symbol",symbol);
      event.put("from",previous);event.put("to",next);
      event.put("explanation",explanation);
      event.put("version",Alpha30Engine.VERSION);
      File log=new File(context.getFilesDir(),"alpha30_audit.jsonl");
      if(log.length()>250000){
        File archived=new File(context.getFilesDir(),"alpha30_audit.previous.jsonl");
        if(archived.exists())archived.delete();
        log.renameTo(archived);
      }
      try(FileOutputStream out=new FileOutputStream(log,true)){
        out.write((event.toString()+"\n").getBytes(StandardCharsets.UTF_8));
      }
    }catch(Exception ignored){}
  }
  String auditText(){
    try{
      File f=new File(context.getFilesDir(),"alpha30_audit.jsonl");
      if(!f.exists())return "No events yet. Monitoring starts from this device.";
      byte[] bytes=java.nio.file.Files.readAllBytes(f.toPath());
      String[] lines=new String(bytes,StandardCharsets.UTF_8).split("\n");
      StringBuilder display=new StringBuilder();int start=Math.max(0,lines.length-24);
      for(int i=lines.length-1;i>=start;i--){
        if(lines[i].isEmpty())continue;
        JSONObject j=new JSONObject(lines[i]);
        String time=java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm",Locale.US)
          .withZone(java.time.ZoneId.of("Asia/Kolkata"))
          .format(Instant.ofEpochMilli(j.optLong("at",0)));
        display.append(time).append(" · ").append(j.optString("symbol"))
          .append(" · ").append(j.optString("event")).append("\n")
          .append(j.optString("from")).append(" → ").append(j.optString("to")).append("\n")
          .append(j.optString("explanation")).append("\n\n");
      }
      return display.toString();
    }catch(Exception ignored){return "Could not load audit history.";}
  }
}

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
  void put(JSONObject j) {
    String symbol=j.optString("symbol","");
    if(!symbol.matches("[A-Z0-9&-]{1,26}"))return;
    String before=p.getString("stock_"+symbol,"");
    p.edit().putString("stock_"+symbol,j.toString())
      .putLong("lastUpdated",System.currentTimeMillis()).apply();
    JSONObject prev=null;try{prev=new JSONObject(before);}catch(Exception ignored){}
    String past=prev==null?"":prev.optString("stage","");
    String current=j.optString("stage","");
    if(!current.equals(past))audit("STATE",symbol,past.isEmpty()?"INITIAL":past,current,j.optString("next",""),j.optLong("tradeMs",0));
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

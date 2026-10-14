package com.mathformula.alpha;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Best-effort, foreground, READ-ONLY NSE stock research discovery using the phone's existing Groww TOTP session.
 * Discovery rank is NOT frozen Alpha-30 factor score and NEVER produces a BUY signal.
 * Every universe count / validated candidate is real; failed batches remain failures.
 */
final class LocalDiscovery {
  private static final String MASTER="https://growwapi-assets.groww.in/instruments/instrument.csv";
  private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
  private static final String PREF="mathformula_phone_discovery_v16";
  private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
  private final Context app;
  private final SharedPreferences prefs;
  interface Progress { void update(String message); }
  static final class Broad {
    String symbol;double price,open,high,low,move,location,rank;
  }
  LocalDiscovery(Context c){
    app=c.getApplicationContext();
    prefs=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);
  }
  JSONObject latest(){
    try{return new JSONObject(prefs.getString("latest","{}"));}
    catch(Exception e){return new JSONObject();}
  }
  JSONArray history(){
    try{return new JSONArray(prefs.getString("history","[]"));}
    catch(Exception e){return new JSONArray();}
  }
  long lastScan(){return prefs.getLong("lastScan",0);}
  long lastAttempt(){return prefs.getLong("lastAttempt",0);}
  String lastAttemptState(){return prefs.getString("lastAttemptState","Never scanned");}
  String error(){return prefs.getString("lastError","");}
  boolean scanning(){return RUNNING.get();}
  private String istDate(long timestamp) {
    return java.time.Instant.ofEpochMilli(timestamp).atZone(IST).toLocalDate().toString();
  }
  /** Most recent completed scan of each NSE session, NOT an exchange-authoritative close. */
  JSONArray sessionSnapshots(){
    try{return new JSONArray(prefs.getString("sessionSnapshots","[]"));}
    catch(Exception e){return new JSONArray();}
  }
  JSONObject lastClosedSession(long now){
    String today=istDate(now);
    java.time.LocalTime clock=java.time.Instant.ofEpochMilli(now)
        .atZone(IST).toLocalTime();
    boolean todayFinished=!clock.isBefore(java.time.LocalTime.of(15,30));
    JSONArray snapshots=sessionSnapshots();
    for(int i=snapshots.length()-1;i>=0;i--){
      JSONObject item=snapshots.optJSONObject(i);
      if(item==null)continue;
      String day=item.optString("sessionDate","");
      if(day.compareTo(today)<0 || (day.equals(today)&&todayFinished))return item;
    }
    return new JSONObject();
  }
  /**
   * Persist a full scan only after every batch has finished.
   * A scan started before the close belongs to that trading session even when final
   * verification finishes after 15:30; all shown quotes retain their own timestamps.
   */
  synchronized void save(JSONObject result){
    try{
      JSONArray previous=latest().optJSONArray("top");
      JSONArray current=result.optJSONArray("top");
      Set<String> seen=new HashSet<>();
      if(current!=null)for(int i=0;i<current.length();i++){
        JSONObject row=current.optJSONObject(i);
        if(row!=null)seen.add(row.optString("symbol"));
      }
      JSONArray timeline=history();
      if(previous!=null){
        for(int i=0;i<previous.length();i++){
          JSONObject old=previous.optJSONObject(i);if(old==null)continue;
          if(!seen.contains(old.optString("symbol"))){
            JSONObject archived=new JSONObject(old.toString());
            archived.put("removedAtMs",System.currentTimeMillis());
            archived.put("reason","Removed from automatic top three after a completed scan");
            timeline.put(archived);
          }
        }
      }
      if(timeline.length()>80){
        JSONArray trimmed=new JSONArray();
        for(int k=timeline.length()-80;k<timeline.length();k++){
          JSONObject record=timeline.optJSONObject(k);
          if(record!=null)trimmed.put(record);
        }
        timeline=trimmed;
      }
      long started=result.optLong("scanStartedMs",result.optLong("scannedAtMs",0));
      String sourceSession=result.optString("sourceSessionDate","");
      String day="NEXT_SESSION_RESEARCH".equals(result.optString("scanMode"))&&
        sourceSession.matches("\\d{4}-\\d{2}-\\d{2}")?sourceSession:istDate(started);
      JSONObject snapshot=new JSONObject(result.toString());
      snapshot.put("sessionDate",day);
      snapshot.put("snapshotKind","LAST_OBSERVED_SESSION_SCAN_NOT_EXCHANGE_CLOSE");
      JSONArray sessions=sessionSnapshots(),updated=new JSONArray();
      for(int i=0;i<sessions.length();i++){
        JSONObject item=sessions.optJSONObject(i);
        if(item!=null&&!day.equals(item.optString("sessionDate")))updated.put(item);
      }
      updated.put(snapshot);
      if(updated.length()>35){
        JSONArray limited=new JSONArray();
        for(int i=updated.length()-35;i<updated.length();i++)limited.put(updated.optJSONObject(i));
        updated=limited;
      }
      prefs.edit()
        .putLong("lastAttempt",System.currentTimeMillis())
        .putString("lastAttemptState","Completed · "+result.optInt("universeCount",0)+
            " NSE symbols · "+result.optInt("quotedCount",0)+" prices · "+
            result.optInt("eligibleForResearch",0)+" qualified research candidates")
        .putString("history",timeline.toString())
        .putString("latest",result.toString())
        .putString("sessionSnapshots",updated.toString())
        .putLong("lastScan",result.optLong("scannedAtMs",System.currentTimeMillis()))
        .putString("lastError","").apply();
      // Lifecycle changes only after a successfully persisted, completed scan.
      new RecommendationLedger(app).onCompletedScan(result);
      ResearchAlerts.newShortlist(app,result,previous);
    }catch(Exception ex){failure("Could not preserve completed scan");}
  }
  /** Update displayed quotes without falsely reranking or changing validated breakout references. */
  synchronized void applyQuoteRefresh(Map<String,GrowwReadOnlyClient.Market> recent){
    if(recent==null||recent.isEmpty())return;
    try{
      JSONObject scan=latest();
      JSONArray old=scan.optJSONArray("top");
      if(old==null)return;
      JSONArray fresh=new JSONArray();
      for(int i=0;i<old.length();i++){
        JSONObject item=old.optJSONObject(i);
        if(item==null)continue;
        JSONObject copy=new JSONObject(item.toString());
        GrowwReadOnlyClient.Market market=recent.get(item.optString("symbol"));
        if(market!=null&&Double.isFinite(market.price)&&market.price>0){
          long time=System.currentTimeMillis();
          if(market.lastTradeMs>0&&market.lastTradeMs<=time+3000&&time-market.lastTradeMs<=60_000){
            double reference=copy.optDouble("breakoutLevel",Double.NaN);
            copy.put("price",market.price);
            copy.put("tradeMs",market.lastTradeMs);
            copy.put("observedMs",time);
            if(Double.isFinite(reference)&&reference>0)
              copy.put("distancePct",100*(market.price/reference-1));
            copy.put("volume",Double.isFinite(market.dayVolume)?market.dayVolume:JSONObject.NULL);
            copy.put("averageTradePrice",Double.isFinite(market.vwap)?market.vwap:JSONObject.NULL);
            copy.put("dayChangePct",Double.isFinite(market.change)?market.change:JSONObject.NULL);
            copy.put("quoteUpdatedSinceScan",true);
          }
        }
        fresh.put(copy);
      }
      scan.put("top",fresh);
      scan.put("quoteRefreshMs",System.currentTimeMillis());
      // Do not update scannedAtMs or session snapshots: this was NOT a new full scan.
      prefs.edit().putString("latest",scan.toString()).apply();
      // A fresh quote updates a watch, but never promotes it to a BUY signal.
      new RecommendationLedger(app).onQuoteRefresh(fresh);
    }catch(Exception ignored){}
  }
  void failure(String why){
    prefs.edit()
      .putString("lastAttemptState","Failed · "+safe(why))
      .putString("lastError",safe(why)).apply();
  }
  String exportJson(){
    try{
      return new JSONObject().put("source","Groww phone discovery")
        .put("formulaVersion",Alpha30Engine.VERSION)
        .put("formulaChanged",false)
        .put("lastCompleted",latest()).put("closedSessions",sessionSnapshots())
        .put("previouslyTracked",history())
        .put("lastError",safe(error())).toString(2);
    }catch(Exception ex){return "{}";}
  }
  private static String safe(String s){
    if(s==null)return "";
    return s.length()>150?s.substring(0,150):s;
  }
  private List<String> universe(Progress progress) throws Exception{
    File file=new File(app.getFilesDir(),"groww_nse_eq_master_v16.txt");
    long age=System.currentTimeMillis()-file.lastModified();
    if(!file.exists()||file.length()<8000||age>24L*60*60*1000L){
      progress.update("Updating NSE equity universe from Groww");
      File temp=new File(app.getFilesDir(),"groww_master_tmp_v16");
      HttpURLConnection conn=(HttpURLConnection)new URL(MASTER).openConnection();
      try{
        conn.setRequestMethod("GET");conn.setConnectTimeout(9000);conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(false);conn.setRequestProperty("Accept","text/csv");
        if(conn.getResponseCode()!=200)throw new IOException("Groww instrument list HTTP "+conn.getResponseCode());
        int rows=0;
        try(InputStream in=conn.getInputStream();OutputStream out=new FileOutputStream(temp)){
          byte[] buf=new byte[16*1024];int n;long total=0;
          while((n=in.read(buf))!=-1){
            total+=n;if(total>75_000_000)throw new IOException("Instrument list too large");
            out.write(buf,0,n);rows+=n;
          }
        }
        if(temp.length()<8000)throw new IOException("Groww master contains insufficient data");
        if(file.exists())file.delete();
        if(!temp.renameTo(file))throw new IOException("Could not store verified instrument list");
      }finally{conn.disconnect();if(temp.exists())temp.delete();}
    }
    List<String> stocks=new ArrayList<>();
    Set<String> unique=new HashSet<>();
    try(BufferedReader reader=new BufferedReader(new InputStreamReader(
        new FileInputStream(file),StandardCharsets.UTF_8))){
      String head=reader.readLine();
      if(head==null)throw new IOException("Missing instrument CSV header");
      head=head.replace("\uFEFF","");
      List<String> keys=csv(head);
      int ex=column(keys,"exchange"),seg=column(keys,"segment"),
         series=column(keys,"series"),kind=column(keys,"instrument_type"),
         symbol=column(keys,"trading_symbol");
      if(ex<0||seg<0||series<0||symbol<0)throw new IOException("Groww instrument file schema changed");
      String line;
      while((line=reader.readLine())!=null){
        List<String> row=csv(line);
        if(row.size()<=Math.max(Math.max(ex,seg),Math.max(series,symbol)))continue;
        if(!"NSE".equalsIgnoreCase(row.get(ex).trim())||
           !"CASH".equalsIgnoreCase(row.get(seg).trim())||
           !"EQ".equalsIgnoreCase(row.get(series).trim()))continue;
        if(kind>=0&&kind<row.size()&&!"EQ".equalsIgnoreCase(row.get(kind).trim()))continue;
        String s=row.get(symbol).trim().toUpperCase(Locale.US);
        if(s.matches("[A-Z0-9&-]{1,26}")&&unique.add(s))stocks.add(s);
        if(stocks.size()>8500)throw new IOException("Unexpectedly large NSE universe");
      }
    }
    if(stocks.size()<500)throw new IOException("Groww returned fewer than 500 NSE EQ instruments");
    Collections.sort(stocks);
    return stocks;
  }
  private int column(List<String> head,String name){
    for(int i=0;i<head.size();i++)
      if(name.equalsIgnoreCase(head.get(i).trim()))return i;
    return -1;
  }
  /** RFC4180 single-line fields; CSV values may contain embedded commas inside quotes. */
  private List<String> csv(String line){
    List<String> fields=new ArrayList<>();StringBuilder current=new StringBuilder();
    boolean quoted=false;
    for(int i=0;i<line.length();i++){
      char c=line.charAt(i);
      if(c=='"'){
        if(quoted&&i+1<line.length()&&line.charAt(i+1)=='"'){current.append('"');i++;}
        else quoted=!quoted;
      }else if(c==','&&!quoted){fields.add(current.toString());current.setLength(0);}
      else current.append(c);
    }
    fields.add(current.toString());return fields;
  }
  private static double ohlcField(Object value,String field){
    String s=value instanceof JSONObject?((JSONObject)value).optString(field,""):String.valueOf(value);
    if(value instanceof JSONObject)return ((JSONObject)value).optDouble(field,Double.NaN);
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)\\b"+field+"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(s);
    if(!m.find())return Double.NaN;
    try{return Double.parseDouble(m.group(1));}catch(Exception e){return Double.NaN;}
  }
  private static double clamp(double x,double min,double max){return Math.max(min,Math.min(max,x));}
  private static double finite(double x){return Double.isFinite(x)?x:Double.NaN;}
  JSONObject scan(GrowwReadOnlyClient groww,Progress progress) throws Exception{
    return scan(groww,progress,false);
  }
  JSONObject scanForNextSession(GrowwReadOnlyClient groww,Progress progress) throws Exception{
    return scan(groww,progress,true);
  }
  private JSONObject scan(GrowwReadOnlyClient groww,Progress progress,boolean afterHours) throws Exception{
    long scanStarted=System.currentTimeMillis();
    if(!RUNNING.compareAndSet(false,true))throw new IllegalStateException("Discovery scan already active");
    prefs.edit().putLong("lastAttempt",scanStarted)
      .putString("lastAttemptState","Scanning NSE equity universe…").apply();
    try{
      List<String> universe=universe(progress);
      Map<String,Double> prices=new HashMap<>();
      Map<String,Object> candles=new HashMap<>();
      int successfulBatches=0,failedBatches=0;
      for(int offset=0;offset<universe.size();offset+=50){
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("Scan cancelled");
        List<String> group=universe.subList(offset,Math.min(universe.size(),offset+50));
        try{
          JSONObject ltp=groww.batch("ltp",group);
          JSONObject ohlc=groww.batch("ohlc",group);
          for(String symbol:group){
            double price=ltp.optDouble("NSE_"+symbol,Double.NaN);
            Object data=ohlc.opt("NSE_"+symbol);
            if(Double.isFinite(price)&&price>0)prices.put(symbol,price);
            if(data!=null)candles.put(symbol,data);
          }
          successfulBatches++;
        }catch(SecurityException auth){throw auth;}
         catch(Exception partial){failedBatches++;}
        if(offset%500==0)progress.update("Screening "+Math.min(offset+50,universe.size())+
          " / "+universe.size()+" NSE stocks");
        Thread.sleep(280); // less than 4 API calls/sec; avoids Groww Live Data limits
      }
      if(prices.size()<Math.max(200,(int)(universe.size()*.70)))
        throw new IOException("Incomplete Groww price scan: "+prices.size()+" / "+universe.size());
      List<Broad> broad=new ArrayList<>();
      for(String s:universe){
        Double price=prices.get(s);Object data=candles.get(s);
        if(price==null||data==null)continue;
        double open=ohlcField(data,"open"),high=ohlcField(data,"high"),
          low=ohlcField(data,"low");
        if(!Double.isFinite(open)||open<=0||!Double.isFinite(high)||!Double.isFinite(low)||high<low)continue;
        double move=100*(price/open-1);
        if(price<25||price>15000||move<0.7||move>8.0)continue; // early setups, exclude chasing
        double location=clamp((price-low)/Math.max(.01,high-low),0,1);
        if(location<.55)continue;
        Broad b=new Broad();
        b.symbol=s;b.price=price;b.open=open;b.high=high;b.low=low;
        b.move=move;b.location=location;
        b.rank=55*clamp(move/7.,0,1)+45*location;
        broad.add(b);
      }
      broad.sort((a,b)->Double.compare(b.rank,a.rank));
      // Verify only leading technical candidates with actual 60 completed sessions & Groww trade time.
      int toVerify=Math.min(55,broad.size());int checked=0,valid=0;
      List<JSONObject> candidates=new ArrayList<>();
      for(int i=0;i<toVerify;i++){
        Broad b=broad.get(i);checked++;
        progress.update("Verifying breakout history "+checked+" / "+toVerify);
        try{
          JSONObject item;
          if(afterHours){
            GrowwReadOnlyClient.NextSessionHistory h=groww.nextSessionHistory(b.symbol);
            if(h.avgValue20<10_000_000||h.priorHigh20<=0||h.previousClose<=0)continue;
            double distance=100*(h.close/h.priorHigh20-1);
            double daily=100*(h.close/h.previousClose-1);
            double location=clamp((h.close-h.low)/Math.max(.01,h.high-h.low),0,1);
            if(distance< -2.5||distance>4.0||daily<0.25||daily>8.0||location<.55)continue;
            double strength=40*clamp(daily/5.,0,1)+30*location+
              30*clamp(1-Math.abs(distance)/4.,0,1);
            item=new JSONObject().put("symbol",b.symbol).put("price",h.close)
              .put("priceKind","LAST_COMPLETED_SESSION_CLOSE_NOT_LIVE")
              .put("priceAsOfSession",h.sessionDate).put("dayChangePct",daily)
              .put("fromOpenPct",100*(h.close/h.open-1))
              .put("breakoutLevel",h.priorHigh20).put("distancePct",distance)
              .put("stage","NEXT SESSION WATCH").put("preliminaryRank",strength)
              .put("priorDataAsOf",h.sessionDate).put("completedSessions",h.completedSessions)
              .put("approxAverageTradedValue20Inr",h.avgValue20)
              .put("liquidityValueEstimated",true).put("volume",h.volume)
              .put("relativeVolume20",h.avgVolume20>0?h.volume/h.avgVolume20:JSONObject.NULL)
              .put("tradeMs",0).put("dailyCandleMs",h.candleMs)
              .put("observedMs",System.currentTimeMillis())
              .put("status","NEXT_SESSION_RESEARCH").put("signal","NO SIGNAL")
              .put("why","Prior session technical shortlist; complete Alpha-30 and live entry gates pending");
          }else{
            GrowwReadOnlyClient.History h=groww.history60(b.symbol);
            if(h.averageValue20<10_000_000 || h.high20<=0)continue;
            GrowwReadOnlyClient.Market q=groww.quote(b.symbol);
            long observed=System.currentTimeMillis();
            if(q.lastTradeMs<=0||q.lastTradeMs>observed+3000||
                observed-q.lastTradeMs>60_000L)continue;
            if(!Double.isFinite(q.price)||q.price<=0)continue;
            double distance=100*(q.price/h.high20-1);
            if(distance< -2.5||distance>4.0)continue;
            double daily=100*(q.price/h.previousClose-1);
            if(daily>8.0||daily<0.25)continue;
            double nearBonus=30*clamp(1-Math.abs(distance)/4.,0,1);
            double strength=40*clamp(daily/5.,0,1)+30*b.location+nearBonus;
            String stage=distance>=0?"BREAKOUT WATCH":distance>=-1.5?"NEAR BREAKOUT":"WATCHING";
            item=new JSONObject()
              .put("symbol",b.symbol).put("price",q.price)
              .put("dayChangePct",daily).put("fromOpenPct",b.move)
              .put("breakoutLevel",h.high20).put("distancePct",distance)
              .put("stage",stage).put("preliminaryRank",strength)
              .put("priorDataAsOf",h.asOf).put("completedSessions",h.sessions)
              .put("approxAverageTradedValue20Inr",h.averageValue20)
              .put("liquidityValueEstimated",true).put("volume",q.dayVolume)
              .put("averageTradePrice",q.vwap).put("tradeMs",q.lastTradeMs)
              .put("observedMs",observed).put("status","RESEARCH ONLY")
              .put("signal","NO SIGNAL")
              .put("why","30-factor fundamentals, catalysts, macro and execution gates not verified");
          }
          candidates.add(item);valid++;
        }catch(SecurityException auth){throw auth;}
         catch(Exception invalid){ /* intentionally not promoted to a validated candidate */ }
        Thread.sleep(300);
      }
      if(afterHours && !candidates.isEmpty()){
        // Never mix stale previous-session records with candidates from a newer
        // completed NSE trading session. All displayed EOD watches share a date.
        String latestSession="";
        for(JSONObject candidate:candidates){
          String session=candidate.optString("priceAsOfSession","");
          if(session.compareTo(latestSession)>0)latestSession=session;
        }
        String commonSession=latestSession;
        candidates.removeIf(candidate->
          !commonSession.equals(candidate.optString("priceAsOfSession","")));
        valid=candidates.size();
      }
      candidates.sort((a,b)->Double.compare(
        b.optDouble("preliminaryRank",0),a.optDouble("preliminaryRank",0)));
      JSONArray top=new JSONArray();
      for(int i=0;i<Math.min(3,candidates.size());i++)top.put(candidates.get(i));
      long now=System.currentTimeMillis();
      JSONObject result=new JSONObject()
         .put("version","on-device-discovery-v1")
         .put("modelVersion",Alpha30Engine.VERSION)
         .put("formulaUnchanged",true)
         .put("scannedAtMs",now).put("scanStartedMs",scanStarted).put("universeCount",universe.size())
         .put("quotedCount",prices.size()).put("batchesSucceeded",successfulBatches)
         .put("batchesFailed",failedBatches).put("broadCandidates",broad.size())
         .put("dailyHistoryVerified",checked).put("eligibleForResearch",valid)
         .put("scanCompleteness","Partial: individual histories verified only for leading "+checked)
         .put("reason",afterHours?
            "Next-session shortlist from completed daily evidence; not a live BUY":
            "Technical discovery ranking, NOT Alpha-30 BUY")
         .put("scanMode",afterHours?"NEXT_SESSION_RESEARCH":"INTRADAY_RESEARCH")
         .put("priceStatus",afterHours?"LAST_COMPLETED_SESSION_NOT_LIVE":"INTRADAY_OBSERVED")
         .put("sourceSessionDate",afterHours&&top.length()>0?
           top.optJSONObject(0).optString("priceAsOfSession",""):"")
         .put("top",top);
      save(result);
      return result;
    }catch(Exception ex){failure(ex.getMessage());throw ex;}
     finally{RUNNING.set(false);}
  }
}
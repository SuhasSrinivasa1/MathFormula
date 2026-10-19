package com.mathformula.alpha;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phone-side read-only breadth sweep. Does not evaluate Alpha-30 BUY or invent fundamentals.
 * Uses fresh Groww batch LTP/OHLC, and only LAST completed-session cached historical references.
 * Android JobScheduler cannot guarantee unattended full-market coverage; report all observation gaps.
 */
final class IntradayRadar {
  private static final String PREF="mathformula_intraday_radar_v30";
  private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
  private static final long INTERVAL_MS=15*60_000L;
  private final Context app;
  private final SharedPreferences prefs;
  private final SharedPreferences history;
  interface Progress { void update(String message); }
  IntradayRadar(Context c){
    app=c.getApplicationContext();
    prefs=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);
    history=app.getSharedPreferences("mathformula_eod_cache_v31",Context.MODE_PRIVATE);
  }
  private static String date(){
    return LocalDate.now(MarketSchedule.IST).toString();
  }
  private static String completedSession(){
    ZonedDateTime now=ZonedDateTime.now(MarketSchedule.IST);
    java.time.LocalDate day=now.toLocalDate().minusDays(1);
    return NseEquityCalendar.previous(day).toString();
  }
  private static double field(Object data,String key){
    if(data instanceof JSONObject)return ((JSONObject)data).optDouble(key,Double.NaN);
    if(data==null)return Double.NaN;
    java.util.regex.Matcher m=java.util.regex.Pattern.compile(
       "(?i)\\b"+key+"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(String.valueOf(data));
    if(!m.find())return Double.NaN;
    try{return Double.parseDouble(m.group(1));}catch(Exception ex){return Double.NaN;}
  }
  private static double clamp(double x,double min,double max){return Math.max(min,Math.min(max,x));}
  boolean running(){return RUNNING.get();}
  long attemptedAt(){return prefs.getLong("attemptMs",0);}
  long completedAt(){return prefs.getLong("completedMs",0);}
  String issue(){return prefs.getString("issue","");}
  JSONObject latest(){
    try{return new JSONObject(prefs.getString("latest","{}"));}
    catch(Exception e){return new JSONObject();}
  }
  boolean fresh(){
    JSONObject item=latest();
    long now=System.currentTimeMillis(),when=item.optLong("observedMs",0);
    return date().equals(item.optString("session","")) &&
        when>0&&now>=when&&now-when<20*60_000L;
  }
  JSONArray currentCandidates(){
    return fresh()?latest().optJSONArray("top"):new JSONArray();
  }
  boolean due(){
    return MarketSchedule.weekdayTradingTime(System.currentTimeMillis()) &&
      !RUNNING.get() &&
      (attemptedAt()==0 || System.currentTimeMillis()-attemptedAt()>=INTERVAL_MS);
  }
  JSONObject scan(GrowwReadOnlyClient api,LocalDiscovery discovery,Progress report) throws Exception{
    if(!MarketSchedule.weekdayTradingTime(System.currentTimeMillis()))
      throw new IllegalStateException("NSE session not open");
    if(!RUNNING.compareAndSet(false,true))
      throw new IllegalStateException("Intraday radar already active");
    long started=System.currentTimeMillis();
    prefs.edit().putLong("attemptMs",started).putString("issue","")
      .putString("state","RUNNING").commit();
    int quotes=0,batchesGood=0,batchesFailed=0,cachedReferences=0;
    try{
      List<String> universe=discovery.instrumentSymbols(s->report.update(s));
      List<JSONObject> leads=new ArrayList<>();
      String refDate=completedSession(),session=date();
      for(int i=0;i<universe.size();i+=50){
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("Radar interrupted");
        List<String> symbols=universe.subList(i,Math.min(universe.size(),i+50));
        JSONObject ltp,ohlc;
        try {
          ltp=api.batch("ltp",symbols);
          ohlc=api.batch("ohlc",symbols);
          batchesGood++;
        }catch(SecurityException ex){throw ex;}
         catch(Exception ex){
          if(ex.getMessage()!=null &&
             (ex.getMessage().contains("429")||ex.getMessage().contains("cooldown")))
             throw ex;
          batchesFailed++;continue;
        }
        for(String symbol:symbols){
          double price=ltp.optDouble("NSE_"+symbol,Double.NaN);
          if(!(price>0&&Double.isFinite(price)))continue;
          quotes++;
          Object candle=ohlc.opt("NSE_"+symbol);
          double open=field(candle,"open"),high=field(candle,"high"),
                 low=field(candle,"low");
          if(!(open>0&&Double.isFinite(high)&&Double.isFinite(low)&&high>=low))continue;
          double gain=100*(price/open-1);
          if(price<25||price>15000||gain<0.25)continue; // retain outsized moves as high-risk research, not BUY
          double location=clamp((price-low)/Math.max(.01,high-low),0,1);
          if(location<.60)continue;
          JSONObject cache;
          try{cache=new JSONObject(history.getString(symbol,"{}"));}
          catch(Exception ex){cache=new JSONObject();}
          boolean verified=refDate.equals(cache.optString("cutoff")) &&
            cache.optInt("sessions",0)>=61 &&
            cache.optDouble("avgValue20",0)>=10_000_000 &&
            cache.optDouble("nextHigh20",0)>0;
          double h20=verified?cache.optDouble("nextHigh20"):Double.NaN;
          if(verified)cachedReferences++;
          double distance=verified?100*(price/h20-1):Double.NaN;
          // Do not call unknown H20 a breakout. Verified near-breakout candidates
          // are promoted first; other movers remain conspicuously preliminary.
          if(verified&&(distance< -3||distance>4))continue;
          double technical=40*clamp(gain/6,0,1)+35*location+
            (verified?25*clamp(1-Math.abs(distance)/4,0,1):0);
          JSONObject row=new JSONObject()
            .put("symbol",symbol).put("price",price).put("fromOpenPct",gain)
            .put("sessionLocation",location).put("preliminaryRank",technical)
            .put("historyVerified",verified).put("h20AsOf",verified?refDate:"")
            .put("breakoutLevel",verified?h20:JSONObject.NULL)
            .put("distancePct",verified?distance:JSONObject.NULL)
            .put("signal","NO_SIGNAL")
            .put("status",verified?"RESEARCH WATCH · EOD REFERENCE VERIFIED":
                     "PRELIMINARY · H20 NOT VERIFIED")
            .put("source","Groww batch LTP + OHLC; no exchange last-trade timestamp")
            .put("reason","No 30-factor score, completed breakout candles, spread or risk plan verified");
          leads.add(row);
        }
        if(i%500==0)report.update("Intraday breadth "+Math.min(i+50,universe.size())+
           "/"+universe.size()+" NSE symbols · "+quotes+" quotes");
        Thread.sleep(250);
      }
      if(quotes<Math.max(200,(int)(universe.size()*.85)))
        throw new IllegalStateException("Incomplete intraday NSE price coverage: "+quotes+
          "/"+universe.size());
      leads.sort((a,b)->{
        int verified=Boolean.compare(b.optBoolean("historyVerified"),a.optBoolean("historyVerified"));
        return verified!=0?verified:
          Double.compare(b.optDouble("preliminaryRank"),a.optDouble("preliminaryRank"));
      });
      JSONArray top=new JSONArray();
      for(int i=0;i<Math.min(120,leads.size());i++)top.put(leads.get(i));
      JSONObject result=new JSONObject()
        .put("session",session).put("observedMs",System.currentTimeMillis())
        .put("scannedUniverse",universe.size()).put("quotesLoaded",quotes)
        .put("batchesSucceeded",batchesGood).put("batchesFailed",batchesFailed)
        .put("historicalReferencesMatched",cachedReferences)
        .put("technicalLeads",leads.size()).put("top",top)
        .put("status","RESEARCH ONLY · NOT A LIVE ALPHA-30 BUY")
        .put("formulaVersion",Alpha30Engine.VERSION)
        .put("dataLimitation","Bulk LTP lacks exchange trade timestamp; full financial/macro factor coverage unavailable");
      new WatchStore(app).audit("RADAR","NSE","RUNNING","COMPLETED",
        "NSE screened "+universe.size()+" quotes "+quotes+" cached references "+
        cachedReferences+" leads "+leads.size()+" failed batches "+batchesFailed,0);
      prefs.edit().putString("latest",result.toString())
        .putLong("completedMs",System.currentTimeMillis())
        .putString("state","COMPLETED").putString("issue","").commit();
      return result;
    }catch(Exception ex){
      String reason=ex instanceof SecurityException?"GROWW_AUTH_OR_APPROVAL":
        ex instanceof InterruptedException?"INTERRUPTED":
        ex.getMessage()!=null&&ex.getMessage().contains("429")?"GROWW_RATE_LIMIT":
        "DATA_OR_NETWORK_FAILURE";
      prefs.edit().putString("state","FAILED").putString("issue",reason)
        .putString("failureAt",String.valueOf(System.currentTimeMillis())).commit();
      new WatchStore(app).audit("RADAR","NSE","RUNNING","FAILED",reason,0);
      throw ex;
    }finally{RUNNING.set(false);}
  }
}

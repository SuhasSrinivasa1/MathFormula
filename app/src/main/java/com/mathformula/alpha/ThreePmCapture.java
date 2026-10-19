package com.mathformula.alpha;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Immutable 3 PM observed Groww research snapshot. Not a validated BUY or executed trade. */
final class ThreePmCapture {
  private static final String PREF="mathformula_3pm_v41";
  private final Context app;
  private final SharedPreferences prefs;
  ThreePmCapture(Context context){
    app=context.getApplicationContext();
    prefs=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);
  }
  private String today(){return java.time.LocalDate.now(MarketSchedule.IST).toString();}
  JSONObject latest(){
    try{return new JSONObject(prefs.getString("latest","{}"));}
    catch(Exception ignored){return new JSONObject();}
  }
  JSONArray history(){
    try{return new JSONArray(prefs.getString("history","[]"));}
    catch(Exception ignored){return new JSONArray();}
  }
  String status(){return prefs.getString("state","NOT CAPTURED");}
  static boolean captureWindow(long now){
    ZonedDateTime z=Instant.ofEpochMilli(now).atZone(MarketSchedule.IST);
    LocalTime t=z.toLocalTime();
    return NseEquityCalendar.session(z.toLocalDate()) &&
      !t.isBefore(LocalTime.of(15,0))&&t.isBefore(LocalTime.of(15,2));
  }
  boolean due(){
    long now=System.currentTimeMillis();
    return captureWindow(now)&&!today().equals(latest().optString("date",""))&&
      now-prefs.getLong("lastAttemptMs",0)>20_000L;
  }
  /** Captures real Groww LTP in the 15:00-15:02 observation window. */
  synchronized JSONObject capture(GrowwReadOnlyClient groww,JSONArray source)throws Exception{
    long now=System.currentTimeMillis();String day=today();
    if(!captureWindow(now))throw new IllegalStateException("3 PM point-in-time window is closed");
    if(day.equals(latest().optString("date","")))return latest();
    prefs.edit().putLong("lastAttemptMs",now).apply();
    if(source==null||source.length()==0){
      prefs.edit().putString("state","NO RECENT NSE RADAR · Freeze unfilled").apply();
      return new JSONObject();
    }
    AmfiClassification classification=new AmfiClassification(app);
    GrowwCapAuto caps=new GrowwCapAuto(app);
    JSONArray[] partitions={new JSONArray(),new JSONArray(),new JSONArray()};
    HashSet<String> seen=new HashSet<>();
    List<String> symbols=new ArrayList<>();
    for(int i=0;i<source.length()&&symbols.size()<9;i++){
      JSONObject row=source.optJSONObject(i);if(row==null)continue;
      String symbol=row.optString("symbol","");
      String cap=classification.bucket(symbol);
      if(cap.isEmpty())cap=caps.bucket(symbol);
      int index="SMALL".equals(cap)?0:"MID".equals(cap)?1:"LARGE".equals(cap)?2:-1;
      if(index<0||partitions[index].length()>=3||!seen.add(symbol))continue;
      symbols.add(symbol);
      partitions[index].put(row);
    }
    // Batch quote refresh takes place inside the capture window; exchange trade
    // timestamps are not available from bulk LTP and are never invented.
    JSONObject prices=new JSONObject();
    if(!symbols.isEmpty()){
      JSONObject payload=groww.batch("ltp",symbols);
      for(String symbol:symbols){
        double price=payload.optDouble("NSE_"+symbol,Double.NaN);
        if(Double.isFinite(price)&&price>0)prices.put(symbol,price);
      }
    }
    long observed=System.currentTimeMillis();
    if(!captureWindow(observed)){
      prefs.edit().putString("state","MISSED · Groww response arrived outside 3 PM window").apply();
      return new JSONObject();
    }
    JSONArray rows=new JSONArray();
    for(int i=0;i<partitions.length;i++)for(int j=0;j<partitions[i].length();j++){
      JSONObject sourceRow=partitions[i].optJSONObject(j);
      if(sourceRow==null)continue;
      String symbol=sourceRow.optString("symbol","");
      double price=prices.optDouble(symbol,Double.NaN);
      if(!Double.isFinite(price)||price<=0)continue;
      JSONObject row=new JSONObject(sourceRow.toString())
        .put("price",price)
        .put("quotePolledMs",observed)
        .put("tradeMs",0)
        .put("amfiCap",i==0?"SMALL":i==1?"MID":"LARGE")
        .put("capImported",!classification.bucket(symbol).isEmpty())
        .put("capEstimated",classification.bucket(symbol).isEmpty())
        .put("capVerified",false)
        .put("signal","NO_SIGNAL")
        .put("entryEligible",false)
        .put("status","FROZEN RESEARCH · MODEL UNVERIFIED")
        .put("explanation","Genuine Groww LTP near 15:00; trade timestamp and 30/40 factor evidence incomplete");
      JSONObject scores=new DualFactorEvidence(app).report(symbol,observed);
      row.put("alpha30FactorCount",scores.optInt("alpha30FactorCount"))
        .put("alpha40FactorCount",scores.optInt("alpha40FactorCount"))
        .put("alpha30Score",scores.opt("alpha30Score"))
        .put("alpha40Score",scores.opt("alpha40Score"))
        .put("alpha30ScoreVerified",false)
        .put("alpha40ScoreVerified",false);
      rows.put(row);
    }
    JSONObject snapshot=new JSONObject().put("date",day)
      .put("observedAtMs",observed).put("window","15:00:00–15:01:59 IST")
      .put("model",DualScoringModel.VERSION)
      .put("calendarSource",NseEquityCalendar.SOURCE)
      .put("targetTradingSession",NseEquityCalendar.next(Instant.ofEpochMilli(observed).atZone(MarketSchedule.IST).toLocalDate()).toString())
      .put("state",rows.length()>=5?"FROZEN PROVISIONAL · FULL SCORE PENDING":
          "FROZEN INCOMPLETE · fewer than 5 imported-cap candidates")
      .put("capProvenance",classification.count()>0?classification.origin():
        "GROWW_CURRENT_MARKET_CAP_ESTIMATE_NOT_OFFICIAL_AMFI_AVERAGE")
      .put("scannedUniverse",new IntradayRadar(app).latest().optInt("scannedUniverse",0))
      .put("radarObservedMs",new IntradayRadar(app).latest().optLong("observedMs",0))
      .put("recommendations",rows)
      .put("noTradingOrders",true);
    JSONArray old=history(),updates=new JSONArray();
    for(int k=Math.max(0,old.length()-59);k<old.length();k++){
      JSONObject prior=old.optJSONObject(k);
      if(prior!=null&&!day.equals(prior.optString("date")))updates.put(prior);
    }
    updates.put(snapshot);
    boolean ok=prefs.edit().putString("latest",snapshot.toString())
      .putString("history",updates.toString()).putString("state",snapshot.optString("state"))
      .putLong("capturedAtMs",observed).commit();
    if(!ok)throw new IllegalStateException("Could not durably persist 3 PM record");
    new WatchStore(app).audit("NEXT_DAY_FREEZE","NSE","OPEN","FROZEN",
      "Saved "+rows.length()+" source-observed candidates; not validated BUY",0);
    return snapshot;
  }
  JSONObject audit(){
    JSONObject p=latest(),j=new JSONObject();
    try{j.put("state",status()).put("lastAttemptMs",prefs.getLong("lastAttemptMs",0L))
      .put("current",p).put("historyCount",history().length());}
    catch(Exception ignored){}
    return j;
  }
}

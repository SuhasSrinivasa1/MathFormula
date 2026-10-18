package com.mathformula.alpha;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Next-session closing-price research audit. Real Groww EOD history, never simulated
 * trades or executable fills. A "WIN" is a research-direction objective, not profit.
 * Missing next-session or exchange holiday verification yields UNVERIFIED.
 */
final class ForecastOutcomes {
  private static final String PREF="mathformula_forecast_outcomes_v41";
  private final Context app;
  private final SharedPreferences prefs;
  ForecastOutcomes(Context c){
    app=c.getApplicationContext();
    prefs=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);
  }
  JSONArray closed(){
    try{return new JSONArray(prefs.getString("closed","[]"));}
    catch(Exception ignored){return new JSONArray();}
  }
  JSONObject audit(){
    JSONObject r=new JSONObject();
    try{JSONArray rows=closed();r.put("records",rows).put("count",rows.length())
      .put("lastEvaluationMs",prefs.getLong("evaluatedMs",0))
      .put("method","Next available completed Groww EOD close vs 3 PM observed LTP; no broker fill, fees or execution inferred");}
    catch(Exception ignored){}
    return r;
  }
  private static String key(String date,String symbol){return date+"|"+symbol;}
  /** At most 2 historical calls per job. Groww quota limiter independently enforces 3/min. */
  int inspectNextSession(GrowwReadOnlyClient client){
    java.time.ZonedDateTime time=Instant.now().atZone(MarketSchedule.IST);
    if(time.toLocalTime().isBefore(java.time.LocalTime.of(16,30)))return 0;
    JSONArray frozenHistory=new ThreePmCapture(app).history();
    JSONArray existing=closed();
    HashSet<String> done=new HashSet<>();
    for(int k=0;k<existing.length();k++){
      JSONObject item=existing.optJSONObject(k);if(item!=null)
        done.add(key(item.optString("freezeDate"),item.optString("symbol")));
    }
    int tested=0;
    for(int h=0;h<frozenHistory.length()&&tested<2;h++){
      JSONObject batch=frozenHistory.optJSONObject(h);if(batch==null)continue;
      String freezeDate=batch.optString("date","");
      JSONArray picks=batch.optJSONArray("recommendations");
      if(picks==null)continue;
      for(int i=0;i<picks.length()&&tested<2;i++){
        JSONObject pick=picks.optJSONObject(i);if(pick==null)continue;
        String symbol=pick.optString("symbol","");
        String recordKey=key(freezeDate,symbol);
        if(!symbol.matches("[A-Z0-9&-]{1,26}")||done.contains(recordKey))continue;
        try{
          LocalDate date=LocalDate.parse(freezeDate);
          if(!date.isBefore(time.toLocalDate()))continue;
          double start=pick.optDouble("price",Double.NaN);
          if(!(start>0))continue;
          GrowwReadOnlyClient.NextSessionHistory bar=client.nextSessionHistory(symbol);
          LocalDate session=LocalDate.parse(bar.sessionDate);
          if(!session.isAfter(date))continue;
          double close=bar.close;
          if(!Double.isFinite(close)||close<=0)continue;
          double change=100*(close/start-1);
          // A +2% close is the objective; -0% is an adverse directional result.
          // It does NOT mean a real trade could have been made at 15:00.
          String result=change>=2.0?"WIN":change<0?"LOSS":"NEUTRAL";
          JSONObject item=new JSONObject()
            .put("symbol",symbol).put("freezeDate",freezeDate)
            .put("referenceLtp",start)
            .put("sessionDate",bar.sessionDate)
            .put("referenceClose",close)
            .put("observedHigh",bar.high).put("observedLow",bar.low)
            .put("referenceReturnPct",change)
            .put("outcomeVerified",false) // NSE holiday & exact next-session verification still missing
            .put("outcome",result).put("outcomeType","UNVERIFIED_RESEARCH_DIRECTION_NOT_A_TRADE")
            .put("reason",result.equals("WIN")?
                "Observed subsequent close exceeded frozen LTP by >=2%; not an executable fill":
                result.equals("LOSS")?
                "Observed subsequent close was below 3 PM reference; causes require sector/news evidence":
                "Observed change was between 0 and +2%; objective not confirmed")
            .put("remediation","Review sector relative strength, catalyst age, VWAP context and late-day exhaustion. Change weights only after out-of-sample validation.")
            .put("source","Groww completed daily historical candle")
            .put("observedAtMs",System.currentTimeMillis())
            .put("needsNseCalendarVerification",true);
          existing.put(item);done.add(recordKey);tested++;
          new WatchStore(app).audit("FORECAST_EVALUATION",symbol,
            "FROZEN_RESEARCH",result,WatchStore.safeExplanation(item.optString("reason")),0);
        }catch(Exception invalid){
          // Do not close or guess an outcome if history is missing.
        }
      }
    }
    if(tested>0){
      JSONArray trimmed=new JSONArray();
      for(int i=Math.max(0,existing.length()-500);i<existing.length();i++)
        trimmed.put(existing.optJSONObject(i));
      prefs.edit().putString("closed",trimmed.toString())
        .putLong("evaluatedMs",System.currentTimeMillis()).apply();
    }
    return tested;
  }
}

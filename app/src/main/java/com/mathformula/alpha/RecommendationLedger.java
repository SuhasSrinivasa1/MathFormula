package com.mathformula.alpha;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.util.HashSet;
import java.util.Set;

/**
 * Local, auditable research-recommendation lifecycle.
 * This NEVER generates BUY: frozen Alpha-30 mandatory inputs are not available
 * in the phone-only technical feed. A price crossing resistance is not a BUY.
 *
 * Ranking changes never imply a closed/failed BUY. Only a verified server may close a signal. Quote refreshes may
 * annotate an existing watch, but never fabricate a full model decision.
 */
final class RecommendationLedger {
  private static final String PREF="mathformula_recommendation_ledger_v20";
  private static final int CLOSED_LIMIT=150;
  private final SharedPreferences prefs;
  RecommendationLedger(Context context) {
    prefs=context.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
  }
  private JSONArray read(String key) {
    try { return new JSONArray(prefs.getString(key,"[]")); }
    catch(Exception e) { return new JSONArray(); }
  }
  synchronized JSONArray live() { return read("live"); }
  synchronized JSONArray closed() { return read("closed"); }
  private static String date(long ms) {
    return Instant.ofEpochMilli(ms).atZone(MarketSchedule.IST).toLocalDate().toString();
  }
  private static boolean hasThreeSessionsElapsed(long begin,long now) {
    if(begin<=0||now<=begin)return false;
    LocalDate day=Instant.ofEpochMilli(begin).atZone(MarketSchedule.IST).toLocalDate();
    LocalDate until=Instant.ofEpochMilli(now).atZone(MarketSchedule.IST).toLocalDate();
    int count=0;
    while(day.isBefore(until) && count<3) {
      day=day.plusDays(1);
      DayOfWeek dow=day.getDayOfWeek();
      if(dow!=DayOfWeek.SATURDAY&&dow!=DayOfWeek.SUNDAY)count++;
    }
    // Exchange holidays are not in this device-side calendar; require next scan
    // for actual closure. Do not claim three confirmed exchange sessions.
    return count>=3;
  }
  private static JSONObject copy(JSONObject item) {
    try { return new JSONObject(item.toString()); }
    catch(Exception e) { return new JSONObject(); }
  }
  private static void set(JSONObject item,String key,Object value){
    try{item.put(key,value);}catch(Exception ignored){}
  }
  private static JSONObject finish(JSONObject item,String code,String explanation,long at) {
    JSONObject row=copy(item);
    set(row,"status","CLOSED");
    set(row,"closeCode",code);
    set(row,"closeReason",explanation);
    set(row,"closedAtMs",at);
    set(row,"signal","NO SIGNAL");
    return row;
  }
  private void store(JSONArray live,JSONArray closed) {
    JSONArray trimmed=new JSONArray();
    for(int i=Math.max(0,closed.length()-CLOSED_LIMIT);i<closed.length();i++){
      JSONObject row=closed.optJSONObject(i);
      if(row!=null)trimmed.put(row);
    }
    prefs.edit().putString("live",live.toString())
      .putString("closed",trimmed.toString()).apply();
  }
  /** A successful, completed full discovery scan alone changes shortlist membership. */
  synchronized void onCompletedScan(JSONObject scan) {
    long now=scan.optLong("scannedAtMs",System.currentTimeMillis());
    boolean nextSession="NEXT_SESSION_RESEARCH".equals(scan.optString("scanMode",""));
    JSONArray discovered=scan.optJSONArray("top");
    if(discovered==null)return;
    JSONArray former=live(), next=new JSONArray(), archive=closed();
    Set<String> seen=new HashSet<>();
    for(int i=0;i<discovered.length();i++){
      JSONObject pick=discovered.optJSONObject(i);
      if(pick==null)continue;
      String symbol=pick.optString("symbol","");
      if(!symbol.matches("[A-Z0-9&-]{1,26}")||!seen.add(symbol))continue;
      JSONObject old=null;
      for(int j=0;j<former.length();j++) {
        JSONObject candidate=former.optJSONObject(j);
        if(candidate!=null&&symbol.equals(candidate.optString("symbol"))) {
          old=candidate;break;
        }
      }
      JSONObject row=old==null?new JSONObject():copy(old);
      long started=old==null?now:old.optLong("openedAtMs",now);
      set(row,"id",old==null?symbol+"_"+now:old.optString("id",symbol+"_"+now));
      set(row,"symbol",symbol);
      set(row,"openedAtMs",started);
      set(row,"lastSeenAtMs",now);
      set(row,"lastScanAtMs",now);
      set(row,"missedScans",0);
      set(row,"price",pick.opt("price"));
      set(row,"breakoutLevel",pick.opt("breakoutLevel"));
      set(row,"dayChangePct",pick.opt("dayChangePct"));
      set(row,"distancePct",pick.opt("distancePct"));
      set(row,"tradeMs",pick.optLong("tradeMs",0));
      set(row,"priceAsOfSession",pick.optString("priceAsOfSession",""));
      set(row,"scanMode",nextSession?"NEXT_SESSION_RESEARCH":"INTRADAY_RESEARCH");
      set(row,"preliminaryRank",pick.opt("preliminaryRank"));
      set(row,"source","NSE technical research · Groww");
      set(row,"signal","NO SIGNAL");
      set(row,"modelVerified",false);
      double distance=pick.optDouble("distancePct",Double.NaN);
      boolean crossed=Double.isFinite(distance)&&distance>=0;
      set(row,"status",nextSession?"NEXT SESSION WATCH":crossed?"BREAKOUT OBSERVED":"WATCHING");
      set(row,"reason",nextSession?
        "Next-session candidate from the completed NSE candle dated "+
          pick.optString("priceAsOfSession","unknown")+
          "; live confirmation and full Alpha-30 gates pending":
        crossed?"Resistance crossed; full Alpha-30 verification, VWAP and risk/reward still incomplete":
          "Forward-looking technical watch; breakout and complete Alpha-30 gates pending");
      if(old==null)set(row,"firstSeenStage",crossed?"AFTER CROSS":"BEFORE CROSS");
      // A local device cannot verify the NSE holiday calendar or full model.
      // Do NOT close a watch on a weekday guess or an arbitrary 4% extension.
      if(!nextSession && crossed && Double.isFinite(distance)&&distance>4.0) {
        set(row,"status","EXTENDED · REVALIDATE");
        set(row,"reason","Extended beyond resistance. Entry is not verified; calculate full net risk/reward on the server.");
      }
      next.put(row);
    }
    for(int j=0;j<former.length();j++){
      JSONObject old=former.optJSONObject(j);
      if(old==null||seen.contains(old.optString("symbol")))continue;
      JSONObject updated=copy(old);
      int missed=old.optInt("missedScans",0)+1;
      set(updated,"missedScans",missed);
      set(updated,"status","REVALIDATION PENDING");
      set(updated,"reason","Not in latest top three. Rank loss alone cannot invalidate an opportunity.");
      next.put(updated);
    }
    store(next,archive);
  }
  /** Quote refresh cannot issue a BUY, rerank the full NSE universe or overwrite historical closure. */
  synchronized void onQuoteRefresh(JSONArray refreshed) {
    if(refreshed==null)return;
    JSONArray opened=live(),next=new JSONArray(),archive=closed();
    long now=System.currentTimeMillis();
    for(int i=0;i<opened.length();i++) {
      JSONObject old=opened.optJSONObject(i);if(old==null)continue;
      JSONObject row=copy(old);
      String symbol=row.optString("symbol","");
      JSONObject quote=null;
      for(int j=0;j<refreshed.length();j++){
        JSONObject candidate=refreshed.optJSONObject(j);
        if(candidate!=null&&symbol.equals(candidate.optString("symbol"))){quote=candidate;break;}
      }
      if(quote!=null){
        long trade=quote.optLong("tradeMs",0);
        if(trade>0&&trade<=now+3000 && now-trade<=60000){
          double price=quote.optDouble("price",Double.NaN);
          double breakout=row.optDouble("breakoutLevel",Double.NaN);
          if(Double.isFinite(price)&&price>0&&Double.isFinite(breakout)&&breakout>0) {
            double gap=100*(price/breakout-1);
            set(row,"price",price);set(row,"tradeMs",trade);
            set(row,"distancePct",gap);set(row,"lastSeenAtMs",now);
            if(gap>4.0){
              set(row,"status","EXTENDED · REVALIDATE");
              set(row,"reason","Price extended beyond resistance; the server must verify executable risk/reward.");
              next.put(row);
              continue;
            }
            if(gap>=0) {
              set(row,"status","BREAKOUT OBSERVED");
              set(row,"reason","Price crossed resistance; mandatory model gates are unverified, so NO SIGNAL");
            }
          }
        }
      }
      next.put(row);
    }
    store(next,archive);
  }
  static String friendly(String code) {
    if("ENTRY_MISSED".equals(code))return "Entry missed";
    if("EXPIRED".equals(code))return "Expired";
    if("NO_LONGER_SHORTLISTED".equals(code))return "No longer shortlisted";
    if("INVALIDATED".equals(code))return "Invalidated";
    if("NOT_QUALIFIED".equals(code))return "Not qualified";
    return "Closed";
  }
}

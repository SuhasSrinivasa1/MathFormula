package com.mathformula.alpha;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/**
 * No user CSV required: populate capitalization buckets from paid Groww
 * single-stock quote.market_cap. This is current market capitalization,
 * NOT the official six-month average AMFI classification.
 * Fails closed when market_cap is absent/invalid. Calls are quota-limited.
 */
final class GrowwCapAuto {
  private static final String PREF="mathformula_cap_auto_v42";
  private static final long DAY=86_400_000L;
  // Approximate cutoffs from AMFI Jan–Jun 2026 company ranking. Current Groww
  // market cap cannot independently establish official six-month AMFI ranks.
  private static final double MID_FLOOR_RUPEES=33_664d*10_000_000d;
  private static final double LARGE_FLOOR_RUPEES=106_346d*10_000_000d;
  private final SharedPreferences prefs;
  GrowwCapAuto(Context c){prefs=c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);}
  static String estimate(double rupees){
    if(!Double.isFinite(rupees)||rupees<=0)return "";
    if(rupees>=LARGE_FLOOR_RUPEES)return "LARGE";
    if(rupees>=MID_FLOOR_RUPEES)return "MID";
    return "SMALL";
  }
  String bucket(String symbol){
    try{
      JSONObject j=new JSONObject(prefs.getString(symbol,"{}"));
      long t=j.optLong("atMs",0);
      if(t<=0||System.currentTimeMillis()-t>14*DAY)return "";
      return estimate(j.optDouble("marketCapRupees",Double.NaN));
    }catch(Exception ignored){return "";}
  }
  int savedCount(){return prefs.getInt("successful",0);}
  long lastAt(){return prefs.getLong("lastAtMs",0);}
  void record(GrowwReadOnlyClient.Market m){
    if(m==null||m.symbol==null||!m.symbol.matches("[A-Z0-9&-]{1,26}")||
      estimate(m.marketCap).isEmpty())return;
    try{
      boolean fresh=!bucket(m.symbol).isEmpty();
      JSONObject j=new JSONObject().put("marketCapRupees",m.marketCap)
        .put("atMs",System.currentTimeMillis())
        .put("source","Groww paid /v1/live-data/quote market_cap")
        .put("capMethod","CURRENT_CAP_ESTIMATE_NOT_OFFICIAL_AMFI_AVERAGE");
      SharedPreferences.Editor e=prefs.edit()
        .putString(m.symbol,j.toString())
        .putLong("lastAtMs",System.currentTimeMillis());
      if(!fresh)e.putInt("successful",savedCount()+1);
      e.apply();
    }catch(Exception ignored){}
  }
  /**
   * Batch LTP cannot supply market_cap. Limit detailed quote calls to 12 per
   * attempt and let the shared Groww quota account for all other features.
   */
  int refresh(GrowwReadOnlyClient api,JSONArray leads,int maxRequests){
    if(leads==null||maxRequests<=0||GrowwQuota.cooling(api.context()))return 0;
    long now=System.currentTimeMillis();
    long old=prefs.getLong("lastAttemptMs",0);
    if(old>0&&now-old<15*60_000L)return 0;
    HashSet<String> seen=new HashSet<>();
    int attempted=0,received=0,withoutCap=0,zero=0,unparsed=0,quoteErrors=0;
    boolean cooling=false;
    for(int i=0;i<leads.length()&&attempted<Math.min(6,maxRequests);i++){
      JSONObject j=leads.optJSONObject(i);
      String symbol=j!=null?j.optString("symbol",""):leads.optString(i,"");
      if(!symbol.matches("[A-Z0-9&-]{1,26}")||!seen.add(symbol))continue;
      if(!bucket(symbol).isEmpty())continue;
      long missingAt=prefs.getLong("missing_cap_"+symbol,0L);
      if(missingAt>0&&now-missingAt<6*60*60_000L)continue;
      if(GrowwQuota.cooling(api.context())){cooling=true;break;}
      attempted++;
      try{
        GrowwReadOnlyClient.Market quote=api.quote(symbol);
        if(!estimate(quote.marketCap).isEmpty()){
          record(quote);received++;
        }else{
          if("MISSING_OR_NULL".equals(quote.marketCapFieldState))withoutCap++;
          else if("ZERO_OR_NEGATIVE".equals(quote.marketCapFieldState))zero++;
          else unparsed++;
          prefs.edit().putLong("missing_cap_"+symbol,now).apply();
        }
      }catch(SecurityException denied){quoteErrors++;break;}
       catch(Exception temporarilyUnavailable){
        quoteErrors++;
        String issue=String.valueOf(temporarilyUnavailable.getMessage()).toLowerCase(Locale.US);
        if(issue.contains("429")||issue.contains("cooldown")||
          issue.contains("auth")||issue.contains("rate limit")){
          cooling=true;break;
        }
      }
    }
    prefs.edit().putLong("lastAttemptMs",now)
      .putInt("lastAttempted",attempted).putInt("lastReceived",received)
      .putInt("lastMissingMarketCap",withoutCap).putInt("lastZeroMarketCap",zero)
      .putInt("lastUnparsedMarketCap",unparsed).putInt("lastQuoteErrors",quoteErrors)
      .putBoolean("lastStoppedForCooldown",cooling).apply();
    return received;
  }
  JSONArray enrich(JSONArray source){
    JSONArray result=new JSONArray();
    for(int i=0;source!=null&&i<source.length();i++){
      JSONObject j=source.optJSONObject(i);if(j==null)continue;
      try{
        JSONObject item=new JSONObject(j.toString());
        if(item.optString("amfiCap","").isEmpty()){
          String cap=bucket(item.optString("symbol",""));
          if(!cap.isEmpty()){
            item.put("amfiCap",cap).put("capEstimated",true)
              .put("capVerified",false).put("capImported",false)
              .put("capSource","Groww live market_cap estimate vs AMFI reference cutoffs; NOT official AMFI categorization");
          }
        }
        result.put(item);
      }catch(Exception ignored){}
    }
    return result;
  }
  JSONObject audit(){
    JSONObject j=new JSONObject();
    try{j.put("source","GROWW_QUOTE_MARKET_CAP").put("classification","ESTIMATE_NOT_AMFI_OFFICIAL")
      .put("largeCutoffCr",106346).put("midCutoffCr",33664)
      .put("savedFreshApprox",savedCount()).put("lastAtMs",lastAt())
      .put("lastAttemptMs",prefs.getLong("lastAttemptMs",0))
      .put("lastAttempted",prefs.getInt("lastAttempted",0))
      .put("lastReceived",prefs.getInt("lastReceived",0))
      .put("lastMissingMarketCap",prefs.getInt("lastMissingMarketCap",0))
      .put("lastZeroMarketCap",prefs.getInt("lastZeroMarketCap",0))
      .put("lastUnparsedMarketCap",prefs.getInt("lastUnparsedMarketCap",0))
      .put("lastQuoteErrors",prefs.getInt("lastQuoteErrors",0))
      .put("lastStoppedForCooldown",prefs.getBoolean("lastStoppedForCooldown",false))
      .put("note","Cap fields absent in provider responses are not invented; negative results cached six hours. Quote cap denomination must be verified from actual numeric payload.");}
    catch(Exception ignored){}
    return j;
  }
}

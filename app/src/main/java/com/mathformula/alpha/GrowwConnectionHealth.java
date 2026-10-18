package com.mathformula.alpha;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;

/**
 * Read-only Groww connection health. A saved TOTP configuration is NOT the same as
 * a permanently open connection. Only successful authenticated Groww responses
 * update lastVerifiedMs; stale timestamps do not signify a rejected token.
 */
final class GrowwConnectionHealth {
  private static final String PREF="mathformula_groww_connection_v22";
  private static final long HEALTH_INTERVAL=20*60_000L;
  private static final long RETRY_INTERVAL=5*60_000L;
  private static final String VERIFIED="groww_verified_at_v22";
  private static final String PROBED="groww_auto_probe_at_v43";
  private static final String ERROR="groww_auto_problem_v43";
  private static final String ERROR_KIND="groww_auto_problem_kind_v43";
  private static final String ERROR_AT="groww_auto_problem_at_v43";
  private static long lastWriteMs=0L;
  private static SharedPreferences prefs(Context c){
    return c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
  }
  static long verifiedAt(Context c){return prefs(c).getLong(VERIFIED,0);}
  static long lastProbeAt(Context c){return prefs(c).getLong(PROBED,0);}
  static String issue(Context c){return prefs(c).getString(ERROR,"");}
  static boolean accountActionNeeded(Context c){
    return "AUTH".equals(prefs(c).getString(ERROR_KIND,""));
  }
  static boolean needsProbe(Context c,boolean configured){
    if(!configured)return false;
    long now=System.currentTimeMillis();
    long good=verifiedAt(c),attempt=lastProbeAt(c);
    return (good<=0||now-good>HEALTH_INTERVAL)&&
      (attempt<=0||now-attempt>RETRY_INTERVAL);
  }
  static void markProbe(Context c){
    prefs(c).edit().putLong(PROBED,System.currentTimeMillis()).apply();
  }
  static synchronized void success(Context c){
    long now=System.currentTimeMillis();
    // Throttle disk writes while a legitimate NSE-wide feed is active.
    if(now-lastWriteMs<30_000L && verifiedAt(c)>0 && !accountActionNeeded(c))return;
    prefs(c).edit().putLong(VERIFIED,now)
      .remove(ERROR).remove(ERROR_KIND).remove(ERROR_AT).apply();
    lastWriteMs=now;
  }
  static void marketTrade(Context c,long tradeAt){
    prefs(c).edit().putLong("groww_verified_last_trade_v22",tradeAt).apply();
  }
  static void failure(Context c,Throwable t){
    String e=t==null?"":String.valueOf(t.getMessage()).toLowerCase(Locale.US);
    boolean auth=e.contains("403")||e.contains("401")||
      e.contains("approval")||e.contains("token rejected")||
      e.contains("totp rejected");
    String message=auth?"Groww requires renewed API authorization or approval":
      e.contains("429")||e.contains("cooldown")?"Groww request budget/cooldown: retry scheduled":
      e.contains("timed out")||e.contains("timeout")?"Network timed out: will retry automatically":
      "Groww verification temporarily unavailable: credentials retained";
    prefs(c).edit().putString(ERROR,message)
      .putString(ERROR_KIND,auth?"AUTH":"TEMPORARY")
      .putLong(ERROR_AT,System.currentTimeMillis()).apply();
  }
  static String label(Context c,boolean configured){
    if(!configured)return "Groww not configured";
    if(accountActionNeeded(c))return "Groww approval required · credentials retained";
    long good=verifiedAt(c),now=System.currentTimeMillis();
    if(good>0 && now>=good && now-good<=HEALTH_INTERVAL)
      return "Groww authenticated · auto-renew enabled";
    if(good>0)
      return "Groww saved · last verified "+(Math.max(0,now-good)/60000)+"m ago · auto-check pending";
    return "Groww credentials saved · automatic verification pending";
  }
}

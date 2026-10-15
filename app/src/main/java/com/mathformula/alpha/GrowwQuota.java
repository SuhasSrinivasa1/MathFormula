package com.mathformula.alpha;

import android.os.SystemClock;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayDeque;

/**
 * Shared in-process Groww API request governor. Applies to HomeActivity, JobScheduler,
 * ScannerService and discovery; all use GrowwReadOnlyClient.call().
 *
 * Groww published REST group limit: LIVE DATA 10/second and 300/minute.
 * Conservative internal budget: 2/sec, 60/min.
 * Groww does not publish an independent historical/backtesting per-minute quota
 * on its REST introduction page. Reserve at most 3 historical requests/minute.
 * External account usage is unknowable; a 429 must halt and cool down.
 * Account-wide usage by other apps/processes is NOT observable here.
 */
final class GrowwQuota {
  private static final int LIVE_SECOND=2, LIVE_MINUTE=60, AUTH_DAY=90;
  private static final int HISTORY_MINUTE=3;
  private static final long HISTORY_INTERVAL_MS=21000L;
  private static final String PREF="mathformula_quota_guard_v28";
  private static final Object LOCK=new Object();
  private static final ArrayDeque<Long> liveSecond=new ArrayDeque<>();
  private static final ArrayDeque<Long> liveMinute=new ArrayDeque<>();
  private static final ArrayDeque<Long> historicalMinute=new ArrayDeque<>();
  private static long blockedUntil=0;
  private static long lastHistorical=0;
  private GrowwQuota(){}
  static void acquire(String path,Context context) throws InterruptedException {
    // Persist the server-imposed account cooldown across Android process restarts.
    android.content.SharedPreferences prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE);
    long enforced=prefs.getLong("cooldownWallMs",0L);
    long remaining=enforced-System.currentTimeMillis();
    if(remaining>0)throw new IllegalStateException(
      "Groww API cooldown active; pause scanning for "+((remaining+59999)/60000)+" minutes");
    final boolean auth=path.equals("/v1/token/api/access");
    final boolean live=path.startsWith("/v1/live-data/");
    if(auth){
      SharedPreferences p=context.getSharedPreferences("groww_quota_v19",Context.MODE_PRIVATE);
      String day=java.time.LocalDate.now(MarketSchedule.IST).toString();
      synchronized(LOCK){
        int count=day.equals(p.getString("date",""))?p.getInt("auth",0):0;
        if(count>=AUTH_DAY)throw new IllegalStateException("Internal Groww auth daily budget reached");
        p.edit().putString("date",day).putInt("auth",count+1).commit();
      }
      return;
    }
    if(!live && !path.startsWith("/v1/historical/"))
      throw new SecurityException("Groww API request type is not permitted");
    while(true){
      if(Thread.currentThread().isInterrupted())throw new InterruptedException("Groww request cancelled");
      long sleep;
      synchronized(LOCK){
        long now=SystemClock.elapsedRealtime();
        long hold=Math.max(0,blockedUntil-now);
        if(live){
          while(!liveSecond.isEmpty()&&now-liveSecond.peekFirst()>=1000)liveSecond.removeFirst();
          while(!liveMinute.isEmpty()&&now-liveMinute.peekFirst()>=60000)liveMinute.removeFirst();
          if(hold==0&&liveSecond.size()<LIVE_SECOND&&liveMinute.size()<LIVE_MINUTE){
            liveSecond.addLast(now);liveMinute.addLast(now);return;
          }
          long secondWait=liveSecond.size()>=LIVE_SECOND?1001-(now-liveSecond.peekFirst()):0;
          long minuteWait=liveMinute.size()>=LIVE_MINUTE?60001-(now-liveMinute.peekFirst()):0;
          sleep=Math.max(hold,Math.max(secondWait,minuteWait));
        }else{
          while(!historicalMinute.isEmpty()&&now-historicalMinute.peekFirst()>=60000)
            historicalMinute.removeFirst();
          if(hold==0&&historicalMinute.size()<HISTORY_MINUTE&&now-lastHistorical>=HISTORY_INTERVAL_MS){
            historicalMinute.addLast(now);lastHistorical=now;return;
          }
          sleep=Math.max(hold,Math.max(historicalMinute.size()>=HISTORY_MINUTE?
            60001-(now-historicalMinute.peekFirst()):0,HISTORY_INTERVAL_MS-(now-lastHistorical)));
        }
      }
      Thread.sleep(Math.min(Math.max(50,sleep),2000));
    }
  }
  static void backoff(Context context){
    long now=System.currentTimeMillis();
    android.content.SharedPreferences prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE);
    long next=Math.max(prefs.getLong("cooldownWallMs",0),now+30*60000L);
    prefs.edit().putLong("cooldownWallMs",next)
      .putLong("last429At",now)
      .putInt("total429",prefs.getInt("total429",0)+1).commit();
    synchronized(LOCK){
      blockedUntil=Math.max(blockedUntil,SystemClock.elapsedRealtime()+30*60000L);
    }
  }
  static org.json.JSONObject policy() throws org.json.JSONException {
    return new org.json.JSONObject()
      .put("publishedLiveRequestsPerSecond",10)
      .put("publishedLiveRequestsPerMinute",300)
      .put("internalLiveRequestsPerSecond",LIVE_SECOND)
      .put("internalLiveRequestsPerMinute",LIVE_MINUTE)
      .put("internalAuthRequestsPerDay",AUTH_DAY)
      .put("historicalInternalRequestsPerMinute",HISTORY_MINUTE)
      .put("externalAppsIncluded",false);
  }
  static org.json.JSONObject counters() throws org.json.JSONException {
    synchronized(LOCK){
      long now=SystemClock.elapsedRealtime();
      while(!liveSecond.isEmpty()&&now-liveSecond.peekFirst()>=1000)liveSecond.removeFirst();
      while(!liveMinute.isEmpty()&&now-liveMinute.peekFirst()>=60000)liveMinute.removeFirst();
      while(!historicalMinute.isEmpty()&&now-historicalMinute.peekFirst()>=60000)
        historicalMinute.removeFirst();
      return new org.json.JSONObject()
        .put("liveLastSecond",liveSecond.size())
        .put("liveLastMinute",liveMinute.size())
        .put("historicalLastMinute",historicalMinute.size())
        .put("cooldownRemainingMs",Math.max(0,blockedUntil-now));
    }
  }
  static String status(){
    synchronized(LOCK){
      long now=SystemClock.elapsedRealtime();
      while(!liveMinute.isEmpty()&&now-liveMinute.peekFirst()>=60000)liveMinute.removeFirst();
      return liveMinute.size()+"/"+LIVE_MINUTE+" internal live-data requests in rolling minute";
    }
  }
}

package com.mathformula.alpha;

import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Best-effort DEVICE-LOCAL safety budgets below official Groww ceilings.
 * All instances in the MathFormula Android process share these counters.
 * Groww account quotas are shared with other apps, and that external usage is unknowable.
 *
 * Official limits (Oct 2026): Live Data 10/s & 300/min, Auth 5/s & 30/min,
 * token exchanges 150/day. Historical API quotas aren't listed alongside Live Data:
 * therefore a separate conservative throttle protects historical requests.
 */
final class GrowwQuota {
  private static final Deque<Long> LIVE=new ArrayDeque<>();
  private static final Deque<Long> HISTORICAL=new ArrayDeque<>();
  private static final Deque<Long> AUTH=new ArrayDeque<>();
  private static long liveBlockUntil=0,historyBlockUntil=0,authBlockUntil=0;
  private static long acceptedLive=0,acceptedHistorical=0,acceptedAuth=0;
  private GrowwQuota(){}
  static synchronized void acquire(String category) throws InterruptedException {
    Deque<Long> events;
    long interval,window=60_000L, block;
    int ceiling;
    switch(category) {
      case "live":events=LIVE;interval=400;ceiling=150;break;
      case "historical":events=HISTORICAL;interval=1500;ceiling=30;break;
      case "auth":events=AUTH;interval=120_000;ceiling=8;window=24L*60*60*1000;break;
      default:throw new IllegalArgumentException("Unknown market-data rate category");
    }
    while(true){
      long now=SystemClock.elapsedRealtime();
      block="live".equals(category)?liveBlockUntil:
        "historical".equals(category)?historyBlockUntil:authBlockUntil;
      while(!events.isEmpty() && now-events.peekFirst()>=window)events.removeFirst();
      long earliest=events.isEmpty()?0:events.peekLast()+interval;
      long windowReset=events.size()>=ceiling?events.peekFirst()+window:0;
      long target=Math.max(Math.max(block,earliest),windowReset);
      if(now>=target){
        events.addLast(now);
        if("live".equals(category))acceptedLive++;
        else if("historical".equals(category))acceptedHistorical++;
        else acceptedAuth++;
        return;
      }
      long delay=Math.min(30_000L,Math.max(1L,target-now));
      GrowwQuota.class.wait(delay);
    }
  }
  static synchronized void rateLimited(String category){
    long next=SystemClock.elapsedRealtime()+120_000L;
    if("live".equals(category))liveBlockUntil=Math.max(liveBlockUntil,next);
    else if("historical".equals(category))historyBlockUntil=Math.max(historyBlockUntil,next);
    else authBlockUntil=Math.max(authBlockUntil,next);
    GrowwQuota.class.notifyAll();
  }
  static synchronized String counters(){
    return "liveCalls="+acceptedLive+" historicalCalls="+acceptedHistorical+
      " authCalls="+acceptedAuth+" (since app process started)";
  }
  static String policy(){
    return "Device guardrails: <=150 live/min and >=400ms/live call; "+
      "<=30 historical/min and >=1500ms/historical call; "+
      "<=8 token exchanges/24h. Groww account-wide quota includes other apps.";
  }
}

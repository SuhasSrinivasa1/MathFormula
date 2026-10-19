package com.mathformula.alpha;

import org.json.JSONObject;
import java.util.Locale;

/**
 * Display-only states. They do not modify the frozen Alpha30Engine parameters or issue BUY orders.
 * A market breakout is not a confirmed Alpha-30 BUY.
 */
final class WatchState {
  static final String WATCHING="WATCHING",NEAR="NEAR BREAKOUT",CROSSED="BREAKOUT CROSSED",
      CONFIRMING="CONFIRMING",GATES_PENDING="FULL MODEL PENDING",STALE="DATA STALE",
      MARKET_CLOSED="MARKET CLOSED",UNAVAILABLE="DATA UNAVAILABLE";
  static final class Progress {
    final String stage, next;
    final double gapPct;
    final boolean fresh, crossed;
    Progress(String s,String n,double g,boolean f,boolean c){
      stage=s;next=n;gapPct=g;fresh=f;crossed=c;
    }
  }
  static Progress decide(double price,double breakout,long tradeMs,long nowMs,
          boolean marketOpen,boolean two5m,boolean candle15m,boolean aboveVwap){
    if(!marketOpen)return new Progress(MARKET_CLOSED,"Next NSE market session; display references only",Double.NaN,false,false);
    if(tradeMs<=0||tradeMs>nowMs+3000||nowMs-tradeMs>10000)
      return new Progress(STALE,"Wait for a fresh Groww exchange timestamp (≤10 seconds)",Double.NaN,false,false);
    if(!Double.isFinite(price)||price<=0)
      return new Progress(UNAVAILABLE,"Groww last price unavailable",Double.NaN,true,false);
    if(!Double.isFinite(breakout)||breakout<=0)
      return new Progress(UNAVAILABLE,"Refresh the latest prior 20-session breakout reference",Double.NaN,true,false);
    double gap=(breakout/price-1)*100;
    if(price<breakout){
      if(gap<=1.5)return new Progress(NEAR,"Needs price above ₹"+String.format(Locale.US,"%.2f",breakout),gap,true,false);
      return new Progress(WATCHING,"Waiting for price to approach breakout",gap,true,false);
    }
    if(!two5m)return new Progress(CROSSED,"Needs two completed 5-minute closes above breakout + VWAP",gap,true,true);
    if(!candle15m||!aboveVwap)return new Progress(CONFIRMING,"Needs completed 15-minute breakout and verified VWAP support",gap,true,true);
    return new Progress(GATES_PENDING,"Price confirms; still need the remaining frozen Alpha-30 factors, RVOL and net risk/reward",gap,true,true);
  }
  static JSONObject json(GrowwReadOnlyClient.Market m,Progress p,long polledAt) throws Exception{
    JSONObject j=new JSONObject();
    j.put("symbol",m.symbol); j.put("price",safe(m.price));j.put("changePct",safe(m.change));
    j.put("reference",safe(m.breakoutLevel));j.put("referenceAsOf",m.referenceAsOf);
    j.put("referenceSource",m.referenceSource);
    j.put("tradeMs",m.lastTradeMs);j.put("polledAt",polledAt);
    j.put("quoteAgeMs",m.lastTradeMs>0&&polledAt>=m.lastTradeMs?
      polledAt-m.lastTradeMs:JSONObject.NULL);
    j.put("exchangeTimestampFresh",p.fresh);
    j.put("marketCapFieldState",m.marketCapFieldState);
    j.put("staleQuotePolicy","10 seconds required for signals; inactive symbols re-polled every 5 minutes");
    j.put("high",safe(m.high));j.put("low",safe(m.low));
    j.put("volume",safe(m.dayVolume));j.put("vwap",safe(m.vwap));
    j.put("spreadBps",safe(m.spreadBps));j.put("fiveCount",m.fiveCount);
    j.put("fifteenCount",m.fifteenCount);j.put("two5m",m.two5m);
    j.put("fifteenConfirmed",m.fifteenCount>0 && Double.isFinite(m.breakoutLevel) &&
      Double.isFinite(m.lastFifteenClose) && m.lastFifteenClose>m.breakoutLevel && m.aboveVwap15);
    j.put("aboveVwap5",m.aboveVwap5);j.put("aboveVwap15",m.aboveVwap15);
    j.put("lastFiveClose",safe(m.lastFiveClose));j.put("lastFifteenClose",safe(m.lastFifteenClose));
    j.put("stage",p.stage);j.put("next",p.next);j.put("gapPct",safe(p.gapPct));
    j.put("fresh",p.fresh);j.put("modelVersion",Alpha30Engine.VERSION);
    j.put("decision","NO SIGNAL"); // Full model is not supplied by the Groww-only feed.
    return j;
  }
  static Object safe(double n){return Double.isFinite(n)?n:JSONObject.NULL;}
}

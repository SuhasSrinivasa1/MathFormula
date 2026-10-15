package com.mathformula.alpha;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/**
 * Bounded, auditable device research alerts. Incomplete Alpha-30 BUY gates
 * deliberately forbid BUY or entry instructions. No order placement.
 */
final class ResearchAlerts {
  static final String CHANNEL="mf_research_opportunities_v18";
  private static final String PREFS="mathformula_research_alerts_v18";
  private ResearchAlerts(){}
  static void channel(Context context){
    if(Build.VERSION.SDK_INT>=26){
      NotificationChannel ch=new NotificationChannel(CHANNEL,"MathFormula research opportunities",
          NotificationManager.IMPORTANCE_HIGH);
      ch.setDescription("NSE breakout observations and technical confirmation; not trade orders");
      NotificationManager manager=context.getSystemService(NotificationManager.class);
      if(manager!=null)manager.createNotificationChannel(ch);
    }
  }
  static boolean permission(Context c){
    if(Build.VERSION.SDK_INT>=33 &&
      c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false;
    NotificationManager mgr=c.getSystemService(NotificationManager.class);
    return mgr!=null && mgr.areNotificationsEnabled();
  }
  static long lastCheck(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getLong("checked",0);}
  static String lastState(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("state","No alert evaluations recorded");}
  static void record(Context c,int n,String result){
    c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
       .putLong("checked",System.currentTimeMillis()).putString("state",result)
       .putInt("count",n).apply();
  }
  private static void post(Context c,String symbol,String key,String title,String message){
    if(!permission(c))return;
    SharedPreferences preferences=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    String date=java.time.LocalDate.now(MarketSchedule.IST).toString();
    String distinct="sent_"+date+"_"+symbol+"_"+key;
    if(preferences.getBoolean(distinct,false))return;
    Intent opening=new Intent(c,HomeActivity.class);
    opening.setAction(Intent.ACTION_VIEW);
    opening.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent tap=PendingIntent.getActivity(c,Math.abs(distinct.hashCode()%30000)+70000,
        opening,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Notification note=new Notification.Builder(c,CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_more)
        .setContentTitle(title).setContentText(message)
        .setStyle(new Notification.BigTextStyle().bigText(message))
        .setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_RECOMMENDATION).build();
    NotificationManager mgr=c.getSystemService(NotificationManager.class);
    if(mgr!=null){
      mgr.notify(Math.abs(distinct.hashCode()%500000)+5000,note);
      preferences.edit().putBoolean(distinct,true).apply();
      new WatchStore(c).audit("RESEARCH_ALERT",symbol,"NOT SEEN",key,message,0);
    }
  }
  /** At most two research-only new-shortlist alerts each IST session day. */
  static void newShortlist(Context c,JSONObject scan,JSONArray older){
    if(!permission(c)||!MarketSchedule.weekdayTradingTime(System.currentTimeMillis()))return;
    JSONArray top=scan.optJSONArray("top");
    if(top==null||top.length()==0)return;
    java.util.Set<String> previous=new java.util.HashSet<>();
    if(older!=null)for(int i=0;i<older.length();i++){
      JSONObject stock=older.optJSONObject(i);
      if(stock!=null)previous.add(stock.optString("symbol",""));
    }
    SharedPreferences pref=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    String today=java.time.LocalDate.now(MarketSchedule.IST).toString();
    int used=pref.getInt("new_count_"+today,0);
    long last=pref.getLong("new_last_"+today,0);
    if(used>=2 || System.currentTimeMillis()-last<60*60_000L)return;
    for(int i=0;i<Math.min(3,top.length());i++){
      JSONObject p=top.optJSONObject(i);if(p==null)continue;
      String symbol=p.optString("symbol","");
      double gap=p.optDouble("distancePct",Double.NaN);
      double strength=p.optDouble("preliminaryRank",Double.NaN);
      long exchange=p.optLong("tradeMs",0),now=System.currentTimeMillis();
      if(previous.contains(symbol)||!symbol.matches("[A-Z0-9&-]{1,26}")||
        !Double.isFinite(gap)||gap< -1.5||gap>3||
        !Double.isFinite(strength)||strength<55||
        exchange<=0||exchange>now+3000||now-exchange>90_000L)continue;
      post(c,symbol,"NEW_RESEARCH_CANDIDATE",
        "MathFormula · new NSE research watch",
        String.format(Locale.US,"%s near breakout (%.2f%% vs prior resistance). "+
          "Review the setup; NOT BUY.",symbol,gap));
      pref.edit().putInt("new_count_"+today,used+1).putLong("new_last_"+today,now).apply();
      break;
    }
  }
  /**
   * Current-session, full-universe radar observations. Daily history reference
   * must already have the exact current H20; alert is ALWAYS research only.
   */
  static int evaluateRadar(Context c,GrowwReadOnlyClient client,IntradayRadar radar){
    if(!MarketSchedule.weekdayTradingTime(System.currentTimeMillis())||!radar.fresh())return 0;
    JSONArray top=radar.currentCandidates();if(top==null)return 0;
    int checked=0,errors=0;
    for(int i=0;i<Math.min(3,top.length());i++){
      JSONObject candidate=top.optJSONObject(i);
      if(candidate==null||!candidate.optBoolean("historyVerified"))continue;
      String symbol=candidate.optString("symbol","");
      double trigger=candidate.optDouble("breakoutLevel",Double.NaN);
      if(!symbol.matches("[A-Z0-9&-]{1,26}")||!(trigger>0))continue;
      try{
        GrowwReadOnlyClient.Market market=client.quote(symbol);
        checked++;
        long now=System.currentTimeMillis();
        if(market.lastTradeMs<=0||market.lastTradeMs>now+3000||
            now-market.lastTradeMs>10_000L||market.price<trigger)continue;
        post(c,symbol,"RADAR_BREAKOUT",
          "MathFormula · "+symbol+" research breakout",
          String.format(Locale.US,
            "Fresh NSE quote ₹%.2f crossed H20 ₹%.2f. Confirm 15m/5m, 30-factor coverage and ALL gates. WATCH only.",
            market.price,trigger));
      }catch(SecurityException e){errors++;break;}
       catch(Exception e){errors++;}
    }
    record(c,checked,"Current-session radar examined "+checked+" verified-H20 names; "+
      errors+" quote errors; no BUY eligibility certified");
    return checked;
  }

  /**
   * Alert only when an exchange-time quote is recent AND a prior 20-day
   * breakout reference was actually verified.
   * Passing completed technical candles is a RESEARCH state, never Alpha-30 BUY.
   */
  static int evaluate(Context c,GrowwReadOnlyClient client,JSONObject universe){
    int inspected=0,eligible=0,errors=0;
    if(!MarketSchedule.weekdayTradingTime(System.currentTimeMillis()))return 0;
    JSONArray top=universe.optJSONArray("top");
    if(top==null||top.length()==0){
      record(c,0,"No current shortlisted research candidates");
      return 0;
    }
    for(int i=0;i<Math.min(3,top.length());i++){
      if(Thread.currentThread().isInterrupted())break;
      JSONObject selection=top.optJSONObject(i);
      if(selection==null)continue;
      String symbol=selection.optString("symbol","");
      long scanAt=universe.optLong("scannedAtMs",0);
      // Overnight research is intended for the NEXT session. Freshness of each
      // actual live quote is validated separately; age of EOD analysis is not quote age.
      long age=System.currentTimeMillis()-scanAt;
      if(!symbol.matches("[A-Z0-9&-]{1,26}") ||
          scanAt<=0||age<0||age>4L*24*60*60*1000L)continue;
      if("NEXT_SESSION_RESEARCH".equals(universe.optString("scanMode"))){
        java.time.LocalDate day=java.time.LocalDate.now(MarketSchedule.IST).minusDays(1);
        while(day.getDayOfWeek()==java.time.DayOfWeek.SATURDAY||
              day.getDayOfWeek()==java.time.DayOfWeek.SUNDAY)day=day.minusDays(1);
        if(!day.toString().equals(universe.optString("sourceSessionDate")))continue;
      }
      double resistance=selection.optDouble("breakoutLevel",Double.NaN);
      if(!Double.isFinite(resistance)||resistance<=0)continue;
      try{
        GrowwReadOnlyClient.Market m=client.quote(symbol);
        inspected++;
        long received=System.currentTimeMillis();
        if(!Double.isFinite(m.price)||m.price<=0||m.lastTradeMs<=0||
           m.lastTradeMs>received+3000||received-m.lastTradeMs>30_000L)continue;
        if(m.price<resistance)continue;
        eligible++;
        post(c,symbol,"PRICE_BREAKOUT","MathFormula · "+symbol+" breakout watch",
            String.format(Locale.US,
            "Observed ₹%.2f above prior resistance ₹%.2f. Research alert only; NOT an Alpha-30 BUY.",
            m.price,resistance));
        m.breakoutLevel=resistance;
        try{
          client.candles(m);
          boolean confirmed=m.two5m&&m.fifteenCount>0&&
            Double.isFinite(m.lastFifteenClose)&&m.lastFifteenClose>resistance&&
            m.aboveVwap5&&m.aboveVwap15&&
            Double.isFinite(m.spreadBps)&&m.spreadBps<=30.0;
          if(confirmed){
            post(c,symbol,"TECHNICAL_CONFIRMED","MathFormula · "+symbol+" technical setup",
                String.format(Locale.US,
                  "Completed 5m/15m candles and VWAP support ₹%.2f; spread verified. Full Alpha-30 factors still pending. NOT BUY.",
                  m.price));
          }
        }catch(Exception e){errors++;}
      }catch(SecurityException e){errors++;break;}
       catch(Exception e){errors++;}
    }
    record(c,inspected,errors>0?
      "Checked "+inspected+" · "+errors+" quote/candle errors; may have missed alerts":
      "Checked "+inspected+" · price breakouts "+eligible+" · no BUY decisions");
    return inspected;
  }
}

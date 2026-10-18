package com.mathformula.alpha;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.content.pm.ServiceInfo;
import android.app.ForegroundServiceStartNotAllowedException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Explicit user-started, best-effort on-device session monitor.
 * On Android 14+, user-started specialUse applies, subject to Android and Play restrictions. Server-side monitoring is required
 * for guaranteed uninterrupted market coverage and overnight universe discovery.
 */
public final class ScannerService extends Service {
  public static final String START="com.mathformula.alpha.START_MONITOR";
  public static final String STOP="com.mathformula.alpha.STOP_MONITOR";
  private static final String CHANNEL="alpha30_scan_v12";
  private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
  private static final long MAX_SESSION_MS=6L*60*60*1000+20L*60*1000; // market session plus controlled margin
  private final Handler loop=new Handler(Looper.getMainLooper());
  private final ExecutorService work=Executors.newSingleThreadExecutor();
  private final ExecutorService radarWorker=Executors.newSingleThreadExecutor();
  private WatchStore store;
  private SecureGrowwStore vault;
  private GrowwReadOnlyClient client;
  private volatile boolean busy=false,radarBusy=false,started=false;
  private volatile int recentQuotes=0,recentUniverse=0;
  private String lastReminderDay="";
  private long sessionStart;
  private final Runnable pulse=new Runnable() {
    @Override public void run(){
      if(!started)return;
      if(System.currentTimeMillis()-sessionStart>=MAX_SESSION_MS) {
        halt("Live market monitor session elapsed; reopen app to re-arm next NSE session");
        return;
      }
      if(!inMarketHours()){
        halt("Outside supported NSE hours; monitoring stopped");
        return;
      }
      if(MarketSchedule.preClose(System.currentTimeMillis()) &&
         java.time.ZonedDateTime.now(IST).getHour()==14 &&
         java.time.ZonedDateTime.now(IST).getMinute()>=30){
        String today=java.time.LocalDate.now(IST).toString();
        if(!today.equals(lastReminderDay)){
          lastReminderDay=today;
          store.audit("MONITOR","SESSION","ACTIVE","RENEWAL_REMINDER",
            "Open MathFormula and tap Start background again for closing session coverage",0);
          reminder("Closing-session coverage check",
            "Open MathFormula and tap Start background to renew the Android monitoring session.");
        }
      }
      status("FOREGROUND_ACTIVE");
      if(!busy)fetchCycle();
      startRadarWhenDue();
      // Monitored-symbol quotes every minute; completed-candle checks every five minutes.
      long now=System.currentTimeMillis(), interval=60000L;
      long delay=interval-(now%interval)+3000L;
      loop.postDelayed(this,Math.max(3000L,delay));
    }
  };
  @Override public void onCreate(){
    super.onCreate();store=new WatchStore(this);vault=new SecureGrowwStore(this);
    client=new GrowwReadOnlyClient(vault);
    if(Build.VERSION.SDK_INT>=26){
      NotificationChannel alerts=new NotificationChannel("alpha30_breakouts_v12","Breakout watch alerts",NotificationManager.IMPORTANCE_HIGH);
      alerts.setDescription("Early price breakout alerts, NOT trade recommendations");
      getSystemService(NotificationManager.class).createNotificationChannel(alerts);
      NotificationChannel c=new NotificationChannel(CHANNEL,"MathFormula monitoring",
        NotificationManager.IMPORTANCE_LOW);
      c.setDescription("Read-only NSE 5-minute monitoring session");
      getSystemService(NotificationManager.class).createNotificationChannel(c);
    }
  }
  private boolean inMarketHours(){
    ZonedDateTime z=ZonedDateTime.now(IST);
    DayOfWeek d=z.getDayOfWeek();
    if(d==DayOfWeek.SATURDAY||d==DayOfWeek.SUNDAY)return false;
    LocalTime t=z.toLocalTime();
    // Exchange holiday data unavailable on phone: service fails closed only when provider responds inactive.
    return !t.isBefore(LocalTime.of(9,15))&&t.isBefore(LocalTime.of(15,30));
  }
  private Notification notification(String caption){
    Intent open=new Intent(this,HomeActivity.class);
    PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this,CHANNEL)
      .setContentTitle("MathFormula · Live NSE monitor")
      .setContentText(caption)
      .setSmallIcon(R.drawable.ic_mathformula_notification)
      .setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true)
      .setCategory(Notification.CATEGORY_SERVICE)
      .setShowWhen(true).build();
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId){
    String action=intent==null?"":intent.getAction();
    if(STOP.equals(action)){halt("Stopped by user");return START_NOT_STICKY;}
    if(started){
      // Explicit user interaction re-arms the local safety timer. Android OS limits still apply.
      sessionStart=System.currentTimeMillis();
      store.audit("MONITOR","SESSION","ACTIVE","USER_RENEWED",
        "User reopened MathFormula to prioritize closing session; Android budget still applies",0);
      return START_STICKY;
    }
    if(!vault.configured()) {
      store.setEnabled(false);store.recordScan(false,"Groww credentials not configured",0);
      stopSelf();return START_NOT_STICKY;
    }
    if(!inMarketHours()){
      store.setEnabled(false);store.recordScan(false,"Market closed or outside routine NSE hours",0);
      stopSelf();return START_NOT_STICKY;
    }
    try{
      if(Build.VERSION.SDK_INT>=34){
        // User-visible, manually stoppable live market-monitoring session.
        // This subtype mirrors the user's Univest/Multify apps and requires
        // its own Play Console justification if ever publicly distributed.
        startForeground(70,notification("Active · Groww 1m quotes / 5m checks · read-only"),
          ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
      }else if(Build.VERSION.SDK_INT>=29){
        startForeground(70,notification("Active · Groww 1m quotes / 5m checks · read-only"),
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
      }else startForeground(70,notification("Active · five-minute Groww research · read-only"));
    }catch(RuntimeException ex){
      store.recordScan(false,"Android refused foreground monitoring; open app and retry",0);
      store.setEnabled(false);stopSelf();return START_NOT_STICKY;
    }
    started=true;sessionStart=System.currentTimeMillis();store.setEnabled(true);
    status("FOREGROUND_ACTIVE");ResearchJob.schedule(this);
    store.audit("MONITOR","SESSION","STOPPED","ACTIVE","User-started read-only session",0);
    loop.post(pulse);return START_STICKY;
  }
  /** Follow the overnight watch into the NEXT trading day, not only the next 25 minutes.
   *  The fallback remains research-only: all Alpha-30 BUY gates are still independently required.
   */
  private List<String> watchlist(){
    List<String> symbols=new ArrayList<>();
    LocalDiscovery discovery=new LocalDiscovery(this);
    org.json.JSONObject record=discovery.latest();
    long stamp=record.optLong("scannedAtMs",0L);
    long age=System.currentTimeMillis()-stamp;
    boolean overnightUsable=stamp>0 && age>=0 && age<=4L*24*60*60*1000;
    // Start with the FRESH intraday breadth leaders; never carry yesterday's names by default.
    org.json.JSONArray current=new IntradayRadar(this).currentCandidates();
    if(current!=null)for(int i=0;i<current.length()&&symbols.size()<5;i++){
      org.json.JSONObject row=current.optJSONObject(i);
      String name=row==null?"":row.optString("symbol","");
      if(name.matches("[A-Z0-9&-]{1,26}")&&!symbols.contains(name))symbols.add(name);
    }
    // Limit individual depth/candle reads; the separate batch refresh covers the priority 50.
    org.json.JSONArray verified=overnightUsable?record.optJSONArray("top"):null;
    if(verified!=null)for(int i=0;i<verified.length()&&symbols.size()<5;i++){
      org.json.JSONObject stock=verified.optJSONObject(i);
      String name=stock==null?"":stock.optString("symbol","");
      if(name.matches("[A-Z0-9&-]{1,26}")&&!symbols.contains(name))symbols.add(name);
    }
    org.json.JSONArray priority=overnightUsable?discovery.priorityWatchlist():new org.json.JSONArray();
    for(int i=0;i<priority.length()&&symbols.size()<5;i++){
      String name=priority.optString(i,"");
      if(name.matches("[A-Z0-9&-]{1,26}")&&!symbols.contains(name))symbols.add(name);
    }
    return symbols;
  }
  /** Heartbeat proves the worker is running; notification alone proves only Android service presence. */
  private void status(String state){
    getSharedPreferences("mathformula_local_monitor_v32",MODE_PRIVATE).edit()
      .putLong("heartbeat_ms",System.currentTimeMillis())
      .putString("monitoring_state",state).apply();
  }
  /** Dynamically discover fresh movers even when the Market activity has been closed. */
  private void startRadarWhenDue(){
    final IntradayRadar radar=new IntradayRadar(this);
    if(radarBusy||!radar.due())return;
    radarBusy=true;
    radarWorker.execute(()->{
      try {
        radar.scan(client,new LocalDiscovery(this),update->{});
        recentUniverse=radar.latest().optInt("scannedUniverse",0);
        ResearchAlerts.evaluateRadar(this,client,radar);
      }catch(SecurityException access){
        store.audit("RADAR","GROWW","ACTIVE","AUTH_REQUIRED",
          "Phone-only market scan lost Groww account authorization",0);
      }catch(Exception failure){
        store.audit("RADAR","UNIVERSE","ACTIVE","SCAN_FAILURE",
          failure.getClass().getSimpleName(),0);
      }finally{radarBusy=false;}
    });
  }
  private void fetchCycle(){
    busy=true;work.execute(()->{
      try{
      List<String> symbols=watchlist();
      boolean fullFive=java.time.ZonedDateTime.now(IST).getMinute()%5==0;int success=0;String lastFailure="";
      long now=System.currentTimeMillis();
      for(String symbol:symbols){
        try{
          GrowwReadOnlyClient.Market m=client.quote(symbol);
          org.json.JSONObject prior=store.get(symbol);
          if(fullFive || !prior.has("reference")){
            try{client.dailyReference(m);}catch(Exception ex){m.note="Daily reference unavailable";}
          }else{
            m.breakoutLevel=prior.optDouble("reference",Double.NaN);
            m.referenceAsOf=prior.optString("referenceAsOf","");
            m.referenceSource=prior.optString("referenceSource","");
          }
          if(fullFive) {
            try{client.candles(m);}catch(Exception ex){m.note+=" Candle data unavailable";}
          }else{
            // Candles belong to the LAST completed 5-minute evaluation, not the 1-min quote.
            m.two5m=prior.optBoolean("two5m",false);
            m.fifteenCount=prior.optInt("fifteenCount",0);
            m.fiveCount=prior.optInt("fiveCount",0);
            m.aboveVwap5=prior.optBoolean("aboveVwap5",false);
            m.aboveVwap15=prior.optBoolean("aboveVwap15",false);
            m.lastFiveClose=prior.optDouble("lastFiveClose",Double.NaN);
            m.lastFifteenClose=prior.optDouble("lastFifteenClose",Double.NaN);
          }
          // Do not accept a completed 15m candle unless it CLOSED above resistance and average trade price.
          boolean fifteenConfirmed=m.fifteenCount>0 && Double.isFinite(m.breakoutLevel) &&
            Double.isFinite(m.lastFifteenClose) && m.lastFifteenClose>m.breakoutLevel && m.aboveVwap15;
          WatchState.Progress p=WatchState.decide(m.price,m.breakoutLevel,m.lastTradeMs,
            System.currentTimeMillis(),true,m.two5m,fifteenConfirmed,m.aboveVwap5&&m.aboveVwap15);
          String previous=store.get(symbol).optString("stage","");
          store.put(WatchState.json(m,p,System.currentTimeMillis()));
          // The shared ResearchAlerts notifier handles deduplication and accurate labels.
          success++;
        }catch(Exception ex){
          lastFailure=symbol+": "+(ex.getMessage()==null?"Groww unavailable":ex.getMessage());
          store.audit("ERROR",symbol,"","FETCH FAILED",lastFailure,0);
        }
      }
      boolean okay=success==symbols.size()&&success>0;
      if(fullFive&&success>0)ResearchAlerts.evaluate(this,client,new LocalDiscovery(this).latest());
      if(MarketSchedule.checkpoint(now)){
        StringBuilder shortlist=new StringBuilder();
        for(String symbol:symbols){
          org.json.JSONObject j=store.get(symbol);
          String stage=j.optString("stage","NO DATA");
          if(shortlist.length()>0)shortlist.append(" | ");
          shortlist.append(symbol).append(": ").append(stage);
          String event=store.earningsDate(symbol);
          if(MarketSchedule.dueSoon(event,System.currentTimeMillis()))shortlist.append(" · MANUAL earnings tag ").append(event);
        }
        String label=MarketSchedule.checkpointLabel(now);
        store.recordPreClose(label,shortlist.toString());
        reminder("Pre-close watch · "+label,"Updated watched symbols. Check MathFormula; not a BUY signal.");
      }
      store.recordScan(okay,okay?"":lastFailure,success);
      recentQuotes=success;
      status(okay?"FOREGROUND_ACTIVE":"FOREGROUND_DEGRADED");
      Intent changed=new Intent(WatchStore.ACTION);changed.setPackage(getPackageName());
      sendBroadcast(changed);
      if(started){
        NotificationManager mgr=getSystemService(NotificationManager.class);
        if(mgr!=null)mgr.notify(70,notification("Quotes "+success+"/"+symbols.size()+
          " · NSE radar "+(radarBusy?"scanning":recentUniverse>0?recentUniverse+" stocks":"pending")+
          " · "+LocalTime.now(IST).toString().substring(0,5)+" IST"));
      }
      }catch(Exception fatal){
        store.audit("ERROR","SESSION","RUNNING","SCAN_WORKER_EXCEPTION",
          fatal.getClass().getSimpleName(),0);
        store.recordScan(false,"Monitor cycle failed: "+fatal.getClass().getSimpleName(),0);
        status("SCAN_ERROR");
      }finally{busy=false;}
    });
  }
  private void reminder(String title,String body){
    if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=
      android.content.pm.PackageManager.PERMISSION_GRANTED)return;
    PendingIntent open=PendingIntent.getActivity(this,781,new Intent(this,MainActivity.class),
      PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Notification msg=new Notification.Builder(this,"alpha30_breakouts_v12")
      .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title)
      .setContentText(body).setContentIntent(open).setAutoCancel(true).build();
    NotificationManager manager=getSystemService(NotificationManager.class);
    if(manager!=null)manager.notify(7102,msg);
  }
  private void notifyWatch(String symbol,String stage,GrowwReadOnlyClient.Market m){
    if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
         !=android.content.pm.PackageManager.PERMISSION_GRANTED)return;
    Intent intent=new Intent(this,MainActivity.class);
    PendingIntent open=PendingIntent.getActivity(this,Math.abs(symbol.hashCode()%10000),intent,
      PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Notification alert=new Notification.Builder(this,"alpha30_breakouts_v12")
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(symbol+" · "+stage)
      .setContentText("Price ₹"+String.format(Locale.US,"%.2f",m.price)+" vs breakout ₹"+
        String.format(Locale.US,"%.2f",m.breakoutLevel)+" · NOT an Alpha-30 BUY")
      .setContentIntent(open).setAutoCancel(true).build();
    NotificationManager manager=getSystemService(NotificationManager.class);
    if(manager!=null)manager.notify(100000+Math.abs(symbol.hashCode()%10000),alert);
  }
  private void halt(String reason){
    if(started)reminder("Monitoring stopped · coverage gap",reason);
    started=false;loop.removeCallbacks(pulse);store.setEnabled(false);
    status("STOPPED: "+reason);
    store.audit("MONITOR","SESSION","ACTIVE","STOPPED",reason,0);
    store.recordScan(false,reason,0);
    Intent changed=new Intent(WatchStore.ACTION);changed.setPackage(getPackageName());sendBroadcast(changed);
    stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
  }
  @Override public void onTimeout(int startId,int fgsType){halt("Android foreground dataSync timeout");}
  @Override public void onDestroy(){
    boolean unexpected=started;
    started=false;loop.removeCallbacks(pulse);work.shutdownNow();
    radarWorker.shutdownNow();store.setEnabled(false);
    if(unexpected)status("INTERRUPTED_UNEXPECTEDLY");
    super.onDestroy();
  }
  @Override public IBinder onBind(Intent i){return null;}
}

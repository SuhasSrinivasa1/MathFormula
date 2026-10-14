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
 * Android 15 caps dataSync FGS background use at 6h per 24h. Server-side monitoring is required
 * for guaranteed uninterrupted market coverage and overnight universe discovery.
 */
public final class ScannerService extends Service {
  public static final String START="com.mathformula.alpha.START_MONITOR";
  public static final String STOP="com.mathformula.alpha.STOP_MONITOR";
  private static final String CHANNEL="alpha30_scan_v12";
  private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
  private static final long MAX_SESSION_MS=5L*60*60*1000+45L*60*1000; // 5h45m safety budget
  private final Handler loop=new Handler(Looper.getMainLooper());
  private final ExecutorService work=Executors.newSingleThreadExecutor();
  private WatchStore store;
  private SecureGrowwStore vault;
  private GrowwReadOnlyClient client;
  private boolean busy=false,started=false;
  private long sessionStart;
  private final Runnable pulse=new Runnable() {
    @Override public void run(){
      if(!started)return;
      if(System.currentTimeMillis()-sessionStart>=MAX_SESSION_MS) {
        halt("Android monitoring budget reached; server needed for uninterrupted coverage");
        return;
      }
      if(!inMarketHours()){
        halt("Outside supported NSE hours; monitoring stopped");
        return;
      }
      if(!busy)fetchCycle();
      // Align close to completed 5-min clock boundary. Additional 3 seconds allow a candle to settle.
      long now=System.currentTimeMillis(), interval=300000L;
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
    Intent open=new Intent(this,MainActivity.class);
    PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this,CHANNEL)
      .setContentTitle("MathFormula · monitoring active")
      .setContentText(caption)
      .setSmallIcon(android.R.drawable.ic_menu_view)
      .setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true).build();
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId){
    String action=intent==null?"":intent.getAction();
    if(STOP.equals(action)){halt("Stopped by user");return START_NOT_STICKY;}
    if(started)return START_NOT_STICKY;
    if(!vault.configured()) {
      store.setEnabled(false);store.recordScan(false,"Groww credentials not configured",0);
      stopSelf();return START_NOT_STICKY;
    }
    if(!inMarketHours()){
      store.setEnabled(false);store.recordScan(false,"Market closed or outside routine NSE hours",0);
      stopSelf();return START_NOT_STICKY;
    }
    try{
      if(Build.VERSION.SDK_INT>=29){
        startForeground(70,notification("5-minute research checks · read-only"),
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
      }else startForeground(70,notification("5-minute research checks · read-only"));
    }catch(RuntimeException ex){
      store.recordScan(false,"Android refused foreground monitoring; open app and retry",0);
      store.setEnabled(false);stopSelf();return START_NOT_STICKY;
    }
    started=true;sessionStart=System.currentTimeMillis();store.setEnabled(true);
    store.audit("MONITOR","SESSION","STOPPED","ACTIVE","User-started read-only session",0);
    loop.post(pulse);return START_NOT_STICKY;
  }
  private List<String> watchlist(){
    String configured=getSharedPreferences("MainActivity",MODE_PRIVATE).getString("watchlist","TARIL,GAEL,ADVANCE");
    List<String> out=new ArrayList<>();
    for(String item:configured.split(",")){
      String v=item.trim().toUpperCase(Locale.US);
      if(v.matches("[A-Z0-9&-]{1,26}")&&!out.contains(v))out.add(v);
      if(out.size()==20)break;
    }
    return out;
  }
  private void fetchCycle(){
    busy=true;work.execute(()->{
      List<String> symbols=watchlist();int success=0;String lastFailure="";
      long now=System.currentTimeMillis();
      for(String symbol:symbols){
        try{
          GrowwReadOnlyClient.Market m=client.quote(symbol);
          try{client.dailyReference(m);}catch(Exception ex){m.note="Daily reference unavailable";}
          try{client.candles(m);}catch(Exception ex){m.note+=" Candle data unavailable";}
          WatchState.Progress p=WatchState.decide(m.price,m.breakoutLevel,m.lastTradeMs,
            now,true,m.two5m,m.fifteenCount>0,m.aboveVwap5&&m.aboveVwap15);
          String previous=store.get(symbol).optString("stage","");
          store.put(WatchState.json(m,p,System.currentTimeMillis()));
          if(p.fresh&&!p.stage.equals(previous)&&
              (WatchState.CROSSED.equals(p.stage)||WatchState.CONFIRMING.equals(p.stage)||WatchState.GATES_PENDING.equals(p.stage))) {
            notifyWatch(symbol,p.stage,m);
          }
          success++;
        }catch(Exception ex){
          lastFailure=symbol+": "+(ex.getMessage()==null?"Groww unavailable":ex.getMessage());
          store.audit("ERROR",symbol,"","FETCH FAILED",lastFailure,0);
        }
      }
      boolean okay=success==symbols.size()&&success>0;
      store.recordScan(okay,okay?"":lastFailure,success);
      Intent changed=new Intent(WatchStore.ACTION);changed.setPackage(getPackageName());
      sendBroadcast(changed);
      if(started){
        NotificationManager mgr=getSystemService(NotificationManager.class);
        if(mgr!=null)mgr.notify(70,notification("Checked "+success+"/"+symbols.size()+" at "+LocalTime.now(IST).toString().substring(0,5)+" IST"));
      }
      busy=false;
    });
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
    started=false;loop.removeCallbacks(pulse);store.setEnabled(false);
    store.audit("MONITOR","SESSION","ACTIVE","STOPPED",reason,0);
    store.recordScan(false,reason,0);
    Intent changed=new Intent(WatchStore.ACTION);changed.setPackage(getPackageName());sendBroadcast(changed);
    stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
  }
  @Override public void onTimeout(int startId,int fgsType){halt("Android foreground dataSync timeout");}
  @Override public void onDestroy(){
    started=false;loop.removeCallbacks(pulse);work.shutdownNow();store.setEnabled(false);
    super.onDestroy();
  }
  @Override public IBinder onBind(Intent i){return null;}
}

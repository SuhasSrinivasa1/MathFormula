package com.mathformula.alpha;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Best-effort periodic intraday/after-hours research when Android permits.
 * JobScheduler can defer or cancel runs; NOT a real-time NSE scanner or FCM.
 */
public final class ResearchJob extends JobService {
  private static final int JOB_ID=20261018;
  private ExecutorService worker=Executors.newSingleThreadExecutor();
  private volatile boolean cancelled=false;
  static void schedule(Context c){
    JobScheduler scheduler=c.getSystemService(JobScheduler.class);
    if(scheduler==null)return;
    // Android's allowed periodic job minimum is 15 minutes; scheduler may defer execution.
    JobInfo info=new JobInfo.Builder(JOB_ID,new ComponentName(c,ResearchJob.class))
      .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
      .setPeriodic(15*60*1000L)
      .setPersisted(true)
      .build();
    scheduler.schedule(info);
    ResearchAlerts.channel(c);
  }
  @Override public boolean onStartJob(JobParameters params){
    cancelled=false;
    if(worker.isShutdown())worker=Executors.newSingleThreadExecutor();
    if(!new SecureGrowwStore(this).configured())return false;
    boolean open=MarketSchedule.weekdayTradingTime(System.currentTimeMillis());
    java.time.LocalTime clock=java.time.Instant.now().atZone(MarketSchedule.IST).toLocalTime();
    if(!open && !clock.isBefore(java.time.LocalTime.of(15,30)) &&
       clock.isBefore(java.time.LocalTime.of(16,30)))return false;
    worker.execute(()->{
      try{
        if(!cancelled){
          GrowwReadOnlyClient client=new GrowwReadOnlyClient(new SecureGrowwStore(this));
          LocalDiscovery discovery=new LocalDiscovery(this);
          // Android may delay this ~15m periodic job. Screen only when stale;
          // the shared quota limiter governs both foreground and background.
          long previous=discovery.lastScan();
          boolean intraday=MarketSchedule.weekdayTradingTime(System.currentTimeMillis());
          long age=System.currentTimeMillis()-previous;
          long cadence=intraday?15*60_000L:4*60*60_000L;
          if(!discovery.scanning() && (previous==0||age>cadence) &&
             System.currentTimeMillis()-discovery.lastAttempt()>20*60_000L){
            try{
              if(intraday)discovery.scan(client,progress->{});
              else discovery.scanForNextSession(client,progress->{});
            }catch(Exception ex){
              ResearchAlerts.record(this,0,"Background research unavailable; "+ex.getClass().getSimpleName());
            }
          }
          if(!cancelled && intraday)ResearchAlerts.evaluate(this,client,discovery.latest());
        }
      }finally{
        if(!cancelled)jobFinished(params,false);
      }
    });
    return true;
  }
  @Override public boolean onStopJob(JobParameters params){
    cancelled=true;
    worker.shutdownNow();
    // Reschedule only when OS permits; not a precise 15-minute alarm.
    return true;
  }
  @Override public void onDestroy(){worker.shutdownNow();super.onDestroy();}
}

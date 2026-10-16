package com.mathformula.alpha;

import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import android.content.res.ColorStateList;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.view.View;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import org.json.JSONArray;

/**
 * Only two sections: LIVE (current market observations) and CLOSED (completed
 * market-session snapshots). No buttons to refresh, start monitoring, or trade.
 */
public final class HomeActivity extends AppCompatActivity {
  private static final int INK=Color.rgb(20,38,56),BLUE=Color.rgb(24,91,154),
      MUTED=Color.rgb(103,113,127),GREEN=Color.rgb(19,123,85),
      AMBER=Color.rgb(158,98,19),RED=Color.rgb(180,59,58);
  private static final int EXPORT=867,IMPORT_EVIDENCE=868;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private LocalDiscovery discovery;
  private IntradayRadar intradayRadar;
  private RecommendationLedger ledger;
  private Alpha30EvidenceStore evidenceStore;
  private String evidenceImportNote="";
  private JSONArray remoteLive=new JSONArray(),remoteClosed=new JSONArray();
  private boolean remoteBusy=false,remoteConnected=false;
  private long remoteLastAttempt=0,remoteLastSuccess=0;
  private String remoteError="";
  private SecureGrowwStore secrets;
  private GrowwReadOnlyClient groww;
  private LinearLayout root;
  private ScrollView mainScroll;
  private String section="LIVE",scanProgress="";
  private boolean busy=false,quoteBusy=false,priorityBusy=false,radarBusy=false;
  private long lastPriorityAttempt=0;
  private long lastQuoteAttempt=0,lastAlertAttempt=0;
  private boolean startedWhileOpen=false;
  private String lastVisibleDate="";
  private final Runnable autoUpdate=new Runnable(){
    @Override public void run(){
      boolean open=MarketSchedule.weekdayTradingTime(System.currentTimeMillis());
      // Keep the user's selected section. Closing bell does not close a valid research watch.
      startedWhileOpen=open;
      if(serverConfigured())fetchRemote();
      else if(open){
        // Intraday universe quotes must be independently refreshed; EOD history stays frozen.
        refreshIntradayRadarIfDue();
        // Never reopen all-NSE historical verification during the market session.
        refreshPriorityWatchlist();
        refreshPrices();
        evaluateAlerts();
      }else{
        ensureNextSessionFresh();
      }
      render();
      handler.postDelayed(this,30000L);
    }
  };
  private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
  private TextView text(String v,int sp,int color,boolean bold){
    TextView t=new TextView(this);t.setText(v);t.setTextSize(sp);t.setTextColor(color);
    if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
    t.setLineSpacing(dp(2),1.05f);
    return t;
  }
  private GradientDrawable rounded(int fill){
    GradientDrawable b=new GradientDrawable();b.setColor(fill);b.setCornerRadius(dp(18));return b;
  }
  private void space(int dp){root.addView(new View(this),new LinearLayout.LayoutParams(1,dp(dp)));}
  private LinearLayout card(){
    MaterialCardView shell=new MaterialCardView(this);
    shell.setRadius(dp(20));shell.setCardElevation(dp(2));
    shell.setCardBackgroundColor(Color.WHITE);
    shell.setStrokeColor(Color.rgb(227,233,240));shell.setStrokeWidth(dp(1));
    LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);
    c.setPadding(dp(18),dp(18),dp(18),dp(18));
    shell.addView(c,new MaterialCardView.LayoutParams(-1,-2));
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(14);
    root.addView(shell,p);return c;
  }
  private String time(long ms){
    if(ms<=0)return "Not available";
    return DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm:ss 'IST'",Locale.US)
        .withZone(MarketSchedule.IST).format(Instant.ofEpochMilli(ms));
  }
  private String hour(long ms){
    if(ms<=0)return "Unknown";
    return DateTimeFormatter.ofPattern("HH:mm:ss 'IST'",Locale.US)
        .withZone(MarketSchedule.IST).format(Instant.ofEpochMilli(ms));
  }
  private String money(double v){
    return Double.isFinite(v)?String.format(Locale.US,"₹%,.2f",v):"Unavailable";
  }
  private String percent(double v){
    return Double.isFinite(v)?String.format(Locale.US,"%+.2f%%",v):"Unavailable";
  }
  private boolean open(){return MarketSchedule.weekdayTradingTime(System.currentTimeMillis());}
  @Override public void onCreate(Bundle saved){
    super.onCreate(saved);
    secrets=new SecureGrowwStore(this);groww=new GrowwReadOnlyClient(secrets);
    discovery=new LocalDiscovery(this);
    intradayRadar=new IntradayRadar(this);
    ledger=new RecommendationLedger(this);
    evidenceStore=new Alpha30EvidenceStore(this);
    ResearchAlerts.channel(this);
    ResearchJob.schedule(this);
    if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
      requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},904);
    }
    startedWhileOpen=open();
    lastVisibleDate=todayIst();
    section="LIVE"; // Recommendation lifecycle is independent of market open/close.
    getWindow().setStatusBarColor(Color.rgb(247,249,252));
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    ScrollView scroll=new ScrollView(this);mainScroll=scroll;
    scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(247,249,252));
    root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(20),dp(23),dp(20),dp(32));
    scroll.addView(root);setContentView(scroll);
    render();
  }
  private String todayIst(){
    return java.time.LocalDate.now(MarketSchedule.IST).toString();
  }
  @Override protected void onResume(){
    super.onResume();
    if(!todayIst().equals(lastVisibleDate)){
      // Do not silently switch tabs when a new trading day begins.
      lastVisibleDate=todayIst();
      startedWhileOpen=open();
    }
    handler.removeCallbacks(autoUpdate);
    handler.postDelayed(autoUpdate,200);
    autoStartPhoneMonitoringIfEligible();
  }
  /** Phone monitoring is best effort; only a deployed server can scan continuously. */
  private void autoStartPhoneMonitoringIfEligible(){
    if(serverConfigured()||!open()||!secrets.configured())return;
    if(getSharedPreferences("mathformula_auto_monitor_v29",MODE_PRIVATE)
       .getBoolean("opted_out",false))return;
    long beat=getSharedPreferences("mathformula_local_monitor_v32",MODE_PRIVATE)
      .getLong("heartbeat_ms",0L);
    if(new WatchStore(this).enabled()&&beat>0&&
       System.currentTimeMillis()-beat<150_000L)return;
    // Persisted enabled=true after Vivo kills the process does NOT mean it is running.
    try{
      android.content.Intent intent=new android.content.Intent(this,ScannerService.class)
        .setAction(ScannerService.START);
      if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
    }catch(RuntimeException ex){
      new WatchStore(this).audit("MONITOR","SESSION","INACTIVE","START_BLOCKED",
        "Android denied foreground start; use Settings to start local monitoring",0);
    }
  }
  @Override protected void onPause(){handler.removeCallbacks(autoUpdate);super.onPause();}
  @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
  private String serverUrl(){
    return getSharedPreferences("mathformula_shared_runtime_v30",MODE_PRIVATE).getString("server_url","");
  }
  private boolean serverConfigured(){return !serverUrl().isEmpty();}
  private void fetchRemote(){
    if(remoteBusy||!serverConfigured())return;
    long now=System.currentTimeMillis();
    if(now-remoteLastAttempt<25000L)return;
    remoteLastAttempt=now;remoteBusy=true;
    String base=serverUrl(),token=secrets.get("server_viewer_token");
    worker.execute(()->{
      JSONArray active=null,closed=null;String failure="";
      try{
        BackendReadOnlyClient api=new BackendReadOnlyClient(base,token);
        JSONObject l=api.get("/v1/recommendations/live");
        JSONObject c=api.get("/v1/recommendations/closed");
        if(!"authoritative-v2.1".equals(l.optString("recommendationSchema"))||
           !"authoritative-v2.1".equals(c.optString("recommendationSchema")))
          throw new SecurityException("Scanner version mismatch");
        active=l.optJSONArray("items");closed=c.optJSONArray("items");
        if(active==null||closed==null)throw new IllegalStateException("Incomplete scanner results");
      }catch(Exception ex){
        failure=ex instanceof SecurityException?"Scanner authentication/version mismatch":
          "Scanner offline · check HTTPS address and token in Settings";
      }
      JSONArray liveRows=active,closedRows=closed;String issue=failure;
      handler.post(()->{
        remoteBusy=false;
        if(issue.isEmpty()){
          remoteLive=liveRows;remoteClosed=closedRows;
          remoteConnected=true;remoteLastSuccess=System.currentTimeMillis();remoteError="";
        }else{remoteConnected=false;remoteError=issue;}
        render();
      });
    });
  }
  private void ensureFresh(){ /* Full universe research is strictly overnight. */ }
  private void refreshPriorityWatchlist(){
    long now=System.currentTimeMillis();
    if(!open()||priorityBusy||!secrets.configured()||discovery.scanning()||
       now-lastPriorityAttempt<60000L)return;
    if(discovery.priorityWatchlist().length()==0)return;
    priorityBusy=true;lastPriorityAttempt=now;
    worker.execute(()->{
      try{discovery.refreshPriority(groww);}
      catch(Exception ex){handler.post(()->scanProgress="Priority quote refresh: "+safeReason(ex));}
      finally{handler.post(()->{priorityBusy=false;render();});}
    });
  }
  private boolean safeOvernightWindow(){
    java.time.LocalTime t=java.time.Instant.now().atZone(MarketSchedule.IST).toLocalTime();
    return !t.isBefore(java.time.LocalTime.of(16,30))||
      t.isBefore(java.time.LocalTime.of(7,30));
  }
  private void ensureNextSessionFresh(){
    if(serverConfigured()||open()||!secrets.configured()||busy||quoteBusy||discovery.scanning())return;
    long now=System.currentTimeMillis();
    java.time.LocalTime t=Instant.ofEpochMilli(now).atZone(MarketSchedule.IST).toLocalTime();
    // EOD finalized only after 16:30. Never start resource-heavy research
    // in pre-market, intraday, or just after the close.
    if(!safeOvernightWindow())return;
    if(discovery.completedForCurrentEod())return;
    if(discovery.lastAttempt()>0 && now-discovery.lastAttempt()<45*60000L)return;
    runNextSessionScan(false);
  }
  private void runNextSessionScan(boolean force){
    if(serverConfigured()||busy||quoteBusy||discovery.scanning()||!secrets.configured())return;
    if(!safeOvernightWindow()){
      Toast.makeText(this,"Full NSE scan runs only 16:30–07:30 IST. Monitoring the saved shortlist during market hours.",Toast.LENGTH_LONG).show();
      return;
    }
    long now=System.currentTimeMillis();
    if(discovery.completedForCurrentEod()){
      Toast.makeText(this,"Latest NSE EOD full-universe scan already complete. Reusing cached shortlist.",Toast.LENGTH_LONG).show();
      return;
    }
    // Avoid accidental NSE-wide API request bursts from repeated manual taps.
    if(discovery.lastAttempt()>0 && now-discovery.lastAttempt()<45*60000L){
      if(force)Toast.makeText(this,"Previous full scan attempted recently. See scanner status.",Toast.LENGTH_LONG).show();
      return;
    }
    busy=true;scanProgress="Screening completed NSE market data for next session";render();
    worker.execute(()->{
      String problem="";
      try{
        discovery.scanForNextSession(groww,progress->handler.post(()->{
          scanProgress=progress;render();
        }));
      }catch(Exception ex){problem=safeReason(ex);}
      String error=problem;
      handler.post(()->{busy=false;scanProgress=error;render();});
    });
  }
  private void manualScan(){
    if(serverConfigured()){
      remoteLastAttempt=0;
      fetchRemote();
      Toast.makeText(this,"Refreshing authoritative scanner feed",Toast.LENGTH_SHORT).show();
      return;
    }
    if(!secrets.configured()){
      Toast.makeText(this,"Save and verify Groww credentials in Settings first",Toast.LENGTH_LONG).show();return;
    }
    if(!safeOvernightWindow()){
      if(open()){refreshPriorityWatchlist();refreshPrices();}
      Toast.makeText(this,open()?
        "Refreshing only the overnight shortlist (up to 50 NSE stocks)":
        "Full-universe scans are scheduled 16:30–07:30 IST, after NSE closing data is available.",
        Toast.LENGTH_LONG).show();
      return;
    }
    if(!open()){
      runNextSessionScan(true);
      return;
    }
    if(busy||discovery.scanning()){
      Toast.makeText(this,"NSE scan already running · see live progress",Toast.LENGTH_SHORT).show();return;
    }
    if(System.currentTimeMillis()-discovery.lastAttempt()<60000L){
      Toast.makeText(this,"A full scan was attempted recently. Allow one minute before retrying.",Toast.LENGTH_LONG).show();
      return;
    }
    refreshPriorityWatchlist();refreshPrices();
  }
  private void runFullScan(boolean force){
    if(busy||quoteBusy||!safeOvernightWindow()||!secrets.configured()||discovery.scanning())return;
    long now=System.currentTimeMillis();
    long interval=MarketSchedule.preClose(now)?5*60000L:15*60000L;
    if(!force && discovery.lastScan()>0 && now-discovery.lastScan()<interval)return;
    // A failed scan is NOT "completed"; retry with a bounded minimum interval.
    if(!force && discovery.lastAttempt()>0 && now-discovery.lastAttempt()<2*60000L)return;
    busy=true;scanProgress="Updating NSE symbol master and fetching Groww prices";render();
    worker.execute(()->{
      String note="";
      try{
        discovery.scan(groww,stage->handler.post(()->{scanProgress=stage;render();}));
      }catch(Exception e){note=safeReason(e);}
      String result=note;
      handler.post(()->{
        busy=false;scanProgress=result;
        lastQuoteAttempt=0;
        render();
        if(result.isEmpty())refreshPrices();
      });
    });
  }
  private void evaluateAlerts(){
    long now=System.currentTimeMillis();
    if(!open()||busy||quoteBusy||!secrets.configured()||now-lastAlertAttempt<5*60000L)return;
    JSONObject snap=discovery.latest();
    long last=snap.optLong("scannedAtMs",0);
    if(last<=0||now-last>25*60000L)return;
    lastAlertAttempt=now;
    worker.execute(()->{
      try{ResearchAlerts.evaluate(this,groww,snap);}
      catch(Exception ignored){}
      handler.post(this::render);
    });
  }
  private static String safeReason(Exception error){
    if(error instanceof SecurityException)return "Groww sign-in could not be verified";
    String msg=error.getMessage()==null?"":error.getMessage().toLowerCase(Locale.US);
    if(msg.contains("rate limit")||msg.contains("429"))return "Groww rate limit reached";
    if(msg.contains("instrument")||msg.contains("master"))return "NSE symbol list unavailable";
    if(msg.contains("price scan"))return "Incomplete market-data coverage";
    return "Market-data verification failed; see diagnostics";
  }
  /** Refresh selected prices, without pretending the entire NSE universe was rescanned. */
  private void refreshPrices(){
    if(!open()||busy||quoteBusy||!secrets.configured())return;
    long now=System.currentTimeMillis();
    if(now-lastQuoteAttempt<30000)return;
    JSONArray list=discovery.latest().optJSONArray("top");
    if(list==null||list.length()==0)return;
    lastQuoteAttempt=now;quoteBusy=true;
    worker.execute(()->{
      Map<String,GrowwReadOnlyClient.Market> latest=new HashMap<>();
      for(int i=0;i<Math.min(3,list.length());i++){
        JSONObject pick=list.optJSONObject(i);
        if(pick==null)continue;
        String symbol=pick.optString("symbol","");
        try{latest.put(symbol,groww.quote(symbol));}
        catch(Exception ignored){} // Quoted timestamp remains stale if this call fails.
      }
      discovery.applyQuoteRefresh(latest);
      handler.post(()->{quoteBusy=false;render();});
    });
  }
  private long nextScanTime(){
    long now=System.currentTimeMillis();
    long previous=discovery.lastScan();
    if(!safeOvernightWindow())return 0; // No full-universe scan during trading/pre-open.
    if(discovery.completedForCurrentEod())return 0;
    return previous==0?now:Math.max(now,discovery.lastAttempt()+45*60000L);
  }
  private String rejectionSummary(JSONObject reasons){
    java.util.Iterator<String> keys=reasons.keys();
    StringBuilder out=new StringBuilder();
    while(keys.hasNext()){
      String key=keys.next();
      if(out.length()>0)out.append(" · ");
      out.append(key.replace('_',' ')).append(": ").append(reasons.optInt(key,0));
    }
    return out.length()==0?"No rejections recorded":out.toString();
  }
  private void preliminaryCard(JSONObject candidate,int number){
    if(candidate==null)return;
    LinearLayout c=card();
    c.addView(text("#"+number+" · "+candidate.optString("symbol","UNKNOWN"),17,INK,true));
    spaceIn(c,5);
    c.addView(text("PRELIMINARY · EOD HISTORY / ALPHA-30 NOT VERIFIED",11,AMBER,true));
    spaceIn(c,5);
    c.addView(text("Groww observed last traded: "+money(candidate.optDouble("price",Double.NaN)),15,INK,true));
    c.addView(text("Technical move from open: "+percent(candidate.optDouble("fromOpenPct",Double.NaN))+
      " · preliminary technical rank "+
      String.format(Locale.US,"%.1f",candidate.optDouble("preliminaryRank",0)),12,MUTED,false));
    spaceIn(c,6);
    c.addView(text("This is a provisional name from whole-universe screening, NOT a verified closing price, recommendation or BUY.",11,AMBER,false));
  }
  private void scanStatusCard(){
    LinearLayout c=card();
    boolean marketOpen=open();
    String state=serverConfigured()?"SERVER VIEW":busy||discovery.scanning()?
      "SCAN RUNNING":!marketOpen?"NEXT-SESSION RESEARCH":"AUTO DISCOVERY ACTIVE";
    c.addView(text("Scanner status  ·  "+state,15,
      busy||discovery.scanning()?BLUE:marketOpen?GREEN:AMBER,true));
    spaceIn(c,7);
    if(serverConfigured()){
      c.addView(text("Last server sync: "+time(remoteLastSuccess),12,MUTED,false));
      c.addView(text("Viewer refresh: every 30 seconds while this screen is open",11,MUTED,false));
      c.addView(text(remoteConnected?"Scanner API connected":remoteError.isEmpty()?
        "Awaiting scanner response":remoteError,12,remoteConnected?GREEN:AMBER,false));
    }else{
      c.addView(text("Last full successful scan: "+time(discovery.lastScan()),12,INK,false));
      JSONObject tracked=discovery.priorityLatest();
      if(tracked.optInt("requested",0)>0){
        c.addView(text("Daytime shortlist: "+tracked.optInt("received")+" / "+
          tracked.optInt("requested")+" batch LTP prices · "+
          time(tracked.optLong("observedMs")),11,MUTED,false));
      }else if(discovery.priorityWatchlist().length()>0){
        c.addView(text("Overnight priority symbols ready: "+
          discovery.priorityWatchlist().length()+" (not yet refreshed during trading)",11,BLUE,false));
      }
      c.addView(text("Last scan attempt: "+time(discovery.lastAttempt()),12,MUTED,false));
      c.addView(text("Intraday NSE radar: "+
        (radarBusy?"RUNNING":intradayRadar.fresh()?"CURRENT":intradayRadar.issue().isEmpty()?"WAITING":"FAILED · "+intradayRadar.issue())+
        " · last complete "+time(intradayRadar.completedAt()),12,
        intradayRadar.fresh()?GREEN:AMBER,true));
      c.addView(text("Result: "+discovery.lastAttemptState(),12,
        discovery.error().isEmpty()?MUTED:AMBER,false));
      if("NEXT_SESSION_RESEARCH".equals(discovery.latest().optString("scanMode"))){
        spaceIn(c,5);
        c.addView(text("Next-session shortlist · completed EOD prices, NOT LIVE",12,BLUE,true));
      }
      if(busy||discovery.scanning()){
        spaceIn(c,5);
        c.addView(text("Current progress: "+(scanProgress.isEmpty()?"Scanning NSE…":scanProgress),
          12,BLUE,true));
      }
      JSONObject latest=discovery.latest();
      JSONObject running=discovery.inProgress();
      if(running.optInt("total")>0){
        spaceIn(c,6);
        c.addView(text("Historical checks: "+running.optInt("checked")+
          " / "+running.optInt("total")+" · verified candidates "+running.optInt("passed"),
          12,BLUE,true));
        JSONObject rejected=running.optJSONObject("rejections");
        if(rejected!=null&&rejected.length()>0)
          c.addView(text("Rejection breakdown: "+rejectionSummary(rejected),11,MUTED,false));
      }
      if(discovery.lastScan()>0){
        spaceIn(c,5);
        c.addView(text("Stocks in master: "+latest.optInt("universeCount",0)+
          " · Quotes received: "+latest.optInt("quotedCount",0)+
          " · History checked: "+latest.optInt("dailyHistoryVerified",0)+
          " · Research-qualified: "+latest.optInt("eligibleForResearch",0),
          11,MUTED,false));
        JSONObject coverage=latest.optJSONObject("universeCoverage");
        if(coverage!=null){
          spaceIn(c,4);
          c.addView(text("Groww universe: "+coverage.optInt("eligibleUniqueEquities")+" NSE equities"+
            " · series "+coverage.optJSONObject("eligibleSeries"),11,MUTED,false));
          c.addView(text("Excluded instrument rows: non-equities "+
            coverage.optInt("excludedNonEquityRows")+" · other series "+
            coverage.optInt("excludedSeriesRows"),11,MUTED,false));
        }
        c.addView(text("Full-universe quote batches: "+
          latest.optInt("batchesSucceeded")+" successful · "+
          latest.optInt("batchesFailed")+" failed",11,MUTED,false));
        c.addView(text("History reviewed: "+
          latest.optInt("dailyHistoryVerified")+" of "+
          latest.optInt("broadCandidates")+" broad candidates",11,MUTED,false));
        JSONObject reasons=latest.optJSONObject("verificationRejectReasons");
        if(reasons!=null&&reasons.length()>0){
          c.addView(text("Historical rejection audit: "+rejectionSummary(reasons),11,MUTED,false));
        }
      }
      spaceIn(c,7);
      c.addView(text("Whole NSE universe: once per completed EOD, only 16:30–07:30 IST; resumable overnight.",11,MUTED,false));
      c.addView(text("Market hours: best-effort 15-minute NSE-wide batch radar plus up to 50 priority quotes and 5 detailed candidates.",11,MUTED,false));
      c.addView(text("Groww historical budget: max 3/min. Existing 429 cooldown: 30 min; other apps share account limits.",11,AMBER,false));
      spaceIn(c,5);
      OfficialNseFeed official=new OfficialNseFeed(this);
      c.addView(text("Official NSE corporate RSS: "+official.status()+" · "+official.count()+
        " announcements · last downloaded "+time(official.completed())+
        " · research metadata only (no fabricated C11-C17 scores).",
        11,MUTED,false));
      c.addView(text(nextScanTime()==0?"Next full-universe scan: after 16:30 IST or already complete":
        "Next overnight research eligibility: "+time(nextScanTime()),11,BLUE,true));
    }
    spaceIn(c,11);
    MaterialButton button=new MaterialButton(this);
    button.setAllCaps(false);button.setText(serverConfigured()?"Refresh scanner feed":
      safeOvernightWindow()?"Resume overnight full NSE scan":"Refresh selected 50 only");
    button.setTextSize(13);button.setTextColor(Color.WHITE);
    button.setBackgroundTintList(ColorStateList.valueOf(BLUE));button.setCornerRadius(dp(14));
    button.setEnabled(!busy&&!discovery.scanning()&&!remoteBusy);
    button.setOnClickListener(v->manualScan());
    c.addView(button,new LinearLayout.LayoutParams(-1,dp(48)));
    if(!marketOpen && !serverConfigured()){
      spaceIn(c,6);
      c.addView(text("Full NSE discovery is limited to 16:30–07:30 IST. During market hours we monitor the saved shortlist.",11,AMBER,false));
    }
  }
  private void tabHeader(){
    LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout brand=new LinearLayout(this);brand.setOrientation(LinearLayout.VERTICAL);
    brand.addView(text("MathFormula",27,INK,true));
    brand.addView(text("RESEARCH DESK  /  ALPHA-30 v1.0",10,BLUE,true));
    long verified=getSharedPreferences("mathformula_groww_connection_v22",MODE_PRIVATE).getLong("groww_verified_at_v22",0L);
    long since=System.currentTimeMillis()-verified;
    boolean authorized=secrets.configured()&&verified>0&&since>=0&&since<=15*60_000L;
    brand.addView(text(authorized?"✓ Groww authenticated":
        secrets.configured()?"○ Groww credentials saved · session check pending":"○ Groww not connected",
        11,authorized?GREEN:AMBER,true));
    top.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
    TextView settings=text("⚙",24,BLUE,true);
    settings.setContentDescription("Open streamlined settings");
    settings.setGravity(Gravity.CENTER);
    settings.setPadding(dp(14),dp(10),dp(14),dp(10));
    settings.setBackground(rounded(Color.WHITE));
    settings.setClickable(true);settings.setFocusable(true);
    settings.setOnClickListener(v->startActivity(new Intent(this,MainActivity.class)));
    top.addView(settings);root.addView(top);
    space(20);
    TabLayout tabs=new TabLayout(this);
    tabs.setTabMode(TabLayout.MODE_FIXED);
    tabs.setTabGravity(TabLayout.GRAVITY_FILL);
    tabs.setSelectedTabIndicatorColor(BLUE);
    tabs.setSelectedTabIndicatorHeight(dp(3));
    tabs.setTabTextColors(MUTED,BLUE);
    tabs.setBackground(rounded(Color.WHITE));
    tabs.addTab(tabs.newTab().setText("Market watch"));
    tabs.addTab(tabs.newTab().setText("History"));
    if("CLOSED".equals(section))tabs.getTabAt(1).select();
    tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener(){
      @Override public void onTabSelected(TabLayout.Tab tab){
        section=tab.getPosition()==0?"LIVE":"CLOSED";render();
        if("LIVE".equals(section)){ensureFresh();refreshPrices();}
      }
      @Override public void onTabUnselected(TabLayout.Tab tab){}
      @Override public void onTabReselected(TabLayout.Tab tab){}
    });
    root.addView(tabs,new LinearLayout.LayoutParams(-1,dp(56)));space(14);
    systemHealth();
  }
  private void systemHealth(){
    LinearLayout grid=new LinearLayout(this);
    grid.setOrientation(LinearLayout.HORIZONTAL);
    String market=open()?"OPEN":"CLOSED";
    String growwState=!secrets.configured()?"SETUP":new WatchStore(this).lastPollOk()?
      "DATA SEEN":"SAVED";
    long beat=getSharedPreferences("mathformula_local_monitor_v32",MODE_PRIVATE)
      .getLong("heartbeat_ms",0);
    boolean heartbeatFresh=beat>0&&System.currentTimeMillis()-beat<150_000L;
    String scanner=serverConfigured()?remoteConnected?"SERVER":"SERVER DOWN":
      heartbeatFresh?"ACTIVE":intradayRadar.fresh()?"RADAR FRESH":"PAUSED";
    String alerts=!ResearchAlerts.permission(this)?"BLOCKED":"ON PHONE";
    String[] labels={"MARKET","GROWW","SCANNER","ALERTS"};
    String[] values={market,growwState,scanner,alerts};
    for(int i=0;i<labels.length;i++){
      LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);
      c.setPadding(dp(8),dp(11),dp(8),dp(11));
      c.setBackground(rounded(Color.WHITE));
      c.addView(text(labels[i],9,MUTED,true));
      c.addView(text(values[i],11,INK,true));
      LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
      lp.setMargins(dp(2),0,dp(2),0);grid.addView(c,lp);
    }
    root.addView(grid);
    space(8);
    long latestBeat=getSharedPreferences("mathformula_local_monitor_v32",MODE_PRIVATE)
      .getLong("heartbeat_ms",0L);
    String localState=getSharedPreferences("mathformula_local_monitor_v32",MODE_PRIVATE)
      .getString("monitoring_state","NOT STARTED");
    root.addView(text("Phone monitor: "+localState+" · last heartbeat "+
      time(latestBeat)+" · Android/Vivo may stop background execution.",
      11,latestBeat>0&&System.currentTimeMillis()-latestBeat<150_000L?GREEN:AMBER,false));
    space(14);
  }
  private void summaryCard(JSONObject scan,boolean closed){
    LinearLayout c=card();
    long completed=scan.optLong("scannedAtMs",0);
    long elapsed=System.currentTimeMillis()-completed;
    long threshold=MarketSchedule.preClose(System.currentTimeMillis())?8*60000L:20*60000L;
    boolean recent=completed>0&&elapsed>=0&&elapsed<=threshold;
    String state=closed?"HISTORICAL OBSERVATIONS":
      !open()?"NEXT-SESSION EOD RESEARCH · NOT LIVE":
      busy?"NSE SCAN IN PROGRESS":!secrets.configured()?"CONNECT GROWW IN SETTINGS":
      recent?"LATEST RESEARCH SCAN":"LIVE DATA NOT VERIFIED";
    c.addView(text(state,12,closed?MUTED:recent?GREEN:AMBER,true));
    spaceIn(c,8);
    c.addView(text("Last complete scan · "+time(completed),12,MUTED,false));
    if(completed>0){
      spaceIn(c,7);
      c.addView(text(scan.optInt("universeCount")+" NSE instruments · "+
        scan.optInt("quotedCount")+" prices · "+
        scan.optInt("dailyHistoryVerified")+" histories checked",12,INK,false));
    }
    if(!closed){
      spaceIn(c,9);
      c.addView(text(busy?scanProgress:
        "Research watches only · no verified BUY signals. Background phone alerts are best-effort.",
        11,AMBER,false));
      c.addView(text("Notification access: "+(ResearchAlerts.permission(this)?"enabled":"off")+
        "  ·  Groww "+(secrets.configured()?"configured":"not configured"),11,MUTED,false));
    }
    if(!discovery.error().isEmpty()){
      spaceIn(c,7);
      c.addView(text("Data issue · "+safeReason(new IllegalStateException(discovery.error())),
        11,AMBER,false));
    }
  }
  private void spaceIn(LinearLayout c,int amount){
    c.addView(new View(this),new LinearLayout.LayoutParams(1,dp(amount)));
  }
  private String factorDetail(JSONArray factors){
    if(factors==null)return "No server factor evidence.";
    StringBuilder out=new StringBuilder();
    for(int i=0;i<Math.min(30,factors.length());i++){
      JSONObject j=factors.optJSONObject(i);
      if(j==null)continue;
      out.append(j.optInt("number")).append(". ")
        .append(j.optString("name")).append(" = ")
        .append(j.isNull("value")?"MISSING/STALE":j.optString("value"))
        .append("\\n");
    }
    return out.toString();
  }
  private void stockCard(JSONObject j,int rank,boolean closed){
    LinearLayout c=card();
    LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
    String symbol=j.optString("symbol","UNKNOWN");
    header.addView(text(symbol,19,INK,true),new LinearLayout.LayoutParams(0,-2,1));
    String stage=closed?RecommendationLedger.friendly(j.optString("closeCode")):
      j.optString("status",j.optString("stage","WATCHING"));
    int stageColor=closed?MUTED:stage.contains("BREAKOUT")?AMBER:BLUE;
    header.addView(text(stage.replace('_',' ').toUpperCase(Locale.US),10,stageColor,true));
    c.addView(header);spaceIn(c,5);
    boolean remote=serverConfigured()&&remoteConnected;
    boolean buyNow=remote&&!closed&&open()&&
      "BUY_ELIGIBLE".equals(j.optString("signal",""))&&
      j.optBoolean("quoteFreshNow",false)&&
      System.currentTimeMillis()-j.optLong("evaluationTimeMs",0)<=10000;
    c.addView(text(buyNow?"VERIFIED BUY ELIGIBLE · VERIFY FRESHNESS BEFORE ACTING":
      closed?"ARCHIVED RESEARCH · NO EXECUTED TRADE":
      remote?"SERVER RESEARCH · "+j.optString("signal","NO SIGNAL"):
             "LOCAL RESEARCH · NO SIGNAL",
      10,buyNow?GREEN:MUTED,true));
    spaceIn(c,12);
    double price=j.optDouble("price",Double.NaN);
    double pct=j.optDouble("dayChangePct",Double.NaN);
    c.addView(text(money(price)+"    "+percent(pct),19,INK,true));
    spaceIn(c,8);
    c.addView(text("Breakout reference  "+money(j.optDouble("breakoutLevel",Double.NaN)),13,BLUE,true));
    c.addView(text("Distance vs resistance  "+percent(j.optDouble("distancePct",Double.NaN)),12,MUTED,false));
    if("NEXT_SESSION_RESEARCH".equals(j.optString("scanMode",""))){
      spaceIn(c,5);
      c.addView(text("Tomorrow's research watch · BUY confirmation pending next market session",
        11,BLUE,true));
    }
    spaceIn(c,11);
    long trade=j.optLong("tradeMs",0),now=System.currentTimeMillis();
    boolean quoteFresh=!closed&&open()&&trade>0&&now>=trade&&now-trade<=60000L;
    boolean nextSession="NEXT_SESSION_RESEARCH".equals(j.optString("scanMode",""));
    c.addView(text(nextSession?
      "Latest completed NSE session: "+j.optString("priceAsOfSession","Unavailable")+
        " · LAST SESSION CLOSE (not live)":
      (closed?"Last recorded quote · ":"Exchange observation · ")+time(trade),
      11,quoteFresh?GREEN:MUTED,false));
    spaceIn(c,7);
    JSONArray why=j.optJSONArray("reasons");
    StringBuilder explanations=new StringBuilder();
    if(why!=null)for(int i=0;i<Math.min(3,why.length());i++){
      if(i>0)explanations.append("; ");
      explanations.append(why.optString(i));
    }
    String reason=closed?j.optString("closeReason","Historical research watch closed"):
      remote?explanations.length()>0?explanations.toString():
        "Alpha-30 verified at last exchange observation":
      j.optString("reason","Pending full Alpha-30 validation");
    c.addView(text(reason,12,INK,false));
    spaceIn(c,9);
    if(remote){
      c.addView(text("Alpha-30 score "+String.format(Locale.US,"%.1f",j.optDouble("alpha30Score",0))+
        "/100 · coverage "+String.format(Locale.US,"%.1f",j.optDouble("coveragePct",0))+"%",
        11,MUTED,false));
      JSONObject gates=j.optJSONObject("execution_evidence");
      if(gates!=null&&gates.length()>0){
        spaceIn(c,5);
        c.addView(text("Entry "+money(gates.optDouble("entry",Double.NaN))+
          " · Stop "+money(gates.optDouble("stop_loss",Double.NaN))+
          " · Target "+money(gates.optDouble("target",Double.NaN)),11,INK,true));
      }
    }
    spaceIn(c,5);
    c.addView(text(buyNow?"BUY ELIGIBLE AT VERIFIED INSTANT · READ DETAILS":
      "NO CURRENT VERIFIED BUY · SEE MISSING REQUIREMENTS",
      11,buyNow?GREEN:AMBER,true));
    c.setContentDescription(symbol+" research details");
    c.setClickable(true);c.setFocusable(true);
    c.setBackground(new android.graphics.drawable.RippleDrawable(
      android.content.res.ColorStateList.valueOf(Color.argb(35,24,91,154)),
      rounded(Color.WHITE),rounded(Color.WHITE)));
    c.setOnClickListener(v->{
      String message="Status: "+stage+"\n\nObserved price: "+money(price)+
        "\nPrior resistance: "+money(j.optDouble("breakoutLevel",Double.NaN))+
        "\nObserved trade: "+time(trade)+
        "\nFirst selected: "+time(j.optLong("openedAtMs",j.optLong("scannedAtMs",0)))+
        (closed?"\nClosed: "+time(j.optLong("closedAtMs",0)):"")+
        "\n\nReason: "+reason+
        "\n\nAlpha-30: "+(buyNow?"Verified at observed instant; recheck current quote.":
          "NO CURRENT VERIFIED BUY")+
        "\n\n"+(remote?"Evidence:\n"+factorDetail(j.optJSONArray("factor_evidence")):
          "Local research-only candidate; full data unavailable.");
      new android.app.AlertDialog.Builder(this).setTitle(symbol+" · recommendation record")
        .setMessage(message).setPositiveButton("Done",null).show();
    });
  }
  private void importEvidence(){
    Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("text/*");
    try{startActivityForResult(intent,IMPORT_EVIDENCE);}
    catch(Exception ex){Toast.makeText(this,"Document picker not available",Toast.LENGTH_LONG).show();}
  }
  private void overnightEvidencePanel(JSONObject scan){
    LinearLayout c=card();
    c.addView(text("PDF Alpha-30 · 29-factor overnight qualification",17,INK,true));
    spaceIn(c,7);
    c.addView(text("Frozen groups: Fundamentals 27 · Catalysts 23 · Macro 19 · Trading 31. "+
      "Overnight denominator 91; T30 excluded. Score ≥68 AND coverage ≥90% required.",11,MUTED,false));
    spaceIn(c,8);
    JSONArray pool=scan.optJSONArray("verifiedTechnicalPool");
    ArrayList<JSONObject> eligible=new ArrayList<>();
    int sufficient=0,scored=0;double bestCoverage=0;
    long now=System.currentTimeMillis();
    Map<String,Alpha30EvidenceStore.EvidenceScore> batch=evidenceStore.bulkOvernight(pool,now);
    if(pool!=null){
      for(int i=0;i<pool.length();i++){
        JSONObject candidate=pool.optJSONObject(i);if(candidate==null)continue;
        String symbol=candidate.optString("symbol","");
        if(!symbol.matches("[A-Z0-9&-]{1,26}")||
           !"EQ".equals(candidate.optString("nseSeries","")))continue;
        Alpha30EvidenceStore.EvidenceScore proof=batch.get(symbol);
        if(proof==null)continue;
        scored++;
        bestCoverage=Math.max(bestCoverage,proof.overnight.coverage);
        if(proof.overnight.coverage>=90)sufficient++;
        if(proof.shortlistEligible()){
          try{
            JSONObject row=new JSONObject(candidate.toString());
            row.put("alpha30OvernightScore",proof.overnight.value);
            row.put("alpha30Coverage",proof.overnight.coverage);
            row.put("observedFactors",proof.observed);
            eligible.add(row);
          }catch(Exception ignored){}
        }
      }
    }
    eligible.sort((a,b)->Double.compare(
        b.optDouble("alpha30OvernightScore",0),a.optDouble("alpha30OvernightScore",0)));
    c.addView(text("Normal EQ-series historical candidates reviewed: "+scored+
      " · sufficient factor coverage: "+sufficient+
      " · overnight score-qualified: "+eligible.size(),12,INK,true));
    spaceIn(c,4);
    c.addView(text("Best evidence coverage: "+
      String.format(Locale.US,"%.1f%%",bestCoverage)+" · missing values are NOT scored as verified",
      11,MUTED,false));
    if(eligible.isEmpty()){
      spaceIn(c,8);
      c.addView(text("NO PDF-QUALIFIED OVERNIGHT TOP 3 · source-backed financial, catalyst and macro factors "+
        "are not automatically supplied by Groww market quotes.",12,AMBER,true));
    }else{
      spaceIn(c,8);
      c.addView(text("Source-declared Alpha-30 watchlist · verify provenance independently",12,BLUE,true));
      for(int i=0;i<Math.min(3,eligible.size());i++){
        JSONObject candidate=eligible.get(i);
        String symbol=candidate.optString("symbol");
        Alpha30EvidenceStore.EvidenceScore score=evidenceStore.overnight(symbol,now);
        final String explain="Observed 29-factor score: "+
          String.format(Locale.US,"%.2f /100",score.overnight.value)+
          "\nWeighted coverage: "+String.format(Locale.US,"%.1f%%",score.overnight.coverage)+
          "\nObserved factors: "+score.observed+"/29"+
          "\nDeclared HTTPS sources must be verified independently. Live entry and fees NOT validated."+
          "\nMissing factors: "+score.missingNames.toString();
        TextView row=text((i+1)+". "+symbol+" · score "+
          String.format(Locale.US,"%.1f",score.overnight.value)+
          " · coverage "+String.format(Locale.US,"%.0f%%",score.overnight.coverage),
          13,BLUE,true);
        row.setPadding(dp(0),dp(8),dp(0),dp(8));
        row.setOnClickListener(v->new android.app.AlertDialog.Builder(this)
          .setTitle(symbol+" · Alpha-30 source evidence").setMessage(explain)
          .setPositiveButton("Close",null).show());
        c.addView(row);
      }
    }
    spaceIn(c,10);
    MaterialButton importButton=new MaterialButton(this);
    importButton.setAllCaps(false);
    importButton.setText("Import sourced Alpha-30 factor CSV");
    importButton.setTextColor(Color.WHITE);importButton.setBackgroundTintList(ColorStateList.valueOf(BLUE));importButton.setCornerRadius(dp(14));
    importButton.setOnClickListener(v->importEvidence());
    c.addView(importButton,new LinearLayout.LayoutParams(-1,dp(48)));
    spaceIn(c,5);
    c.addView(text("CSV: symbol,factor,value,observed_at_utc,source · factor 1–30 · value 0–1 · timestamp in UTC/offset · HTTPS URL.",
      11,MUTED,false));
    if(!evidenceImportNote.isEmpty()){
      spaceIn(c,4);c.addView(text(evidenceImportNote,11,AMBER,false));
    }
  }

  /** Broad live NSE price discovery; this is research ONLY, never a BUY validator. */
  private void refreshIntradayRadarIfDue(){
    if(!open()||serverConfigured()||!secrets.configured()||
       radarBusy||discovery.scanning()||!intradayRadar.due())return;
    radarBusy=true;
    worker.execute(()->{
      String problem="";
      try{
        intradayRadar.scan(groww,discovery,update->{});
      }catch(Exception ex){
        problem=ex instanceof SecurityException?"Groww approval/authentication required":
          ex.getMessage()!=null&&ex.getMessage().contains("429")?
          "Groww quota reached · retry after cooldown":
          "Market-wide radar interrupted · check scanner health";
      }
      final String message=problem;
      handler.post(()->{
        radarBusy=false;
        if(!message.isEmpty())scanProgress=message;
        render();
      });
    });
  }
  /** Clear technical-only early discovery, separately from the frozen Alpha-30 BUY engine. */
  private void radarPanel(){
    if(serverConfigured()||!open())return;
    JSONObject snapshot=intradayRadar.latest();
    JSONArray pool=intradayRadar.currentCandidates();
    long at=snapshot.optLong("observedMs",0L);
    if(pool==null||pool.length()==0){
      if(radarBusy)root.addView(text("Intraday NSE radar · scanning current market breadth…",
          12,BLUE,true));
      else if(at>0&&!intradayRadar.fresh())
        root.addView(text("Intraday NSE radar: last data is stale · no current leads shown.",
          12,AMBER,true));
      return;
    }
    root.addView(text("Today's fresh NSE discoveries",18,INK,true));
    space(4);
    root.addView(text("Screened "+snapshot.optInt("scannedUniverse",0)+
      " NSE equities · "+snapshot.optInt("quotesLoaded",0)+" prices · "+
      time(at)+" · "+snapshot.optInt("historicalReferencesMatched",0)+
      " reusable H20 histories. Strictly research-only; not all 30 factors verified.",
      11,MUTED,false));
    space(9);
    for(int i=0;i<Math.min(5,pool.length());i++){
      JSONObject lead=pool.optJSONObject(i);
      if(lead==null)continue;
      LinearLayout card=card();
      String symbol=lead.optString("symbol","UNKNOWN");
      card.addView(text((i+1)+". "+symbol+
        (lead.optBoolean("historyVerified")?" · H20 CHECKED":" · PRELIMINARY"),
        16,INK,true));
      spaceIn(card,5);
      card.addView(text(money(lead.optDouble("price",Double.NaN))+
        "  ·  from open "+percent(lead.optDouble("fromOpenPct",Double.NaN)),
        15,BLUE,true));
      if(lead.optBoolean("historyVerified")){
        spaceIn(card,4);
        card.addView(text("Prior 20-session high: "+
          money(lead.optDouble("breakoutLevel",Double.NaN))+
          " · distance "+percent(lead.optDouble("distancePct",Double.NaN)),
          12,INK,false));
      }
      spaceIn(card,5);
      Alpha30EvidenceStore.EvidenceScore factors=evidenceStore.overnight(symbol,System.currentTimeMillis());
      card.addView(text("Alpha-30 source coverage: "+
        String.format(Locale.US,"%.1f%%",factors.overnight.coverage)+
        " · "+factors.observed+"/29 overnight features · "+
        (factors.overnight.coverage>=90?"review normalization":"insufficient coverage"),
        11,MUTED,false));
      spaceIn(card,5);
      card.addView(text("WATCH ONLY · Bulk quotes lack broker trade timestamp and live gates. "+
        "No Alpha-30 BUY verified.",11,AMBER,true));
    }
    space(10);
  }

  /** Research citations are discoverable links, never pre-filled numeric scores. */
  private void webResearchPanel(){
    InternetResearchEngine web=new InternetResearchEngine(this);
    JSONObject snapshot=web.latest();
    JSONArray results=snapshot.optJSONArray("results");
    LinearLayout shell=card();
    shell.addView(text("Internet research · evidence discovery",17,INK,true));
    spaceIn(shell,5);
    shell.addView(text(web.status()+" · last completed "+time(web.completedAt()),
      11,MUTED,false));
    spaceIn(shell,4);
    shell.addView(text("Searches happen directly on this phone. A public NSE/BSE webpage or web snippet must still be parsed and verified before any of F01–T30 is scored.",11,AMBER,false));
    spaceIn(shell,7);
    OfficialFinancialCollector primary=new OfficialFinancialCollector(this);
    JSONObject financial=primary.latest();
    shell.addView(text("NSE filing facts: "+primary.status()+
      " · last updated "+time(primary.updated()),11,MUTED,false));
    JSONArray raw=financial.optJSONArray("rawFacts");
    if(raw!=null&&raw.length()>0){
      spaceIn(shell,5);
      shell.addView(text("Extracted raw amounts (NOT normalized Alpha-30 scores):",12,INK,true));
      for(int i=0;i<Math.min(3,raw.length());i++){
        JSONObject item=raw.optJSONObject(i);if(item==null)continue;
        shell.addView(text(item.optString("hint","")+" · "+
          item.optString("value","")+" "+item.optString("unitRef","")+
          " · context "+item.optString("contextRef","")+
          " · issuer and fiscal basis not verified",11,AMBER,false));
      }
    }
    if(results==null||results.length()==0){
      spaceIn(shell,7);
      shell.addView(text(web.configured()?
        "No completed cited research yet · evening jobs resume when Android allows.":
        "Official NSE disclosures are collected without a key. For wider Internet search, configure an optional API key in Settings.",
        12,MUTED,false));
      return;
    }
    int shown=0;
    for(int pass=0;pass<2&&shown<5;pass++)for(int i=0;i<results.length()&&shown<5;i++){
      JSONObject item=results.optJSONObject(i);if(item==null)continue;
      String url=item.optString("url","");
      String level=item.optString("tier","");
      boolean official=level.startsWith("OFFICIAL_HOST");
      if(official!=(pass==0)||!url.startsWith("https://"))continue;
      shown++;
      spaceIn(shell,8);
      TextView button=text((official?"OFFICIAL DOMAIN · ":"SECONDARY · ")+
        item.optString("title","Untitled"),12,BLUE,true);
      button.setPadding(dp(8),dp(9),dp(8),dp(9));
      button.setMinimumHeight(dp(48));button.setClickable(true);button.setFocusable(true);
      button.setContentDescription("Open source: "+item.optString("title",""));
      button.setOnClickListener(v->{
        try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)));}
        catch(Exception ignored){Toast.makeText(this,"Browser unavailable",Toast.LENGTH_SHORT).show();}
      });
      shell.addView(button);
      shell.addView(text(item.optString("excerpt",""),11,MUTED,false));
    }
  }

  private void renderLive(){
    JSONObject scan=discovery.latest();
    JSONArray latestTop=scan.optJSONArray("top");
    JSONArray live=serverConfigured()?(remoteConnected?remoteLive:new JSONArray()):
      latestTop!=null?latestTop:ledger.live();
    JSONObject preliminary=discovery.preview();
    JSONArray provisional=serverConfigured()?new JSONArray():preliminary.optJSONArray("top");
    if(provisional==null)provisional=new JSONArray();
    boolean nextSession="NEXT_SESSION_RESEARCH".equals(scan.optString("scanMode",""));
    root.addView(text(nextSession?"Next-session research watchlist":"Market watch · Alpha-30",22,INK,true));
    space(4);
    root.addView(text(nextSession?
       "Latest completed NSE session: "+scan.optString("sourceSessionDate","unknown")+
       " · technical watchlist, NOT today's confirmed live ranking":
       "Research candidates; current market leadership can change. No broker-verified BUY.",
       12,MUTED,false));
    space(9);
    if(!serverConfigured()){
      root.addView(text("PHONE-ONLY: local foreground scanning and local notifications. NSE-wide price radar roughly every 15 minutes while the process runs; Android/Vivo can delay or terminate the service. All 30 factors require independent evidence.",12,AMBER,true));
    }
    space(13);
    LinearLayout overview=card();
    overview.addView(text(live.length()+" historically verified watches · "+
      provisional.length()+" provisional whole-universe leads",17,INK,true));
    spaceIn(overview,5);
    int verified=0;
    if(serverConfigured()&&remoteConnected&&open())
      for(int i=0;i<live.length();i++){
        JSONObject row=live.optJSONObject(i);
        if(row!=null&&"BUY_ELIGIBLE".equals(row.optString("signal"))&&
           row.optBoolean("quoteFreshNow",false)&&
           System.currentTimeMillis()-row.optLong("evaluationTimeMs",0)<=10000)verified++;
      }
    overview.addView(text("Currently verified BUY eligible: "+verified,12,verified>0?GREEN:AMBER,false));
    if(serverConfigured()){
      spaceIn(overview,7);
      overview.addView(text(remoteConnected?
        "Independent scanner connected · refreshed "+time(remoteLastSuccess):
        remoteBusy?"Connecting to independent scanner…":
        remoteError.isEmpty()?"Scanner configured, not yet connected":remoteError,
        12,remoteConnected?GREEN:AMBER,false));
    }
    if(!serverConfigured()){
      summaryCard(scan,false);
      overnightEvidencePanel(scan);
      if(!open())webResearchPanel();
    }
    radarPanel();
    if(live.length()==0&&provisional.length()==0 &&
       (!open()||intradayRadar.currentCandidates()==null||intradayRadar.currentCandidates().length()==0)){
      LinearLayout empty=card();
      empty.addView(text(busy?"Scanning NSE…":"No active verified research shortlist",16,INK,true));
      spaceIn(empty,7);
      empty.addView(text(serverConfigured()?
        (remoteConnected?"No active scanner recommendations; no stocks are invented.":
          "Cannot load authoritative recommendations. Check server status in Settings."):
        !secrets.configured()?"Connect Groww in Settings for research discovery.":
        discovery.lastScan()==0?"No completed research scan yet. Use Scan Tomorrow's Top 3 after hours or Scan Now during market hours.":
        "The last completed scan found no qualified technical watches. Check scan coverage and data timestamps.",12,MUTED,false));
    }
    for(int i=0;i<live.length();i++){
      JSONObject item=live.optJSONObject(i);
      if(item!=null)stockCard(item,i+1,false);
    }
    if(!serverConfigured()&&!discovery.completedForCurrentEod()){
      JSONArray partial=discovery.partialVerifiedResearch();
      if(partial.length()>0){
        space(6);
        root.addView(text("Historically checked · incomplete overnight scan",17,INK,true));
        space(4);
        root.addView(text("These passed technical EOD checks so far. The full universe, financial factors and trade eligibility are NOT verified.",11,AMBER,false));
        space(6);
        for(int k=0;k<partial.length();k++){
          JSONObject candidate=partial.optJSONObject(k);
          if(candidate!=null)stockCard(candidate,k+1,false);
        }
      }
    }
    if(provisional.length()>0){
      space(6);
      root.addView(text("Preliminary Top 3 · awaiting historical verification",17,INK,true));
      space(4);
      root.addView(text("Names from the full Groww equity price screen. Not Alpha-30 recommendations.",11,AMBER,false));
      space(7);
      int displayed=0;
      for(int i=0;i<provisional.length()&&displayed<Math.max(0,3-live.length());i++){
        JSONObject lead=provisional.optJSONObject(i);
        if(lead==null)continue;
        boolean alreadyShown=false;
        for(int j=0;j<live.length();j++){
          JSONObject verifiedRow=live.optJSONObject(j);
          if(verifiedRow!=null&&lead.optString("symbol").equals(verifiedRow.optString("symbol"))){
            alreadyShown=true;break;
          }
        }
        if(!alreadyShown)preliminaryCard(lead,++displayed);
      }
    }
    space(8);
    root.addView(text(serverConfigured()?
      "Only fresh server-verified 30-factor decisions may display BUY eligibility; never order automatically.":
      "GROWW-ONLY RESEARCH MODE · Broader company, catalyst, macro and execution evidence is still required before a verified BUY.",
      11,MUTED,false));
  }
  private void renderClosed(){
    JSONArray records=serverConfigured()?(remoteConnected?remoteClosed:new JSONArray()):ledger.closed();
    root.addView(text("Closed recommendations",22,INK,true));space(5);
    if(serverConfigured()&&!remoteConnected){
      root.addView(text("Independent scanner unavailable · remote closed history cannot be verified.",12,AMBER,false));
      space(6);
    }
    root.addView(text("Immutable research outcomes with reasons and observation times.",12,MUTED,false));
    space(13);
    LinearLayout overview=card();
    overview.addView(text(records.length()+"  recorded closures",20,INK,true));
    spaceIn(overview,5);
    overview.addView(text("Entry missed · Expired · No longer shortlisted. No implied trade P&L.",
      12,MUTED,false));
    int start=Math.max(0,records.length()-40);
    for(int i=records.length()-1;i>=start;i--){
      JSONObject row=records.optJSONObject(i);
      if(row!=null)stockCard(row,i+1,true);
    }
    if(records.length()==0){
      LinearLayout empty=card();
      empty.addView(text("No v2.0 closures recorded yet",16,INK,true));
      spaceIn(empty,6);
      empty.addView(text("Closed records are created when a completed NSE scan or a fresh quote invalidates a watch, not merely because the exchange closes.",12,MUTED,false));
    }
    space(4);
    JSONObject previous=serverConfigured()?new JSONObject():discovery.lastClosedSession(System.currentTimeMillis());
    if(!serverConfigured()){root.addView(text("Previous local session archive",17,INK,true));space(8);}
    if(previous.length()>0){
      LinearLayout snapshot=card();
      snapshot.addView(text("Last captured session · "+previous.optString("sessionDate",""),14,INK,true));
      spaceIn(snapshot,5);
      snapshot.addView(text("Observation: "+time(previous.optLong("scannedAtMs",0))+
        " · Historical scan only, not the official exchange closing price",11,MUTED,false));
      JSONArray top=previous.optJSONArray("top");
      if(top!=null)for(int i=0;i<top.length();i++){
        JSONObject pick=top.optJSONObject(i);
        if(pick!=null){
          spaceIn(snapshot,5);
          snapshot.addView(text(pick.optString("symbol")+" · "+money(pick.optDouble("price",Double.NaN))+
            " · research watch at capture",12,INK,false));
        }
      }
    }else if(!serverConfigured()){
      LinearLayout c=card();
      c.addView(text("No prior session scan captured on this device.",12,MUTED,false));
    }
    space(12);
    TextView export=text("Export research audit ZIP  ↗",13,BLUE,true);
    export.setGravity(Gravity.CENTER);export.setPadding(dp(14),dp(16),dp(14),dp(16));
    export.setBackground(rounded(Color.WHITE));
    export.setOnClickListener(v->exportLogs());
    root.addView(export);
    space(7);
    root.addView(text("Archived observations can have gaps. These are not filled with estimated returns.",11,MUTED,false));
  }
  private void render(){
    if(root==null)return;
    final int previousScroll=mainScroll==null?0:mainScroll.getScrollY();
    root.removeAllViews();tabHeader();
    scanStatusCard();
    if("CLOSED".equals(section))renderClosed();
    else renderLive();
    // Preserve reading position across 30-second quote/health UI refreshes.
    if(mainScroll!=null&&previousScroll>0){
      mainScroll.post(()->mainScroll.scrollTo(0,previousScroll));
    }
  }
  private void exportLogs(){
    Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.setType("application/zip");intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.putExtra(Intent.EXTRA_TITLE,"MathFormula_Alpha30_"+System.currentTimeMillis()+".zip");
    try{startActivityForResult(intent,EXPORT);}
    catch(Exception ignored){Toast.makeText(this,"Android Files not available",Toast.LENGTH_LONG).show();}
  }
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(result!=RESULT_OK||data==null||data.getData()==null)return;
    if(request==IMPORT_EVIDENCE){
      Uri file=data.getData();
      worker.execute(()->{
        String resultText;
        try{resultText=evidenceStore.importEvidence(this,file).description();}
        catch(Exception ex){resultText="Evidence import rejected: "+ex.getClass().getSimpleName();}
        final String summary=resultText;
        handler.post(()->{evidenceImportNote=summary;render();});
      });
      return;
    }
    if(request!=EXPORT)return;
    Uri uri=data.getData();
    worker.execute(()->{
      String issue=null;
      try{
        // Legacy manually watched symbols are diagnostic history, not active Top 3.
        String manual=getPreferences(MODE_PRIVATE).getString("watchlist","");
        List<String> selected=new ArrayList<>();
        for(String entry:manual.split(",")){
          String symbol=entry.trim();
          if(symbol.matches("[A-Z0-9&-]{1,26}"))selected.add(symbol);
        }
        DiagnosticExporter.save(getApplicationContext(),uri,new WatchStore(this),selected,
          getSharedPreferences("mathformula_shared_runtime_v30",MODE_PRIVATE).getString("server_url",""),
          secrets.get("server_viewer_token"));
      }catch(Exception e){issue=e.getClass().getSimpleName();}
      String problem=issue;
      handler.post(()->Toast.makeText(this,problem==null?
        "Logs saved. You can upload the ZIP for analysis.":
        "Export failed: "+problem,Toast.LENGTH_LONG).show());
    });
  }
}

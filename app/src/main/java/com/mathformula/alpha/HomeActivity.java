package com.mathformula.alpha;

import android.app.Activity;
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
public final class HomeActivity extends Activity {
  private static final int INK=Color.rgb(20,38,56),BLUE=Color.rgb(24,91,154),
      MUTED=Color.rgb(103,113,127),GREEN=Color.rgb(19,123,85),
      AMBER=Color.rgb(158,98,19),RED=Color.rgb(180,59,58);
  private static final int EXPORT=867;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private LocalDiscovery discovery;
  private RecommendationLedger ledger;
  private JSONArray remoteLive=new JSONArray(),remoteClosed=new JSONArray();
  private boolean remoteBusy=false,remoteConnected=false;
  private long remoteLastAttempt=0,remoteLastSuccess=0;
  private String remoteError="";
  private SecureGrowwStore secrets;
  private GrowwReadOnlyClient groww;
  private LinearLayout root;
  private String section="LIVE",scanProgress="";
  private boolean busy=false,quoteBusy=false;
  private long lastQuoteAttempt=0,lastAlertAttempt=0;
  private boolean startedWhileOpen=false;
  private String lastVisibleDate="";
  private final Runnable autoUpdate=new Runnable(){
    @Override public void run(){
      boolean open=MarketSchedule.weekdayTradingTime(System.currentTimeMillis());
      // Keep the user's selected section. Closing bell does not close a valid research watch.
      startedWhileOpen=open;
      if(serverConfigured())fetchRemote();
      else if(open){ // Maintain research scan even when user is viewing Closed.
        ensureFresh();
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
    GradientDrawable b=new GradientDrawable();b.setColor(fill);b.setCornerRadius(dp(14));return b;
  }
  private void space(int dp){root.addView(new View(this),new LinearLayout.LayoutParams(1,dp(dp)));}
  private LinearLayout card(){
    LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);
    c.setPadding(dp(16),dp(16),dp(16),dp(16));c.setBackground(rounded(Color.WHITE));
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(13);
    root.addView(c,p);return c;
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
    ledger=new RecommendationLedger(this);
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
    ScrollView scroll=new ScrollView(this);
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
  }
  @Override protected void onPause(){handler.removeCallbacks(autoUpdate);super.onPause();}
  @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
  private String serverUrl(){
    return getPreferences(MODE_PRIVATE).getString("server_url","");
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
  private void ensureFresh(){runFullScan(false);}
  private void ensureNextSessionFresh(){
    if(serverConfigured()||open()||!secrets.configured()||busy||quoteBusy||discovery.scanning())return;
    long now=System.currentTimeMillis();
    java.time.LocalTime t=Instant.ofEpochMilli(now).atZone(MarketSchedule.IST).toLocalTime();
    // Prefer the finalized daily record, not the unfinished market-close bar.
    if(!t.isBefore(java.time.LocalTime.of(15,30)) &&
       t.isBefore(java.time.LocalTime.of(16,30)))return;
    if(discovery.lastScan()>0 && now-discovery.lastScan()<4*60*60000L)return;
    if(discovery.lastAttempt()>0 && now-discovery.lastAttempt()<20*60000L)return;
    runNextSessionScan(false);
  }
  private void runNextSessionScan(boolean force){
    if(serverConfigured()||busy||quoteBusy||discovery.scanning()||!secrets.configured())return;
    if(open()){runFullScan(force);return;}
    long now=System.currentTimeMillis();
    if(!force && discovery.lastScan()>0 && now-discovery.lastScan()<4*60*60000L)return;
    // Avoid accidental NSE-wide API request bursts from repeated manual taps.
    if(discovery.lastAttempt()>0 && now-discovery.lastAttempt()<20*60000L){
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
    runFullScan(true);
  }
  private void runFullScan(boolean force){
    if(busy||quoteBusy||!open()||!secrets.configured()||discovery.scanning())return;
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
    if(open()){
      long interval=MarketSchedule.preClose(now)?5*60000L:15*60000L;
      return previous==0?now:Math.max(now,previous+interval);
    }
    return previous==0?now:Math.max(now,previous+4*60*60000L);
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
      c.addView(text("Last scan attempt: "+time(discovery.lastAttempt()),12,MUTED,false));
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
      if(discovery.lastScan()>0){
        spaceIn(c,5);
        c.addView(text("Stocks in master: "+latest.optInt("universeCount",0)+
          " · Quotes received: "+latest.optInt("quotedCount",0)+
          " · History checked: "+latest.optInt("dailyHistoryVerified",0)+
          " · Research-qualified: "+latest.optInt("eligibleForResearch",0),
          11,MUTED,false));
        c.addView(text("Research watches shown: "+ledger.live().length()+
          " · only candidates meeting verified screening filters",11,MUTED,false));
      }
      spaceIn(c,7);
      c.addView(text("Broad NSE scan: every 15 min (09:15–14:29), every 5 min (14:30–15:29).",11,MUTED,false));
      c.addView(text("Top-3 quote check: every 30 sec while app is open. Background checks: ~15 min, Android-controlled.",11,MUTED,false));
      c.addView(text("Next eligible scan: "+time(nextScanTime())+
        (marketOpen?" · live":" · after-hours research, 4-hour auto interval"),11,BLUE,true));
    }
    spaceIn(c,11);
    android.widget.Button button=new android.widget.Button(this);
    button.setAllCaps(false);button.setText(serverConfigured()?"Refresh scanner feed":
      marketOpen?"Scan Now":"Scan Tomorrow's Top 3");
    button.setTextSize(13);button.setTextColor(Color.WHITE);
    button.setBackground(rounded(BLUE));button.setEnabled(!busy&&!discovery.scanning()&&!remoteBusy);
    button.setOnClickListener(v->manualScan());
    c.addView(button,new LinearLayout.LayoutParams(-1,dp(43)));
    if(!marketOpen && !serverConfigured()){
      spaceIn(c,6);
      c.addView(text("Outside NSE hours: Scan Now evaluates completed-session data. Live BUY confirmation remains suspended.",11,AMBER,false));
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
        secrets.configured()?"○ Groww saved · tap ⚙ to verify":"○ Groww not connected",
        11,authorized?GREEN:AMBER,true));
    top.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
    TextView settings=text("⚙",24,BLUE,true);
    settings.setContentDescription("Open streamlined settings");
    settings.setGravity(Gravity.CENTER);
    settings.setPadding(dp(14),dp(10),dp(14),dp(10));
    settings.setBackground(rounded(Color.WHITE));
    settings.setOnClickListener(v->startActivity(new Intent(this,MainActivity.class)));
    top.addView(settings);root.addView(top);
    space(20);
    LinearLayout tabs=new LinearLayout(this);tabs.setBackground(rounded(Color.WHITE));
    tabs.setPadding(dp(4),dp(4),dp(4),dp(4));
    for(String item:new String[]{"LIVE","CLOSED"}){
      String caption=item.equals("LIVE")?"Live recommendations":"Closed recommendations";
      TextView tab=text(caption,13,item.equals(section)?BLUE:MUTED,item.equals(section));
      tab.setGravity(Gravity.CENTER);tab.setPadding(dp(6),dp(13),dp(6),dp(13));
      tab.setBackground(rounded(item.equals(section)?Color.rgb(229,240,250):Color.WHITE));
      LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
      tabs.addView(tab,lp);
      tab.setOnClickListener(v->{section=item;render();
        if(item.equals("LIVE")){ensureFresh();refreshPrices();}
      });
    }
    root.addView(tabs);space(16);
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
  private void renderLive(){
    JSONObject scan=discovery.latest();
    JSONArray live=serverConfigured()?(remoteConnected?remoteLive:new JSONArray()):ledger.live();
    boolean nextSession="NEXT_SESSION_RESEARCH".equals(scan.optString("scanMode",""));
    root.addView(text(nextSession?"Tomorrow's Top 3 research":"Live recommendations",22,INK,true));
    space(4);
    root.addView(text(nextSession?
       "Latest completed NSE session: "+scan.optString("sourceSessionDate","unknown")+
       " · technical watchlist, not live BUY signals":
       "Forward-looking watches, carried forward while the setup remains valid.",
       12,MUTED,false));
    space(13);
    LinearLayout overview=card();
    overview.addView(text((nextSession?
      Math.min(3,scan.optJSONArray("top")==null?0:scan.optJSONArray("top").length())+" next-session candidates · "+
      live.length()+" ongoing watches":live.length()+" active research watches"),20,INK,true));
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
    if(!serverConfigured())summaryCard(scan,false);
    if(live.length()==0){
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
    root.removeAllViews();tabHeader();
    scanStatusCard();
    if("CLOSED".equals(section))renderClosed();
    else renderLive();
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
    if(request!=EXPORT||result!=RESULT_OK||data==null||data.getData()==null)return;
    Uri uri=data.getData();
    worker.execute(()->{
      String issue=null;
      try{
        // Legacy manually watched symbols are diagnostic history, not active Top 3.
        String manual=getPreferences(MODE_PRIVATE).getString("watchlist","TARIL,GAEL,ADVANCE");
        List<String> selected=new ArrayList<>();
        for(String entry:manual.split(",")){
          String symbol=entry.trim();
          if(symbol.matches("[A-Z0-9&-]{1,26}"))selected.add(symbol);
        }
        DiagnosticExporter.save(getApplicationContext(),uri,new WatchStore(this),selected,
          getPreferences(MODE_PRIVATE).getString("server_url",""),
          secrets.get("server_viewer_token"));
      }catch(Exception e){issue=e.getClass().getSimpleName();}
      String problem=issue;
      handler.post(()->Toast.makeText(this,problem==null?
        "Logs saved. You can upload the ZIP for analysis.":
        "Export failed: "+problem,Toast.LENGTH_LONG).show());
    });
  }
}

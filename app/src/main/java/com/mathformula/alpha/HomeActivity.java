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
  private static final int INK=Color.rgb(26,40,57),BLUE=Color.rgb(32,98,168),
      MUTED=Color.rgb(103,113,127),GREEN=Color.rgb(19,123,85),
      AMBER=Color.rgb(158,98,19),RED=Color.rgb(180,59,58);
  private static final int EXPORT=867;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private LocalDiscovery discovery;
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
      if(startedWhileOpen && !open && "LIVE".equals(section)) section="CLOSED";
      if(!startedWhileOpen && open && "CLOSED".equals(section)) section="LIVE";
      startedWhileOpen=open;
      if(open && "LIVE".equals(section)){
        ensureFresh();
        refreshPrices();
        evaluateAlerts();
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
    ResearchAlerts.channel(this);
    ResearchJob.schedule(this);
    if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
      requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},904);
    }
    startedWhileOpen=open();
    lastVisibleDate=todayIst();
    section=startedWhileOpen?"LIVE":"CLOSED";
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
      section=open()?"LIVE":"CLOSED";
      lastVisibleDate=todayIst();
      startedWhileOpen=open();
    }
    handler.removeCallbacks(autoUpdate);
    handler.postDelayed(autoUpdate,200);
  }
  @Override protected void onPause(){handler.removeCallbacks(autoUpdate);super.onPause();}
  @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
  private void ensureFresh(){
    if(busy||quoteBusy||!open()||!secrets.configured())return;
    long now=System.currentTimeMillis();
    long interval=MarketSchedule.preClose(now)?5*60000L:15*60000L;
    if(discovery.lastScan()>0 && now-discovery.lastScan()<interval)return;
    busy=true;scanProgress="Checking NSE instrument universe";render();
    worker.execute(()->{
      String note="";
      try{
        discovery.scan(groww,stage->handler.post(()->scanProgress=stage));
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
  private void tabHeader(){
    LinearLayout top=new LinearLayout(this);
    top.setGravity(Gravity.CENTER_VERTICAL);
    TextView label=text("MathFormula",26,INK,true);
    top.addView(label,new LinearLayout.LayoutParams(0,-2,1));
    TextView settings=text("⚙",24,MUTED,false);
    settings.setContentDescription("Connections and advanced settings");
    settings.setPadding(dp(11),dp(5),dp(8),dp(5));
    settings.setOnClickListener(v->startActivity(new Intent(this,MainActivity.class)));
    top.addView(settings);
    root.addView(top);
    root.addView(text("ALPHA-30 v1.0 · FIXED RESEARCH MODEL",11,BLUE,true));
    space(18);
    LinearLayout tabs=new LinearLayout(this);
    tabs.setOrientation(LinearLayout.HORIZONTAL);
    for(String item:new String[]{"LIVE","CLOSED"}){
      TextView view=text(item.equals("LIVE")?"Live":"Closed",15,
        item.equals(section)?BLUE:MUTED,item.equals(section));
      view.setGravity(Gravity.CENTER);
      view.setPadding(dp(14),dp(13),dp(14),dp(13));
      view.setBackground(rounded(item.equals(section)?Color.rgb(230,239,248):Color.WHITE));
      LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
      lp.rightMargin=dp(7);tabs.addView(view,lp);
      view.setOnClickListener(v->{
        section=item;render();
        if(item.equals("LIVE")){ensureFresh();refreshPrices();}
      });
    }
    root.addView(tabs);space(14);
  }
  private void summaryCard(JSONObject scan,boolean closed){
    LinearLayout c=card();
    long completed=scan.optLong("scannedAtMs",0);
    long elapsed=System.currentTimeMillis()-completed;
    long threshold=MarketSchedule.preClose(System.currentTimeMillis())?8*60000L:20*60000L;
    boolean recent=completed>0&&elapsed>=0&&elapsed<=threshold;
    String state=closed?"SESSION RECORD · NOT A LIVE QUOTE":
      !open()?"MARKET CLOSED":
      busy?"SCANNING NSE":!secrets.configured()?"GROWW NOT CONNECTED":
      recent?"LATEST RESEARCH SCAN AVAILABLE":"SCAN OVERDUE / NO CURRENT RESULTS";
    int color=closed?MUTED:recent?GREEN:AMBER;
    c.addView(text(state,12,color,true));spaceIn(c,6);
    c.addView(text(closed?
      "Latest session snapshot: "+time(completed):
      busy?(scanProgress.isEmpty()?"Finding opportunities…":scanProgress):
        "Last completed universe scan: "+time(completed),12,MUTED,false));
    if(completed>0){
      spaceIn(c,5);
      c.addView(text("NSE stocks: "+scan.optInt("universeCount")+" · Quotes received: "+
        scan.optInt("quotedCount")+" · Histories checked: "+
        scan.optInt("dailyHistoryVerified")+" · Eligible: "+
        scan.optInt("eligibleForResearch"),12,INK,false));
    }
    if(!closed){
    spaceIn(c,7);
    boolean alertsOn=ResearchAlerts.permission(this);
    c.addView(text(alertsOn?
      "Research alerts enabled · Android background checks are best-effort (~15 min)":
      "Notifications disabled · enable permission in Android app settings",
      12,alertsOn?GREEN:AMBER,false));
    long alertChecked=ResearchAlerts.lastCheck(this);
    c.addView(text("Last alert check: "+time(alertChecked)+
      " · "+ResearchAlerts.lastState(this),11,MUTED,false));
    spaceIn(c,6);
    c.addView(text("Groww usage: "+GrowwQuota.status()+". "+
      "Shared app cap 6/sec and 210/min (Groww publishes 10/sec and 300/min). "+
      "Other apps on your Groww account are not included.",11,MUTED,false));
    }
    if(!closed&&!discovery.error().isEmpty()){
      spaceIn(c,6);
      c.addView(text("Data issue: "+safeReason(new IllegalStateException(discovery.error())),
        12,AMBER,false));
    }
    if(closed){
      spaceIn(c,7);
      c.addView(text("These are the last prices actually recorded by MathFormula during the session, not verified NSE closing-auction prices.",11,AMBER,false));
    }
  }
  private void spaceIn(LinearLayout c,int amount){
    c.addView(new View(this),new LinearLayout.LayoutParams(1,dp(amount)));
  }
  private void stockCard(JSONObject j,int rank,boolean closed){
    LinearLayout c=card();
    LinearLayout title=new LinearLayout(this);title.setGravity(Gravity.CENTER_VERTICAL);
    title.setOrientation(LinearLayout.HORIZONTAL);
    String symbol=j.optString("symbol","UNKNOWN");
    title.addView(text(rank+". "+symbol,18,INK,true),new LinearLayout.LayoutParams(0,-2,1));
    title.addView(text(closed?"LAST OBSERVED":j.optString("stage","WATCHING"),
      11,closed?MUTED:BLUE,true));c.addView(title);spaceIn(c,9);
    double last=j.optDouble("price",Double.NaN),move=j.optDouble("dayChangePct",Double.NaN);
    c.addView(text(money(last)+"   "+percent(move),20,INK,true));
    spaceIn(c,10);
    c.addView(text("Expected breakout  "+money(j.optDouble("breakoutLevel",Double.NaN)),
      14,BLUE,true));
    c.addView(text("Distance to resistance  "+percent(j.optDouble("distancePct",Double.NaN)),
      12,MUTED,false));
    spaceIn(c,10);
    long trade=j.optLong("tradeMs",0),now=System.currentTimeMillis();
    boolean live=!closed&&open()&&trade>0&&now>=trade&&now-trade<=60_000L;
    c.addView(text(closed?"Recorded trade: "+time(trade):
      "Latest trade: "+hour(trade)+(live?" · RECENT":" · STALE / UNVERIFIED"),
      12,live?GREEN:AMBER,false));
    if(!closed)c.addView(text(
      "Technical watch only · waiting for completed candles, VWAP and full model",
      12,MUTED,false));
    else c.addView(text("Screening stage at the time: "+j.optString("stage","UNKNOWN"),
      12,MUTED,false));
    spaceIn(c,8);
    c.addView(text("NO SIGNAL · Frozen Alpha-30 verification incomplete",11,AMBER,true));
  }
  private void renderLive(){
    JSONObject scan=discovery.latest();
    summaryCard(scan,false);
    root.addView(text("Top Opportunities",20,INK,true));space(6);
    root.addView(text("Three most recent qualifying research setups · automatic refresh",12,MUTED,false));
    space(13);
    long at=scan.optLong("scannedAtMs",0),now=System.currentTimeMillis();
    long maximum=MarketSchedule.preClose(now)?8*60000L:20*60000L;
    boolean scanFresh=open()&&at>0&&now-at>=0&&now-at<=maximum;
    JSONArray top=scan.optJSONArray("top");
    if(!scanFresh||top==null||top.length()==0){
      LinearLayout c=card();
      String heading=busy?"Finding opportunities…":!open()?"Market closed":
        "No verified fresh opportunities";
      c.addView(text(heading,16,INK,true));spaceIn(c,7);
      c.addView(text(busy?"Research results appear after a completed NSE scan.":
        !open()?"View Closed for the last captured session.":
        !secrets.configured()?"Connect Groww in Settings.":
        "No fresh complete scan or no stocks passed the preliminary research filters. Previous picks are not recycled.",
        13,MUTED,false));
      return;
    }
    for(int i=0;i<Math.min(3,top.length());i++){
      JSONObject j=top.optJSONObject(i);if(j!=null)stockCard(j,i+1,false);
    }
    root.addView(text("Breakout is a prior 20-session resistance level, not a price prediction. A discovery shortlist is NOT a complete BUY recommendation.",11,MUTED,false));
  }
  private void renderClosed(){
    JSONObject closed=discovery.lastClosedSession(System.currentTimeMillis());
    if(closed.length()==0){
      LinearLayout c=card();c.addView(text("No completed-session record yet",17,INK,true));
      spaceIn(c,6);
      c.addView(text("The Closed section fills after MathFormula successfully captures an NSE scan during a trading session. It does not invent yesterday's closing prices.",13,MUTED,false));
    }else{
      String date=closed.optString("sessionDate","");
      root.addView(text("Session · "+date,20,INK,true));space(10);
      summaryCard(closed,true);
      JSONArray picks=closed.optJSONArray("top");
      root.addView(text("Last Tracked Opportunities",17,INK,true));space(10);
      if(picks==null||picks.length()==0){
        LinearLayout c=card();c.addView(text("No candidates qualified in this captured session",13,MUTED,false));
      }else for(int i=0;i<Math.min(3,picks.length());i++){
        JSONObject j=picks.optJSONObject(i);if(j!=null)stockCard(j,i+1,true);
      }
    }
    space(5);
    JSONArray history=discovery.history();
    root.addView(text("Previously Tracked",17,INK,true));space(7);
    if(history.length()==0)root.addView(text("Stocks replaced by later scans will appear here.",12,MUTED,false));
    for(int i=history.length()-1;i>=Math.max(0,history.length()-5);i--){
      JSONObject j=history.optJSONObject(i);if(j==null)continue;
      LinearLayout c=card();c.addView(text(j.optString("symbol","")+"  ·  ARCHIVED",14,INK,true));
      spaceIn(c,4);c.addView(text("Recorded "+money(j.optDouble("price",Double.NaN))+
        " · Breakout "+money(j.optDouble("breakoutLevel",Double.NaN)),12,MUTED,false));
      c.addView(text("Removed "+time(j.optLong("removedAtMs",0)),11,MUTED,false));
    }
    space(12);
    TextView export=text("Export diagnostic logs  ↗",13,BLUE,true);
    export.setGravity(Gravity.CENTER);
    export.setPadding(dp(12),dp(15),dp(12),dp(15));
    export.setBackground(rounded(Color.WHITE));
    export.setContentDescription("Save MathFormula diagnostic ZIP to Android Files");
    export.setOnClickListener(v->exportLogs());
    root.addView(export);
    space(10);
    root.addView(text("Stored snapshots can have gaps when the phone was offline. Download logs for a point-in-time audit.",11,MUTED,false));
  }
  private void render(){
    if(root==null)return;
    root.removeAllViews();tabHeader();
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

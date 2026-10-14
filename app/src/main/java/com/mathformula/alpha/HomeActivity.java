package com.mathformula.alpha;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.view.View;
import android.widget.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.*;

/** Home-first UX: open -> see newest actual qualifying research candidates. */
public final class HomeActivity extends Activity {
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private static final int INK=Color.rgb(26,40,57),BLUE=Color.rgb(28,103,176);
  private static final int MUTED=Color.rgb(99,111,126),AMBER=Color.rgb(161,99,19);
  private static final int GREEN=Color.rgb(23,127,85),RED=Color.rgb(183,64,67);
  private static final int EXPORT=867;
  private LocalDiscovery discovery;
  private SecureGrowwStore secrets;
  private GrowwReadOnlyClient groww;
  private LinearLayout root;
  private String tab="OPPORTUNITIES",progress="";
  private boolean busy=false;
  private final Runnable refresh=new Runnable(){
    @Override public void run(){
      if("OPPORTUNITIES".equals(tab))ensureFresh();
      else render();
      handler.postDelayed(this,60000L);
    }
  };
  private int px(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
  private TextView text(String s,int size,int color,boolean bold){
    TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
    if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setLineSpacing(px(2),1.08f);
    return t;
  }
  private GradientDrawable bg(int color){
    GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(px(14));
    return d;
  }
  private void gap(LinearLayout b,int x){b.addView(new View(this),new LinearLayout.LayoutParams(1,px(x)));}
  private LinearLayout card(){
    LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);
    c.setPadding(px(16),px(16),px(16),px(16));c.setBackground(bg(Color.WHITE));
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=px(12);
    root.addView(c,lp);return c;
  }
  private String clock(long ms){
    if(ms<=0)return "Not recorded";
    return DateTimeFormatter.ofPattern("dd MMM · HH:mm:ss 'IST'",Locale.US)
      .withZone(ZoneId.of("Asia/Kolkata")).format(Instant.ofEpochMilli(ms));
  }
  private String p(double v){return Double.isFinite(v)?String.format(Locale.US,"₹%,.2f",v):"N/A";}
  private String pct(double v){return Double.isFinite(v)?String.format(Locale.US,"%+.2f%%",v):"N/A";}
  @Override public void onCreate(Bundle b){
    super.onCreate(b);
    secrets=new SecureGrowwStore(this);
    groww=new GrowwReadOnlyClient(secrets);
    discovery=new LocalDiscovery(this);
    getWindow().setStatusBarColor(Color.rgb(247,249,252));
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Color.rgb(247,249,252));
    scroll.setFillViewport(true);
    root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(px(19),px(22),px(19),px(30));scroll.addView(root);setContentView(scroll);
    render();
  }
  @Override protected void onResume(){
    super.onResume();handler.removeCallbacks(refresh);
    handler.postDelayed(refresh,200);
  }
  @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
  @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
  private void ensureFresh(){
    if(busy){render();return;}
    if(!secrets.configured()){render();return;}
    long now=System.currentTimeMillis();
    if(!MarketSchedule.weekdayTradingTime(now)){render();return;}
    long cadence=MarketSchedule.preClose(now)?5*60_000L:15*60_000L;
    if(discovery.lastScan()>0 && now-discovery.lastScan()<cadence){render();return;}
    busy=true;progress="Preparing NSE universe";render();
    worker.execute(()->{
      try{
        discovery.scan(groww,step->handler.post(()->{
          progress=step;
          // Do not constantly rebuild every row during a multi-minute scan.
        }));
        progress="";
      }catch(Exception e){
        progress="Scan failed: "+e.getClass().getSimpleName()+" · "+shortError(e.getMessage());
      }finally{
        handler.post(()->{busy=false;render();});
      }
    });
  }
  private static String shortError(String e){
    if(e==null)return "data unavailable";
    // Never include network response bodies, credentials, URL or raw headers.
    if(e.contains("rate limit"))return "Groww rate limit; retry automatically";
    if(e.contains("60 completed"))return "historical candles incomplete";
    if(e.contains("authentication")||e.contains("authorization"))return "Groww reauthentication required";
    if(e.contains("price scan"))return "insufficient returned market coverage";
    if(e.contains("instrument"))return "instrument list unavailable";
    return "check connectivity and diagnostics";
  }
  private void menu(){
    LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);
    top.setGravity(android.view.Gravity.CENTER_VERTICAL);
    LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);
    names.addView(text("MathFormula",28,INK,true));
    names.addView(text("ALPHA-30 · RESEARCH",11,BLUE,true));
    top.addView(names,new LinearLayout.LayoutParams(0,-2,1));
    TextView settings=text("⚙",28,MUTED,false);settings.setPadding(px(12),px(5),px(9),px(5));
    settings.setContentDescription("Groww and advanced settings");
    settings.setOnClickListener(v->startActivity(new Intent(this,MainActivity.class)));
    top.addView(settings);root.addView(top);gap(root,14);
    LinearLayout tabs=new LinearLayout(this);tabs.setOrientation(LinearLayout.HORIZONTAL);
    for(String item:new String[]{"OPPORTUNITIES","WATCHLIST","HISTORY"}){
      String title="OPPORTUNITIES".equals(item)?"Opportunities":
        "WATCHLIST".equals(item)?"Watchlist":"History";
      TextView button=text(title,12,item.equals(tab)?BLUE:MUTED,item.equals(tab));
      button.setGravity(android.view.Gravity.CENTER);button.setPadding(px(4),px(10),px(4),px(10));
      button.setBackground(bg(item.equals(tab)?Color.rgb(229,239,249):Color.WHITE));
      LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
      lp.rightMargin=px(4);tabs.addView(button,lp);
      button.setOnClickListener(v->{tab=item;render();if("OPPORTUNITIES".equals(item))ensureFresh();});
    }
    root.addView(tabs);gap(root,16);
  }
  private void health(JSONObject record){
    long now=System.currentTimeMillis(),at=record.optLong("scannedAtMs",0);
    boolean market=MarketSchedule.weekdayTradingTime(now);
    long stale=MarketSchedule.preClose(now)?8*60_000L:20*60_000L;
    boolean ready=at>0 && now-at<=stale && market;
    LinearLayout status=card();
    int color=busy?BLUE:!secrets.configured()?AMBER:ready?GREEN:AMBER;
    String label=busy?"SCANNING NSE STOCKS":!secrets.configured()?"GROWW NOT CONNECTED":
      !market?"MARKET CLOSED · LAST SESSION":ready?"LATEST SCAN AVAILABLE":"SCAN OVERDUE / NO FRESH DATA";
    status.addView(text(label,12,color,true));gap(status,7);
    status.addView(text(busy?(progress.isEmpty()?"Processing Groww market data":progress):
      "Last completed NSE scan: "+clock(at),12,MUTED,false));
    if(at>0)status.addView(text("Covered "+record.optInt("quotedCount")+" of "+
      record.optInt("universeCount")+" NSE EQ prices · historical checks "+
      record.optInt("dailyHistoryVerified")+" · qualified "+
      record.optInt("eligibleForResearch"),12,MUTED,false));
    if(!discovery.error().isEmpty())status.addView(text("Last error: "+shortError(discovery.error()),12,AMBER,false));
    if(!market)status.addView(text("Prices are historical. A new scan starts automatically when you open the app during NSE market hours.",12,MUTED,false));
    if(!secrets.configured())status.addView(text("Tap ⚙ to configure Groww. Your existing device credentials are retained when available.",12,MUTED,false));
  }
  private void render(){
    if(root==null)return;
    root.removeAllViews();menu();
    JSONObject scan=discovery.latest();
    health(scan);
    if("HISTORY".equals(tab)){showHistory();return;}
    if("WATCHLIST".equals(tab)){showWatchlist();return;}
    root.addView(text("Today's Top Opportunities",19,INK,true));gap(root,5);
    root.addView(text("Automatically ranked research setups — not BUY signals",12,MUTED,false));gap(root,13);
    long now=System.currentTimeMillis();
    long at=scan.optLong("scannedAtMs",0);
    boolean fresh=MarketSchedule.weekdayTradingTime(now)&&at>0&&
        now-at<=(MarketSchedule.preClose(now)?8*60_000L:20*60_000L);
    JSONArray top=scan.optJSONArray("top");
    if(!fresh||top==null||top.length()==0){
      LinearLayout c=card();
      c.addView(text(busy?"Finding fresh NSE candidates…":"No verified fresh candidates",17,INK,true));
      gap(c,8);
      c.addView(text(busy?"Screening in progress. Only verified data will appear.":
        !secrets.configured()?"Groww authentication is not yet available on this installation.":
        !MarketSchedule.weekdayTradingTime(now)?"Market is closed; prior selections are in History.":
        "No recent complete scan or no stocks met the research filters. Old picks are not recycled.",13,MUTED,false));
      return;
    }
    for(int i=0;i<Math.min(3,top.length());i++){
      JSONObject j=top.optJSONObject(i);if(j!=null)stock(j,i+1);
    }
    gap(root,6);
    root.addView(text("Expected breakout is the previous 20-session high. This is a technical discovery shortlist; the complete Alpha-30 BUY gates still require independent financial, news, macro and execution data.",11,MUTED,false));
  }
  private void stock(JSONObject j,int rank){
    LinearLayout c=card();
    LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
    TextView symbol=text(rank+"  "+j.optString("symbol","UNKNOWN"),18,INK,true);
    row.addView(symbol,new LinearLayout.LayoutParams(0,-2,1));
    TextView stage=text(j.optString("stage","WATCHING"),11,BLUE,true);
    row.addView(stage);c.addView(row);gap(c,10);
    c.addView(text(p(j.optDouble("price",Double.NaN))+"   "+pct(j.optDouble("dayChangePct",Double.NaN)),
       19,INK,true));gap(c,7);
    double breakout=j.optDouble("breakoutLevel",Double.NaN);
    c.addView(text("Breakout level  "+p(breakout),13,BLUE,true));
    c.addView(text("Distance from breakout  "+pct(j.optDouble("distancePct",Double.NaN)),12,MUTED,false));
    gap(c,7);
    c.addView(text("Next: completed 5m/15m confirmation, volume/VWAP and remaining model gates",12,MUTED,false));
    gap(c,7);
    c.addView(text("Quote: "+clock(j.optLong("tradeMs",0))+
       " · Historical resistance as of "+j.optString("priorDataAsOf","unavailable"),11,MUTED,false));
    c.addView(text("Research only · NO SIGNAL · 60+ completed sessions checked",11,AMBER,true));
  }
  private void showWatchlist(){
    root.addView(text("Watchlist",19,INK,true));gap(root,7);
    root.addView(text("Automatically selected candidates. They change after each successfully completed discovery scan.",12,MUTED,false));
    gap(root,10);
    JSONArray j=discovery.latest().optJSONArray("top");
    if(j==null||j.length()==0){
      LinearLayout c=card();c.addView(text("No stocks currently selected",16,INK,true));
      c.addView(text("MathFormula does not fall back to the original three stocks.",12,MUTED,false));return;
    }
    for(int i=0;i<j.length();i++)stock(j.optJSONObject(i),i+1);
  }
  private void showHistory(){
    root.addView(text("Previously Tracked",19,INK,true));gap(root,7);
    root.addView(text("Stocks removed from the automatic top three remain here for review.",12,MUTED,false));gap(root,12);
    JSONArray arr=discovery.history();
    if(arr.length()==0){
      LinearLayout c=card();
      c.addView(text("No archived selections yet",15,INK,true));
      c.addView(text("History will accumulate as completed new scans replace earlier picks.",12,MUTED,false));
    }
    for(int i=arr.length()-1;i>=Math.max(0,arr.length()-20);i--){
      JSONObject j=arr.optJSONObject(i);if(j==null)continue;
      LinearLayout c=card();
      c.addView(text(j.optString("symbol","UNKNOWN")+" · ARCHIVED",14,INK,true));
      c.addView(text("Last price "+p(j.optDouble("price",Double.NaN))+
        " · Breakout "+p(j.optDouble("breakoutLevel",Double.NaN)),12,MUTED,false));
      c.addView(text("Archived "+clock(j.optLong("removedAtMs",0)),11,MUTED,false));
    }
    gap(root,4);
    TextView export=text("Export diagnostic logs (ZIP)",13,BLUE,true);
    export.setPadding(px(12),px(14),px(12),px(14));export.setGravity(android.view.Gravity.CENTER);
    export.setBackground(bg(Color.WHITE));export.setOnClickListener(v->export());
    root.addView(export);
    gap(root,10);
    root.addView(text("Historical scans and exports are stored on this device; a separate hosted NSE scanner is not yet deployed.",12,MUTED,false));
  }
  private void export(){
    Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.setType("application/zip");intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.putExtra(Intent.EXTRA_TITLE,"MathFormula_Audit_"+System.currentTimeMillis()+".zip");
    startActivityForResult(intent,EXPORT);
  }
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(request!=EXPORT||result!=RESULT_OK||data==null||data.getData()==null)return;
    Uri uri=data.getData();
    worker.execute(()->{
      String err=null;
      try{
        String manual=getPreferences(MODE_PRIVATE).getString("watchlist","TARIL,GAEL,ADVANCE");
        List<String> stocks=new ArrayList<>();
        for(String item:manual.split(",")){String s=item.trim();if(s.matches("[A-Z0-9&-]{1,26}"))stocks.add(s);}
        DiagnosticExporter.save(getApplicationContext(),uri,new WatchStore(this),stocks,
          getPreferences(MODE_PRIVATE).getString("server_url",""),
          secrets.get("server_viewer_token"));
      }catch(Exception ex){err=ex.getClass().getSimpleName();}
      final String error=err;
      handler.post(()->Toast.makeText(this,error==null?"ZIP saved. You can upload it here.":
        "Export unavailable: "+error,Toast.LENGTH_LONG).show());
    });
  }
}

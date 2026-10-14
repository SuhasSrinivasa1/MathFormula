package com.mathformula.alpha;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Build;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import org.json.JSONObject;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Direct Groww read-only market monitor; no buy/sell APIs or buttons. */
public final class MainActivity extends Activity {
  private static final int INK=Color.rgb(24,37,55),BLUE=Color.rgb(29,99,169);
  private static final int MUTED=Color.rgb(108,118,132),GREEN=Color.rgb(23,126,90);
  private static final int RED=Color.rgb(183,56,59),AMBER=Color.rgb(164,104,12);
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private SecureGrowwStore vault;
  private GrowwReadOnlyClient client;
  private WatchStore watchStore;
  private final BroadcastReceiver scanReceiver=new BroadcastReceiver(){
    @Override public void onReceive(Context c,Intent intent){render();}
  };
  private LinearLayout page;
  private boolean loading=false;
  private String activeTab="LIVE";
  private long lastAttempt=0;
  private String status="OFFLINE · Enter Groww TOTP credentials";
  private final List<GrowwReadOnlyClient.Market> quotes=new ArrayList<>();
  private final List<String> errors=new ArrayList<>();
  private final Runnable fiveMinute=new Runnable(){
    @Override public void run(){
      if(vault.configured() && !watchStore.enabled())fetch(); else render();
      long now=System.currentTimeMillis();
      handler.postDelayed(this,60000L-(now%60000L)+3000L);
    }
  };
  private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
  private GradientDrawable background(int color,int radius){GradientDrawable b=new GradientDrawable();b.setColor(color);b.setCornerRadius(dp(radius));return b;}
  private TextView text(String value,int size,int color,boolean bold){
    TextView t=new TextView(this);t.setText(value);t.setTextColor(color);t.setTextSize(size);
    if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setLineSpacing(dp(2),1.06f);return t;
  }
  private void gap(LinearLayout p,int v){p.addView(new View(this),new LinearLayout.LayoutParams(1,dp(v)));}
  private LinearLayout card(){
    LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);
    c.setPadding(dp(16),dp(15),dp(16),dp(15));c.setBackground(background(Color.WHITE,15));
    LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(-1,-2);l.bottomMargin=dp(12);page.addView(c,l);return c;
  }
  private Button btn(String caption,Runnable action){
    Button b=new Button(this);b.setAllCaps(false);b.setText(caption);
    b.setTextSize(13);b.setTextColor(Color.WHITE);b.setBackground(background(BLUE,11));
    b.setOnClickListener(v->action.run());return b;
  }
  private void addButtonRow(String a,Runnable ar,String b,Runnable br){
    LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(0,dp(48),1);l.rightMargin=dp(8);
    row.addView(btn(a,ar),l);
    row.addView(btn(b,br),new LinearLayout.LayoutParams(0,dp(48),1));
    page.addView(row);gap(page,15);
  }
  @Override public void onCreate(Bundle b){
    super.onCreate(b);
    vault=new SecureGrowwStore(this);client=new GrowwReadOnlyClient(vault);
    watchStore=new WatchStore(this);
    if(vault.configured())status="Groww credentials saved securely. Refresh to retrieve prices.";
    getWindow().setStatusBarColor(Color.rgb(248,250,252));
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
    scroll.setBackgroundColor(Color.rgb(247,249,252));
    page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
    page.setPadding(dp(19),dp(24),dp(19),dp(30));scroll.addView(page);setContentView(scroll);render();
  }
  @Override protected void onResume(){
    super.onResume();IntentFilter filter=new IntentFilter(WatchStore.ACTION);
    if(Build.VERSION.SDK_INT>=33)registerReceiver(scanReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(scanReceiver,filter);
    handler.postDelayed(fiveMinute,500);
  }
  @Override protected void onPause(){handler.removeCallbacks(fiveMinute);
    try{unregisterReceiver(scanReceiver);}catch(Exception ignored){}
    super.onPause();}
  @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
  private String format(double d){
    return Double.isFinite(d)?String.format(Locale.US,"₹%,.2f",d):"Unavailable";
  }
  private String one(double d){return Double.isFinite(d)?String.format(Locale.US,"%.2f",d):"N/A";}
  private String clock(long timestamp){
    if(timestamp<=0)return "Exchange time unavailable";
    return DateTimeFormatter.ofPattern("dd MMM HH:mm:ss 'IST'",Locale.US)
      .withZone(ZoneId.of("Asia/Kolkata")).format(Instant.ofEpochMilli(timestamp));
  }
  private List<String> symbols(){
    String raw=getPreferences(MODE_PRIVATE).getString("watchlist","TARIL,GAEL,ADVANCE");
    List<String> result=new ArrayList<>();
    for(String s:raw.split(",")){
      String v=s.trim().toUpperCase(Locale.US);
      if(v.matches("[A-Z0-9&-]{1,26}")&&!result.contains(v))result.add(v);
      if(result.size()==20)break;
    }
    return result;
  }
  private void render(){
    page.removeAllViews();
    page.addView(text("MathFormula",30,INK,true));
    page.addView(text("GROWW LIVE · ALPHA-30 RESEARCH",11,BLUE,true));gap(page,16);
    LinearLayout top=card();
    top.addView(text(vault.configured()?"Groww TOTP configured":"Connect your Groww account",17,INK,true));
    gap(top,7);top.addView(text(status,12,vault.configured()?MUTED:AMBER,false));gap(top,5);
    top.addView(text("READ ONLY · no orders · no static trading IP",12,GREEN,true));
    gap(top,5);top.addView(text("Alpha-30 v1.0 fixed weights · 90% accuracy not proven",11,MUTED,false));
    addButtonRow(loading?"Fetching…":"Refresh Groww",this::fetch,"Groww Settings",this::settings);
    addButtonRow(watchStore.enabled()?"Renew close coverage":"Start background",this::startMonitoring,
      "Stop background",this::stopMonitoring);
    addButtonRow("Edit Watchlist",this::editWatchlist,"Earnings dates",this::editEvents);
    addButtonRow("Live",()->selectTab("LIVE"),"Pre-close",()->selectTab("PRE_CLOSE"));
    addButtonRow("Runners & misses",()->selectTab("RUNNERS"),"Audit history",this::showAudit);
    page.addView(btn("View fixed 30 Model Factors",this::showFactors),new LinearLayout.LayoutParams(-1,dp(44)));
    gap(page,10);
    LinearLayout monitor=card();
    long sincePoll=System.currentTimeMillis()-watchStore.lastPoll();
    boolean healthy=watchStore.enabled()&&watchStore.lastPoll()>0&&sincePoll<420000L;
    monitor.addView(text("Background status: "+(!watchStore.enabled()?"STOPPED":!healthy?"COVERAGE GAP":watchStore.lastPollOk()?"MONITORING":"DATA ERROR"),14,INK,true));gap(monitor,5);
    monitor.addView(text("Last scan: "+clock(watchStore.lastPoll())+(watchStore.lastPoll()>0?" · "+(sincePoll/1000)+"s ago":" · not scanned"),12,MUTED,false));
    if(!watchStore.lastPollOk()&&!watchStore.lastError().isEmpty()){
      gap(monitor,5);monitor.addView(text("Last issue: "+watchStore.lastError(),12,AMBER,false));
    }
    gap(monitor,5);
    monitor.addView(text("Phone monitoring is best effort. For final-hour coverage, reopen MathFormula around 2:30 PM and tap Renew close coverage; Android can still interrupt the service.",12,AMBER,false));
    if(MarketSchedule.weekdayTradingTime(System.currentTimeMillis())){
      long until=MarketSchedule.minutesUntilClose(System.currentTimeMillis());
      monitor.addView(text("NSE closes in approximately "+until+" minutes · last-hour opportunity checks at 14:45, 15:00, 15:15, 15:25 IST.",11,MUTED,false));
    }
    if("PRE_CLOSE".equals(activeTab)){
      page.addView(text("PRE-CLOSE OPPORTUNITIES",15,BLUE,true));gap(page,7);
      page.addView(text("Only your monitored symbols. These are event and technical WATCH candidates, not next-day predictions or BUY signals.",12,MUTED,false));gap(page,8);
      LinearLayout summary=card();
      summary.addView(text("Latest pre-close checkpoint",14,INK,true));gap(summary,5);
      summary.addView(text(clock(watchStore.lastPreClose()),12,MUTED,false));
      summary.addView(text(watchStore.precloseSummary(),12,MUTED,false));
      for(String symbol:symbols()){
        JSONObject snap=watchStore.get(symbol);
        String stage=snap.optString("stage","WAITING");
        String event=watchStore.earningsDate(symbol);
        if(stage.equals("NEAR BREAKOUT")||stage.equals("BREAKOUT CROSSED")||
           stage.equals("CONFIRMING")||stage.equals("FULL MODEL PENDING")||
           MarketSchedule.dueSoon(event,System.currentTimeMillis())){
          drawSaved(symbol,snap);
          if(MarketSchedule.dueSoon(event,System.currentTimeMillis())){
            LinearLayout tag=card();
            tag.addView(text(symbol+" · Earnings date "+event,13,AMBER,true));
            tag.addView(text("MANUALLY ENTERED · not a verified NSE disclosure · gap-down risk",12,MUTED,false));
          }
        }
      }
      LinearLayout gapCard=card();
      gapCard.addView(text("Missing: market-wide discovery and published results",14,INK,true));
      gapCard.addView(text("For a TARIL-style event we need a server-side NSE-wide screen and a timestamped filings feed. This phone-only tab cannot anticipate unpublished results.",12,AMBER,false));
    }else if("RUNNERS".equals(activeTab)){
      renderRunners();
    }else {
      page.addView(text("NSE LIVE WATCHLIST",12,BLUE,true));gap(page,5);
      page.addView(text("Watching → Near breakout → Crossed → Confirming → Full-model pending. A breakout is not a BUY.",12,MUTED,false));gap(page,10);
      // All chosen candidates remain visible even after a failed breakout.
      for(String symbol:symbols()){
        JSONObject snapshot=watchStore.get(symbol);
        if(snapshot.has("price"))drawSaved(symbol,snapshot);
        else{
          LinearLayout c=card();
          c.addView(text(symbol+" · WAITING FOR DATA",16,INK,true));
          c.addView(text("Expected breakout: prior 20 completed session highs · awaiting Groww feed",12,MUTED,false));
          c.addView(text("NO SIGNAL",11,AMBER,true));
        }
      }
    }
    for(String error:errors){
      LinearLayout c=card();c.addView(text(error,12,RED,false));
    }
    LinearLayout help=card();
    help.addView(text("Why not automatically BUY?",16,INK,true));gap(help,9);
    help.addView(text("Groww provides price, volume, quotes and candles. It does not by itself supply all 30 required financial, catalyst and macro inputs, nor a validated target and net risk/reward. Missing evidence forces NO SIGNAL.",12,MUTED,false));
    gap(help,10);
    help.addView(text("Enable the visible background-monitoring notification to continue checks while the app is closed. Android may stop the session; the UI reports coverage gaps. Full-universe 24/7 monitoring requires a server.",12,AMBER,true));
    gap(page,4);
    page.addView(text("Credentials encrypted with Android Keystore. No access tokens or TOTP codes are logged, exported, or committed. Only authorized read-only endpoints are called.",11,MUTED,false));
  }
  private void drawSaved(String symbol,JSONObject j){
    LinearLayout c=card();
    long exchangeMs=j.optLong("tradeMs",0),polled=j.optLong("polledAt",0);
    long age=System.currentTimeMillis()-exchangeMs;
    boolean fresh=exchangeMs>0&&age>=0&&age<=10000L;
    long pollAge=System.currentTimeMillis()-polled;
    boolean scanCurrent=polled>0&&pollAge>=0&&pollAge<=360000L;
    String recordedStage=j.optString("stage","WATCHING");
    String stage=scanCurrent?recordedStage:"COVERAGE GAP";
    int stageColor=stage.equals("FULL MODEL PENDING")?GREEN:
      stage.equals("NEAR BREAKOUT")||stage.equals("BREAKOUT CROSSED")||stage.equals("CONFIRMING")?BLUE:AMBER;
    c.addView(text(symbol+"  ·  "+stage,17,stageColor,true));gap(c,6);
    double price=j.optDouble("price",Double.NaN),reference=j.optDouble("reference",Double.NaN);
    c.addView(text(format(price)+(fresh?"  ·  fresh trade":"  ·  last known"),22,INK,true));
    double change=j.optDouble("changePct",Double.NaN);
    if(Double.isFinite(change))c.addView(text(String.format(Locale.US,"%+.2f%% today",change),
      13,change>=0?GREEN:RED,true));
    gap(c,8);
    c.addView(text("EXPECTED BREAKOUT: "+format(reference),15,BLUE,true));
    String asOf=j.optString("referenceAsOf","");
    c.addView(text((asOf.isEmpty()?"Breakout reference not verified":
      "Prior 20-session high · as of "+asOf),11,MUTED,false));
    if(Double.isFinite(price)&&price>0&&Double.isFinite(reference)){
      double diff=reference-price,pct=100*diff/price;
      c.addView(text(diff>0?
        String.format(Locale.US,"Distance to breakout: ₹%.2f (%.2f%%)",diff,pct):
        String.format(Locale.US,"Above breakout: ₹%.2f (%.2f%%)",-diff,-pct),
        13,diff<=0?GREEN:INK,true));
    }
    gap(c,9);
    c.addView(text("NEXT CONDITION",11,BLUE,true));
    c.addView(text(j.optString("next","Awaiting Groww scan"),13,INK,false));
    gap(c,9);
    c.addView(text("5m two-close: "+(j.optBoolean("two5m")?"PASS":"PENDING")+
      "   |   15m breakout close: "+(j.optBoolean("fifteenConfirmed")?"PASS":"PENDING"),12,MUTED,false));
    c.addView(text("Groww average traded price: "+format(j.optDouble("vwap",Double.NaN))+
      " · Volume: "+(j.isNull("volume")?"N/A":String.format(Locale.US,"%,.0f",j.optDouble("volume"))),12,MUTED,false));
    gap(c,9);
    c.addView(text("Exchange last trade: "+clock(exchangeMs)+
      (exchangeMs>0?" · "+(age/1000)+"s old":""),11,fresh?GREEN:AMBER,false));
    c.addView(text("App fetched: "+clock(polled)+" · Source: Groww Market Quote + Historical Candles",11,MUTED,false));
    String event=watchStore.earningsDate(symbol);
    if(!event.isEmpty()){
      gap(c,6);
      c.addView(text("Earnings reminder: "+event+" · manually entered, unverified",12,AMBER,true));
    }
    if(!fresh)c.addView(text("Last observed exchange trade; NOT a confirmed live price.",12,AMBER,true));
    if(!scanCurrent)c.addView(text("Last successful stock scan is over 6 minutes old. Reconnect monitoring.",12,RED,true));
    gap(c,7);
    c.addView(text("ALPHA-30: NO SIGNAL · fundamentals, macro, verified event data, RVOL and net R:R missing",12,AMBER,true));
    double first=j.optDouble("firstObservedPrice",Double.NaN);
    if(Double.isFinite(first)&&first>0){
      gap(c,6);
      c.addView(text("Since first observed on this device at "+clock(j.optLong("firstObservedMs",0))+
        ": "+one(j.optDouble("runnerPct",Double.NaN))+"% | peak observed "+
        one(j.optDouble("peakRunnerPct",Double.NaN))+"%",12,MUTED,false));
    }
  }
  private void startMonitoring(){
    if(!vault.configured()){status="Configure Groww credentials first";render();return;}
    if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},101);
    try{
      Intent intent=new Intent(this,ScannerService.class).setAction(ScannerService.START);
      if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
      status="Requested Android background monitoring; check scan timestamp for verification";
      render();
    }catch(RuntimeException e){status="Android blocked background start; retry while app is visible";render();}
  }
  private void stopMonitoring(){
    Intent intent=new Intent(this,ScannerService.class).setAction(ScannerService.STOP);
    startService(intent);watchStore.setEnabled(false);
    status="Stop requested; monitoring session will close";render();
  }
  private void showAudit(){
    new AlertDialog.Builder(this).setTitle("Alpha-30 · Local Audit")
      .setMessage(watchStore.auditText()).setPositiveButton("Close",null).show();
  }
  private void selectTab(String tab){activeTab=tab;render();}
  private void renderRunners(){
    page.addView(text("RUNNERS & LATE DISCOVERIES",15,BLUE,true));gap(page,7);
    page.addView(text("Observed changes only since MathFormula first received each quote on THIS device. Not an 80% prediction, backtest, or whole-NSE missed-winner analysis.",12,MUTED,false));gap(page,10);
    List<String> ranked=symbols();
    ranked.sort((a,b)->Double.compare(
      watchStore.get(b).optDouble("peakRunnerPct",-9999),
      watchStore.get(a).optDouble("peakRunnerPct",-9999)));
    for(String symbol:ranked){
      JSONObject j=watchStore.get(symbol);
      LinearLayout c=card();
      c.addView(text(symbol+(j.optBoolean("firstSeenAfterTenPct")?" · LATE FIRST OBSERVATION":""),16,INK,true));gap(c,7);
      double first=j.optDouble("firstObservedPrice",Double.NaN);
      if(Double.isFinite(first)&&first>0){
        c.addView(text("First observed: "+format(first)+" · "+clock(j.optLong("firstObservedMs",0)),12,MUTED,false));
        c.addView(text("Observed peak: "+one(j.optDouble("peakRunnerPct",Double.NaN))+
          "%  · Last observed: "+one(j.optDouble("runnerPct",Double.NaN))+"%",13,BLUE,true));
        c.addView(text("Drawdown from observed peak: "+one(j.optDouble("drawdownFromObservedPeakPct",Double.NaN))+"%",12,MUTED,false));
      }else c.addView(text("No fresh quote history yet",12,AMBER,false));
      c.addView(text("Audit of ALL missed NSE movers requires the hosted full-universe scanner.",11,AMBER,false));
    }
  }
  private void editEvents(){
    final List<String> list=symbols();
    LinearLayout inputBox=new LinearLayout(this);inputBox.setOrientation(LinearLayout.VERTICAL);
    inputBox.setPadding(dp(16),dp(12),dp(16),dp(6));
    final android.widget.Spinner spinner=new android.widget.Spinner(this);
    android.widget.ArrayAdapter<String> adapter=new android.widget.ArrayAdapter<>(this,
      android.R.layout.simple_spinner_dropdown_item,list);
    spinner.setAdapter(adapter);
    EditText field=new EditText(this);field.setHint("YYYY-MM-DD (or blank to clear)");
    field.setInputType(InputType.TYPE_CLASS_DATETIME|InputType.TYPE_DATETIME_VARIATION_DATE);
    inputBox.addView(text("Manual calendar reminder. Not a verified corporate disclosure.",12,AMBER,false));
    inputBox.addView(spinner);inputBox.addView(field);
    spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
      @Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int pos,long id){
        field.setText(watchStore.earningsDate(list.get(pos)));
      }
    });
    new AlertDialog.Builder(this).setTitle("Earnings event reminder")
      .setView(inputBox).setPositiveButton("Save",(d,w)->{
        String value=field.getText().toString().trim(), symbol=list.get(spinner.getSelectedItemPosition());
        try{watchStore.setEarningsDate(symbol,value);status="Manual date saved for "+symbol;render();}
        catch(Exception e){status="Use valid YYYY-MM-DD date";render();}
      }).setNegativeButton("Cancel",null).show();
  }
  private void drawQuote(GrowwReadOnlyClient.Market m){
    LinearLayout c=card();
    c.addView(text(m.symbol+"      NO SIGNAL",17,INK,true));gap(c,7);
    c.addView(text(format(m.price),24,BLUE,true));
    if(Double.isFinite(m.change))c.addView(text(
      String.format(Locale.US,"%+.2f%% today",m.change),13,m.change>=0?GREEN:RED,true));
    gap(c,7);
    long age=System.currentTimeMillis()-m.lastTradeMs;
    boolean fresh=m.lastTradeMs>0&&age>=0&&age<=10000L;
    c.addView(text((fresh?"Verified trade: ":"Stale/unverified trade: ")+clock(m.lastTradeMs),12,fresh?MUTED:AMBER,false));
    gap(c,10);
    c.addView(text("Volume: "+(Double.isFinite(m.dayVolume)?String.format(Locale.US,"%,.0f",m.dayVolume):"N/A")+
      "  ·  Groww avg traded price: "+format(m.vwap),12,INK,false));
    gap(c,5);
    c.addView(text("Bid "+format(m.bid)+"  /  Ask "+format(m.ask)+
      "  ·  Spread "+one(m.spreadBps)+" bps",12,INK,false));
    gap(c,5);
    c.addView(text("Completed 5m candles: "+m.fiveCount+
      "  ·  15m confirmation: "+(m.fifteenCount>0?"available":"unavailable"),12,MUTED,false));
    gap(c,5);
    c.addView(text("Latest completed 5m: "+format(m.lastFiveClose)+
      "  ·  15m: "+format(m.lastFifteenClose),12,MUTED,false));
    gap(c,8);
    c.addView(text((m.aboveVwap5&&m.aboveVwap15?"Price strength above average traded price observed. ":"VWAP/candle confirmation incomplete. ")+
      "Alpha-30 fundamentals, time-adjusted RVOL and executable risk/reward remain unverified.",12,AMBER,false));
  }
  private void settings(){
    LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(20),dp(7),dp(20),dp(7));
    EditText key=new EditText(this);key.setSingleLine(true);key.setHint("Groww TOTP Token");
    key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    EditText secret=new EditText(this);secret.setSingleLine(true);secret.setHint("Groww TOTP Secret (Base32)");
    secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    form.addView(text("Enter the TOTP token and secret generated on Groww's API Keys page. Leave blank to preserve existing values.",12,MUTED,false));
    form.addView(key);form.addView(secret);
    new AlertDialog.Builder(this).setTitle("Groww · Encrypted Credentials").setView(form)
      .setPositiveButton("Save & Connect",(d,w)->{
        String k=key.getText().toString().trim(),s=secret.getText().toString().trim();
        try{
          if(!s.isEmpty())TotpUtil.base32(s);
          if(!k.isEmpty())vault.put("totp_token",k);
          if(!s.isEmpty())vault.put("totp_secret",s);
          if(!vault.configured()){status="Both Groww TOTP fields are required";render();return;}
          client.resetSession();quotes.clear();errors.clear();status="Credentials encrypted. Authenticating with Groww…";render();fetch();
        }catch(Exception ex){status="Could not validate or save credentials";render();}
      }).setNeutralButton("Delete Credentials",(d,w)->{
        vault.clearAll();client.resetSession();quotes.clear();errors.clear();
        status="Groww credentials deleted from this device";render();
      }).setNegativeButton("Cancel",null).show();
  }
  private void editWatchlist(){
    EditText input=new EditText(this);input.setText(String.join(",",symbols()));
    input.setSingleLine(false);input.setMinLines(2);input.setHint("TARIL,GAEL,ADVANCE");
    new AlertDialog.Builder(this).setTitle("NSE symbols · max 20")
      .setMessage("Separate symbols with commas. Use NSE trading symbols.")
      .setView(input).setPositiveButton("Save",(d,w)->{
        String raw=input.getText().toString().trim().toUpperCase(Locale.US);
        String[] items=raw.split(",");List<String> clean=new ArrayList<>();
        for(String item:items){
          String s=item.trim();
          if(!s.matches("[A-Z0-9&-]{1,26}")){Toast.makeText(this,"Invalid symbol: "+s,Toast.LENGTH_LONG).show();return;}
          if(!clean.contains(s))clean.add(s);
        }
        if(clean.isEmpty()||clean.size()>20){Toast.makeText(this,"Use 1–20 NSE symbols",Toast.LENGTH_LONG).show();return;}
        getPreferences(MODE_PRIVATE).edit().putString("watchlist",String.join(",",clean)).apply();
        quotes.clear();errors.clear();status="Watchlist updated; refresh to fetch prices";render();fetch();
      }).setNegativeButton("Cancel",null).show();
  }
  private void showFactors(){
    StringBuilder b=new StringBuilder();
    for(int i=0;i<30;i++){
      if(i==0)b.append("FUNDAMENTALS (27)\n");
      if(i==10)b.append("\nCATALYSTS (23)\n");
      if(i==17)b.append("\nMACRO (19)\n");
      if(i==25)b.append("\nTRADING (31)\n");
      b.append(String.format(Locale.US,"%02d · %s  (%d)\n",i+1,Alpha30Engine.FACTORS[i],Alpha30Engine.WEIGHTS[i]));
    }
    new AlertDialog.Builder(this).setTitle("Alpha-30 frozen formula").setMessage(b.toString())
      .setPositiveButton("Close",null).show();
  }
  private void fetch(){
    if(!vault.configured()){status="Enter Groww TOTP token + secret in Settings";render();return;}
    if(loading)return;
    long now=System.currentTimeMillis();
    if(now-lastAttempt<9000L){status="Rate protection: allow a few seconds before retry";render();return;}
    lastAttempt=now;loading=true;status="Requesting read-only Groww quotes…";render();
    List<String> sym=symbols();
    boolean fullFive=java.time.ZonedDateTime.now(MarketSchedule.IST).getMinute()%5==0;
    worker.execute(()->{
      List<GrowwReadOnlyClient.Market> result=new ArrayList<>();
      List<String> failures=new ArrayList<>();
      for(String s:sym){
        try{
          GrowwReadOnlyClient.Market m=client.quote(s);
          JSONObject prior=watchStore.get(s);
          if(fullFive || !prior.has("reference")){
            try{client.dailyReference(m);}catch(Exception ignored){m.note="Daily breakout unavailable";}
          }else{
            m.breakoutLevel=prior.optDouble("reference",Double.NaN);
            m.referenceAsOf=prior.optString("referenceAsOf","");
            m.referenceSource=prior.optString("referenceSource","");
          }
          if(fullFive){
            try{client.candles(m);}catch(Exception ignored){m.note+="; candles unavailable";}
          }else{
            m.two5m=prior.optBoolean("two5m",false);
            m.fifteenCount=prior.optInt("fifteenCount",0);
            m.fiveCount=prior.optInt("fiveCount",0);
            m.aboveVwap5=prior.optBoolean("aboveVwap5",false);
            m.aboveVwap15=prior.optBoolean("aboveVwap15",false);
            m.lastFiveClose=prior.optDouble("lastFiveClose",Double.NaN);
            m.lastFifteenClose=prior.optDouble("lastFifteenClose",Double.NaN);
          }
          WatchState.Progress progress=WatchState.decide(m.price,m.breakoutLevel,m.lastTradeMs,
            System.currentTimeMillis(),true,m.two5m,m.fifteenCount>0 &&
              Double.isFinite(m.breakoutLevel) && Double.isFinite(m.lastFifteenClose) &&
              m.lastFifteenClose>m.breakoutLevel && m.aboveVwap15,m.aboveVwap5&&m.aboveVwap15);
          try{watchStore.put(WatchState.json(m,progress,System.currentTimeMillis()));}catch(Exception ignored){}
          result.add(m);
        }catch(Exception ex){
          String message=ex.getMessage();
          // Error text is generated internally, never propagate a raw response with secrets.
          failures.add(s+" · "+(message==null?"Unavailable":message));
        }
      }
      if(MarketSchedule.checkpoint(now)){
        StringBuilder summary=new StringBuilder();
        for(String symbol:sym){
          JSONObject snap=watchStore.get(symbol);
          if(summary.length()>0)summary.append(" | ");
          summary.append(symbol).append(": ").append(snap.optString("stage","NO DATA"));
        }
        watchStore.recordPreClose(MarketSchedule.checkpointLabel(now),summary.toString());
      }
      handler.post(()->{
        quotes.clear();quotes.addAll(result);errors.clear();errors.addAll(failures);
        loading=false;
        watchStore.recordScan(failures.isEmpty(),failures.isEmpty()?"":String.join(" | ",failures),result.size());
        status="Groww fetched "+result.size()+"/"+sym.size()+" quotes · "+clock(System.currentTimeMillis())+
          " · verify exchange timestamps on each card";
        render();
      });
    });
  }
}

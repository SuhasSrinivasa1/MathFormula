package com.mathformula.alpha;

import android.app.Activity;
import android.net.Uri;
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
  private EditText growwTokenInput, growwSecretInput;
  private boolean growwChecking=false;
  private String growwConnectionMessage="";
  private static final long AUTH_FRESH_MS=15*60_000L;
  private static final String KEY_VERIFIED_AT="groww_verified_at_v22";
  private static final String KEY_LAST_TRADE="groww_verified_last_trade_v22";
  private final BroadcastReceiver scanReceiver=new BroadcastReceiver(){
    @Override public void onReceive(Context c,Intent intent){render();}
  };
  private LinearLayout page;
  private boolean loading=false;
  private String activeTab="LIVE";
  private boolean serverLoading=false;
  private static final int EXPORT_DIAGNOSTICS=4165;
  private long serverLastAttemptMs=0,serverLastSuccessMs=0;
  private JSONObject serverStatus=new JSONObject();
  private org.json.JSONArray serverStocks=new org.json.JSONArray();
  private org.json.JSONArray serverPreClose=new org.json.JSONArray();
  private org.json.JSONArray serverMisses=new org.json.JSONArray();
  private String serverError="Server not configured";
  private long lastAttempt=0;
  private String status="OFFLINE · Enter Groww TOTP credentials";
  private final List<GrowwReadOnlyClient.Market> quotes=new ArrayList<>();
  private final List<String> errors=new ArrayList<>();
  private final Runnable fiveMinute=new Runnable(){
    @Override public void run(){
      // Do not initiate a costly legacy-watchlist quote scan from Settings.
      render();
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
    // Keep advanced data-collection panels out of the new Settings UI.
    if(vault.configured())status="Groww credentials saved securely. Refresh to retrieve prices.";
    getWindow().setStatusBarColor(Color.rgb(248,250,252));
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
    scroll.setBackgroundColor(Color.rgb(247,249,252));
    page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
    page.setPadding(dp(19),dp(24),dp(19),dp(30));scroll.addView(page);setContentView(scroll);render();
  }
  @Override protected void onResume(){
    super.onResume();
    // Do not redraw input controls on a timer while the user enters credentials.
  }
  @Override protected void onPause(){handler.removeCallbacks(fiveMinute);
    try{unregisterReceiver(scanReceiver);}catch(Exception ignored){}
    super.onPause();}
  @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
  private void exportLogs(){
    Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.setType("application/zip");
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    String name="MathFormula_Alpha30_Audit_"+
      java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")
        .withZone(ZoneId.of("Asia/Kolkata"))
        .format(Instant.now())+".zip";
    intent.putExtra(Intent.EXTRA_TITLE,name);
    try{startActivityForResult(intent,EXPORT_DIAGNOSTICS);}
    catch(Exception ex){status="Android file picker unavailable";render();}
  }
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(request!=EXPORT_DIAGNOSTICS || result!=RESULT_OK || data==null || data.getData()==null)return;
    final Uri destination=data.getData();
    final List<String> chosen=symbols();
    final String endpoint=serverUrl();
    final String readToken=vault.get("server_viewer_token");
    status="Preparing diagnostic ZIP with private credentials excluded…";render();
    worker.execute(()->{
      String error=null;
      try{DiagnosticExporter.save(getApplicationContext(),destination,watchStore,chosen,endpoint,readToken);}
      catch(Exception ex){error=ex.getClass().getSimpleName();}
      final String problem=error;
      handler.post(()->{
        status=problem==null?
          "Diagnostic ZIP saved. Upload it in our chat for analysis.":
          "Export failed ("+problem+"). Try another folder.";
        Toast.makeText(this,status,Toast.LENGTH_LONG).show();
        render();
      });
    });
  }

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
  private long lastVerified(){
    return getSharedPreferences("mathformula_groww_connection_v22",MODE_PRIVATE).getLong(KEY_VERIFIED_AT,0L);
  }
  private boolean verifiedRecently(){
    long age=System.currentTimeMillis()-lastVerified();
    return vault.configured() && age>=0 && age<=AUTH_FRESH_MS;
  }
  private EditText passwordField(String placeholder){
    EditText e=new EditText(this);
    e.setSingleLine(true);
    e.setTextSize(14);
    e.setTextColor(INK);
    e.setHintTextColor(MUTED);
    e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    e.setSaveEnabled(false);
    e.setPadding(dp(12),dp(14),dp(12),dp(14));
    e.setBackground(background(Color.rgb(245,248,252),11));
    e.setHint(placeholder);
    return e;
  }
  /** Only save encrypted values; never show credentials or imply authentication. */
  private boolean saveInlineCredentials(){
    String token=growwTokenInput==null?"":growwTokenInput.getText().toString().trim();
    String secret=growwSecretInput==null?"":growwSecretInput.getText().toString().trim();
    boolean changed=!token.isEmpty()||!secret.isEmpty();
    try{
      if(!secret.isEmpty())TotpUtil.base32(secret);
      if(token.isEmpty()&&vault.get("totp_token").isEmpty()){
        growwConnectionMessage="Enter your Groww TOTP token";
        Toast.makeText(this,growwConnectionMessage,Toast.LENGTH_LONG).show();return false;
      }
      if(secret.isEmpty()&&vault.get("totp_secret").isEmpty()){
        growwConnectionMessage="Enter your Groww TOTP secret";
        Toast.makeText(this,growwConnectionMessage,Toast.LENGTH_LONG).show();return false;
      }
      if(!token.isEmpty())vault.put("totp_token",token);
      if(!secret.isEmpty())vault.put("totp_secret",secret);
      if(changed){
        client.resetSession();
        getSharedPreferences("mathformula_groww_connection_v22",MODE_PRIVATE).edit().remove(KEY_VERIFIED_AT).remove(KEY_LAST_TRADE).apply();
      }
      growwConnectionMessage=changed?"Credentials encrypted and saved · verify using Refresh & Connect":
        "Saved credentials retained · use Refresh & Connect to verify access";
      return true;
    }catch(Exception ex){
      growwConnectionMessage="Unable to validate or securely store Groww credentials";
      Toast.makeText(this,growwConnectionMessage,Toast.LENGTH_LONG).show();return false;
    }
  }
  private void saveGroww(){
    if(saveInlineCredentials())render();
  }
  /** A green check requires a successful authenticated Groww market-data API call. */
  private void refreshGrowwConnection(){
    if(growwChecking)return;
    if(!saveInlineCredentials())return;
    growwChecking=true;
    growwConnectionMessage="Authenticating and requesting Groww market data…";
    render();
    worker.execute(()->{
      String problem="";
      long tradeAt=0;
      try{
        // RELIANCE is used only to confirm read-only NSE cash market-data access.
        // A historical last-trade timestamp after close is not a live quote.
        GrowwReadOnlyClient.Market check=client.quote("RELIANCE");
        if(!Double.isFinite(check.price)||check.price<=0)
          throw new IllegalStateException("Groww returned no usable NSE cash quote");
        tradeAt=check.lastTradeMs;
      }catch(SecurityException e){
        problem="Groww authentication rejected · check TOTP token and secret";
      }catch(Exception e){
        String lower=e.getMessage()==null?"":e.getMessage().toLowerCase(Locale.US);
        problem=lower.contains("cooldown")?"Groww authentication cooldown · retry after one minute":
          lower.contains("429")||lower.contains("rate limit")?
            "Groww rate limit reached · retry after cooldown":
          "Groww connection could not be verified · check network and credentials";
      }
      final String failure=problem;final long lastTrade=tradeAt;
      handler.post(()->{
        growwChecking=false;
        if(failure.isEmpty()){
          getSharedPreferences("mathformula_groww_connection_v22",MODE_PRIVATE).edit()
            .putLong(KEY_VERIFIED_AT,System.currentTimeMillis())
            .putLong(KEY_LAST_TRADE,lastTrade).apply();
          growwConnectionMessage="Groww authenticated · read-only API response verified";
        }else{
          getSharedPreferences("mathformula_groww_connection_v22",MODE_PRIVATE).edit().remove(KEY_VERIFIED_AT).apply();
          growwConnectionMessage=failure;
        }
        render();
      });
    });
  }
  /** Inline, accessible Groww connection settings. No second server login required for Groww data. */
  private void render(){
    if(page==null)return;
    page.removeAllViews();
    page.addView(text("Settings",28,INK,true));
    gap(page,4);
    page.addView(text("MATHFORMULA v2.2  ·  ALPHA-30 RESEARCH",11,BLUE,true));
    gap(page,17);
    LinearLayout connection=card();
    connection.addView(text("Groww connection",19,INK,true));
    gap(connection,9);
    String label;
    int stateColor;
    if(growwChecking){label="◌ Checking Groww authentication";stateColor=AMBER;}
    else if(verifiedRecently()){label="✓ Groww authenticated";stateColor=GREEN;}
    else if(!vault.configured()){label="○ Groww credentials not configured";stateColor=AMBER;}
    else if(lastVerified()>0){label="◷ Previously authenticated · verify again";stateColor=AMBER;}
    else {label="○ Credentials saved · not yet authenticated";stateColor=AMBER;}
    connection.addView(text(label,15,stateColor,true));
    if(lastVerified()>0){
      gap(connection,4);
      connection.addView(text("Last successful API verification: "+clock(lastVerified()),
        11,MUTED,false));
      long exchangeAt=getSharedPreferences("mathformula_groww_connection_v22",MODE_PRIVATE).getLong(KEY_LAST_TRADE,0);
      if(exchangeAt>0){
        connection.addView(text("Quote exchange time: "+clock(exchangeAt)+
          " · may be old outside NSE hours",11,MUTED,false));
      }
    }
    if(!growwConnectionMessage.isEmpty()){
      gap(connection,7);
      connection.addView(text(growwConnectionMessage,12,
        verifiedRecently()?GREEN:AMBER,false));
    }
    gap(connection,14);
    connection.addView(text("Groww TOTP token",13,INK,true));
    gap(connection,5);
    growwTokenInput=passwordField(vault.get("totp_token").isEmpty()?
      "Enter Groww TOTP token":"Saved securely · leave blank to keep");
    connection.addView(growwTokenInput,new LinearLayout.LayoutParams(-1,dp(53)));
    gap(connection,13);
    connection.addView(text("Groww TOTP secret",13,INK,true));
    gap(connection,5);
    growwSecretInput=passwordField(vault.get("totp_secret").isEmpty()?
      "Enter Base32 TOTP secret":"Saved securely · leave blank to keep");
    connection.addView(growwSecretInput,new LinearLayout.LayoutParams(-1,dp(53)));
    gap(connection,6);
    connection.addView(text("Fields are masked. Saved values stay encrypted on this device; empty fields preserve saved values.",11,MUTED,false));
    gap(connection,14);
    LinearLayout actions=new LinearLayout(this);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    Button save=btn("Save",this::saveGroww);
    Button connect=btn(growwChecking?"Checking…":"↻ Refresh & Connect",this::refreshGrowwConnection);
    save.setEnabled(!growwChecking);connect.setEnabled(!growwChecking);
    LinearLayout.LayoutParams one=new LinearLayout.LayoutParams(0,dp(48),1);
    one.rightMargin=dp(9);
    actions.addView(save,one);
    actions.addView(connect,new LinearLayout.LayoutParams(0,dp(48),2));
    connection.addView(actions);
    gap(page,2);
    LinearLayout alerts=card();
    alerts.addView(text("Monitoring & notifications",18,INK,true));
    gap(alerts,7);
    alerts.addView(text(ResearchAlerts.permission(this)?
      "✓ Android notifications enabled":"○ Android notifications disabled",13,
      ResearchAlerts.permission(this)?GREEN:AMBER,true));
    gap(alerts,5);
    alerts.addView(text("Research alerts use direct Groww market data. Phone monitoring is best-effort and can stop in the background. No unverified BUY signals.",12,MUTED,false));
    gap(alerts,7);
    alerts.addView(text("Last research alert evaluation: "+clock(ResearchAlerts.lastCheck(this)),11,MUTED,false));
    gap(alerts,9);
    alerts.addView(btn(watchStore.enabled()?"Restart phone monitoring":"Start phone monitoring",
      this::startMonitoring),new LinearLayout.LayoutParams(-1,dp(45)));
    if(watchStore.enabled()){
      gap(alerts,7);
      alerts.addView(btn("Stop phone monitoring",this::stopMonitoring),
        new LinearLayout.LayoutParams(-1,dp(41)));
    }
    gap(page,2);
    LinearLayout preference=card();
    preference.addView(text("Research preferences",18,INK,true));
    gap(preference,6);
    preference.addView(text("Frozen Alpha-30 model · unchanged 30 factors",12,MUTED,false));
    gap(preference,9);
    preference.addView(btn("View all 30 model factors",this::showFactors),
      new LinearLayout.LayoutParams(-1,dp(43)));
    gap(page,2);
    LinearLayout privacy=card();
    privacy.addView(text("Privacy & diagnostics",18,INK,true));
    gap(privacy,7);
    privacy.addView(text("Read-only Groww integration · no order placement · encrypted Android Keystore storage.",12,MUTED,false));
    gap(privacy,9);
    privacy.addView(btn("Export sanitized diagnostics",this::exportLogs),
      new LinearLayout.LayoutParams(-1,dp(43)));
    gap(privacy,7);
    privacy.addView(btn("View local audit",this::showAudit),
      new LinearLayout.LayoutParams(-1,dp(43)));
    gap(page,4);
    page.addView(text("Groww provides market data, not every required financial, news and macro factor. Until those are verified, opportunities remain research-only.",11,AMBER,false));
    gap(page,12);
    page.addView(btn("← Back to recommendations",this::finish),
      new LinearLayout.LayoutParams(-1,dp(43)));
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
  private String serverUrl(){
    return getPreferences(MODE_PRIVATE).getString("server_url","");
  }
  private void editServer(){
    LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(18),dp(8),dp(18),dp(6));
    EditText url=new EditText(this);url.setSingleLine(true);
    url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
    url.setHint("https://scanner.your-domain.com");url.setText(serverUrl());
    EditText key=new EditText(this);key.setSingleLine(true);
    key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    key.setHint("Separate APP_READ_TOKEN from server .env");
    form.addView(text("Server needs independent HTTPS deployment. This is NOT a Groww credential.",12,MUTED,false));
    form.addView(url);form.addView(key);
    new AlertDialog.Builder(this).setTitle("NSE scanner · HTTPS viewer access")
      .setView(form).setPositiveButton("Save",(d,w)->{
        String base=url.getText().toString().trim().replaceAll("/+$","");
        String token=key.getText().toString().trim();
        try{
          String effective=token.isEmpty()?vault.get("server_viewer_token"):token;
          if(!base.isEmpty())new BackendReadOnlyClient(base,effective);
          if(!token.isEmpty())vault.put("server_viewer_token",token);
          getPreferences(MODE_PRIVATE).edit().putString("server_url",base).apply();
          serverError=base.isEmpty()?"Server not configured":"Connecting to independent NSE scanner";
          activeTab="SERVER";render();if(!base.isEmpty())fetchServer();
        }catch(Exception ex){serverError="Invalid HTTPS server address or viewer token";render();}
      }).setNeutralButton("Disconnect",(d,w)->{
        getPreferences(MODE_PRIVATE).edit().remove("server_url").apply();
        serverStocks=new org.json.JSONArray();serverStatus=new JSONObject();serverError="Server disconnected";
        vault.put("server_viewer_token","");render();
      }).setNegativeButton("Cancel",null).show();
  }
  private void fetchServer(){
    if(serverLoading)return;
    if(serverUrl().isEmpty()||vault.get("server_viewer_token").length()<32){
      serverError="Configure an HTTPS scanner and APP_READ_TOKEN in Server Settings";
      render();return;
    }
    long now=System.currentTimeMillis();
    if(now-serverLastAttemptMs<10000L){render();return;}
    serverLastAttemptMs=now;serverLoading=true;
    serverError="Connecting to scanner…";render();
    worker.execute(()->{
      JSONObject status=null,stocks=null,preclose=null,misses=null;String error=null;
      try{
        BackendReadOnlyClient service=new BackendReadOnlyClient(serverUrl(),vault.get("server_viewer_token"));
        status=service.get("/v1/status");
        stocks=service.get("/v1/watchlist");
        preclose=service.get("/v1/preclose");
        misses=service.get("/v1/misses");
      }catch(Exception e){
        error=e.getMessage()==null?"Scanner endpoint unavailable":e.getMessage();
      }
      final JSONObject a=status,b=stocks,c=preclose,d=misses;
      final String failure=error;
      handler.post(()->{
        serverLoading=false;
        if(failure!=null){serverError=failure;}
        else{
          serverStatus=a;
          serverStocks=b.optJSONArray("stocks")==null?new org.json.JSONArray():b.optJSONArray("stocks");
          serverPreClose=c.optJSONArray("stocks")==null?new org.json.JSONArray():c.optJSONArray("stocks");
          serverMisses=d.optJSONArray("items")==null?new org.json.JSONArray():d.optJSONArray("items");
          serverLastSuccessMs=System.currentTimeMillis();serverError="";
        }
        render();
      });
    });
  }
  private void renderServer(){
    page.addView(text("NSE-WIDE DISCOVERY · SERVER",15,BLUE,true));gap(page,7);
    LinearLayout status=card();
    status.addView(text(serverError.isEmpty()?"Scanner status received":serverError,13,serverError.isEmpty()?GREEN:AMBER,true));
    gap(status,6);
    status.addView(text("Last Android synchronization: "+clock(serverLastSuccessMs),12,MUTED,false));
    int count=serverStatus.optInt("universeSymbols",0);
    int ltp=serverStatus.optInt("freshLtpSymbols",0),days=serverStatus.optInt("historicalTradingDays",0);
    status.addView(text("Loaded EQ stocks: "+count+" · Fresh LTP: "+ltp+
       " · EOD history trading days: "+days,13,INK,false));
    boolean wide=serverStatus.optBoolean("fullUniverseClaimsValid",false);
    status.addView(text(wide?"Coverage check: ≥3,000 symbols, ≥90% live prices, 60+ historical dates":
      "Coverage not ready: verify NSE universe, at least 60 prior EOD sessions and Groww LTP connection",
      12,wide?GREEN:AMBER,false));
    JSONObject last=serverStatus.optJSONObject("latestScan");
    if(last!=null){
      status.addView(text("Last completed scan: "+clock(last.optLong("at_ms",0))+
         " · "+last.optString("window")+" · eligible "+last.optInt("eligible")+
         " · total "+last.optInt("universe_total"),12,MUTED,false));
    }else status.addView(text("No recorded universe scan yet",12,AMBER,false));
    status.addView(text("Frozen Alpha-30: "+serverStatus.optString("modelVersion","unknown")+
      " · BUY signal unavailable until all execution gates are verified",12,AMBER,true));
    page.addView(btn("Refresh server",this::fetchServer),new LinearLayout.LayoutParams(-1,dp(42)));
    gap(page,10);
    page.addView(text("DISCOVERED CANDIDATES · NOT BUY SIGNALS",12,BLUE,true));gap(page,7);
    if(serverStocks.length()==0){
      LinearLayout c=card();
      c.addView(text("No verified ranked candidates",15,INK,true));
      c.addView(text("The server needs a loaded EQ security master, 60+ complete EOD sessions per stock and a Groww data connection. It will not invent a top three.",12,AMBER,false));
    }
    for(int i=0;i<serverStocks.length();i++){
      JSONObject r=serverStocks.optJSONObject(i);if(r==null)continue;
      JSONObject details=r.optJSONObject("details");if(details==null)details=new JSONObject();
      LinearLayout c=card();
      c.addView(text("#"+(i+1)+"  "+r.optString("symbol")+" · "+r.optString("stage"),16,INK,true));gap(c,6);
      c.addView(text("Observed price: "+format(r.optDouble("price",Double.NaN))+
         " · 20-session breakout "+format(r.optDouble("breakoutLevel",Double.NaN)),14,BLUE,true));
      c.addView(text("Distance above breakout "+one(details.optDouble("distancePct",Double.NaN))+
        "% · daily momentum "+one(details.optDouble("momentumPct",Double.NaN))+"%",12,MUTED,false));
      c.addView(text("Technical discovery rank "+one(r.optDouble("preliminaryRank",Double.NaN))+
       "/100 — NOT Alpha-30 score",12,AMBER,true));
      double score=r.optDouble("alpha30Score",Double.NaN);
      c.addView(text("Frozen model: "+(Double.isFinite(score)?one(score):"INCOMPLETE")+
        " · feature coverage "+one(r.optDouble("coverage_pct",Double.NaN))+"%",12,MUTED,false));
      c.addView(text("Data observed: "+clock(details.optLong("dataAsOfMs",0))+
        " · quote time verified: "+(!r.optBoolean("quoteStale",true)),11,MUTED,false));
      c.addView(text("NO SIGNAL · needs complete point-in-time data and executable risk checks",12,AMBER,true));
    }
    gap(page,5);page.addView(text("PRE-CLOSE REVIEWS",12,BLUE,true));gap(page,6);
    if(serverPreClose.length()==0)page.addView(text("No pre-close server snapshot stored yet.",12,MUTED,false));
    else{
      for(int i=0;i<Math.min(5,serverPreClose.length());i++){
        JSONObject j=serverPreClose.optJSONObject(i);if(j==null)continue;
        page.addView(text(j.optString("symbol")+" · "+j.optString("window")+
         " · "+format(j.optDouble("price",Double.NaN)),12,INK,false));
      }
    }
    gap(page,8);page.addView(text("MISSED-MOVER AUDIT (5 completed sessions)",12,BLUE,true));gap(page,5);
    if(serverMisses.length()==0)page.addView(text("No complete 5-session outcome records yet.",12,MUTED,false));
    for(int i=0;i<Math.min(10,serverMisses.length());i++){
      JSONObject j=serverMisses.optJSONObject(i);if(j==null)continue;
      page.addView(text(j.optString("symbol")+" · "+j.optString("day")+
        " · peak "+one(j.optDouble("peak_return_pct",Double.NaN))+
        "% · "+(j.optInt("captured")==1?"SHORTLISTED":"MISSED"),12,INK,false));
    }
    gap(page,6);
    page.addView(text("The independently deployed server continues scanning when this phone is closed. This screen refreshes periodically while visible; instant device push alerts still require a configured push channel.",12,MUTED,false));
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

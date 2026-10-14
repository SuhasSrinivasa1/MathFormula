package com.mathformula.alpha;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
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
  private LinearLayout page;
  private boolean loading=false;
  private long lastAttempt=0;
  private String status="OFFLINE · Enter Groww TOTP credentials";
  private final List<GrowwReadOnlyClient.Market> quotes=new ArrayList<>();
  private final List<String> errors=new ArrayList<>();
  private final Runnable fiveMinute=new Runnable(){
    @Override public void run(){
      if(vault.configured())fetch();
      handler.postDelayed(this,300000L);
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
    if(vault.configured())status="Groww credentials saved securely. Refresh to retrieve prices.";
    getWindow().setStatusBarColor(Color.rgb(248,250,252));
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
    scroll.setBackgroundColor(Color.rgb(247,249,252));
    page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
    page.setPadding(dp(19),dp(24),dp(19),dp(30));scroll.addView(page);setContentView(scroll);render();
  }
  @Override protected void onResume(){super.onResume();handler.postDelayed(fiveMinute,300);}
  @Override protected void onPause(){handler.removeCallbacks(fiveMinute);super.onPause();}
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
    addButtonRow("Edit Watchlist",this::editWatchlist,"30 Model Factors",this::showFactors);
    page.addView(text("NSE WATCHLIST",12,BLUE,true));gap(page,10);
    if(quotes.isEmpty()){
      for(String symbol:symbols()){
        LinearLayout c=card();
        c.addView(text(symbol+"  ·  NO SIGNAL",17,INK,true));gap(c,6);
        c.addView(text("Awaiting a verified Groww quote",13,AMBER,false));
      }
    }else{
      for(GrowwReadOnlyClient.Market q:quotes)drawQuote(q);
    }
    for(String error:errors){
      LinearLayout c=card();c.addView(text(error,12,RED,false));
    }
    LinearLayout help=card();
    help.addView(text("Why not automatically BUY?",16,INK,true));gap(help,9);
    help.addView(text("Groww provides price, volume, quotes and candles. It does not by itself supply all 30 required financial, catalyst and macro inputs, nor a validated target and net risk/reward. Missing evidence forces NO SIGNAL.",12,MUTED,false));
    gap(help,10);
    help.addView(text("Market data refreshes every 5 minutes while this screen is open. This is not an always-on background scanner.",12,AMBER,true));
    gap(page,4);
    page.addView(text("Credentials encrypted with Android Keystore. No access tokens or TOTP codes are logged, exported, or committed. Only authorized read-only endpoints are called.",11,MUTED,false));
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
    worker.execute(()->{
      List<GrowwReadOnlyClient.Market> result=new ArrayList<>();
      List<String> failures=new ArrayList<>();
      for(String s:sym){
        try{
          GrowwReadOnlyClient.Market m=client.quote(s);
          try{client.candles(m);}catch(Exception ignored){m.note="Candle feed not available";}
          result.add(m);
        }catch(Exception ex){
          String message=ex.getMessage();
          // Error text is generated internally, never propagate a raw response with secrets.
          failures.add(s+" · "+(message==null?"Unavailable":message));
        }
      }
      handler.post(()->{
        quotes.clear();quotes.addAll(result);errors.clear();errors.addAll(failures);
        loading=false;
        status="Groww fetched "+result.size()+"/"+sym.size()+" quotes · "+clock(System.currentTimeMillis())+
          " · prices validated by exchange last-trade timestamp";
        render();
      });
    });
  }
}

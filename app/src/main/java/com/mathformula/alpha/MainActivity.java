package com.mathformula.alpha;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URL;
import java.net.HttpURLConnection;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Read-only dashboard; broker credentials must stay on an authorized backend. */
public final class MainActivity extends Activity {
  private static final int INK=Color.rgb(25,36,54), BLUE=Color.rgb(31,100,173),
    MUTED=Color.rgb(109,119,132), GREEN=Color.rgb(20,115,85), AMBER=Color.rgb(174,105,18);
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final ExecutorService executor=Executors.newSingleThreadExecutor();
  private LinearLayout root;
  private boolean loading=false;
  private List<JSONObject> stocks=new ArrayList<>();
  private String status="OFFLINE · No live broker feed connected";
  private final Runnable timer=new Runnable(){
    @Override public void run(){if(!endpoint().isEmpty())refresh();handler.postDelayed(this,300000);}
  };
  private int px(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
  private String endpoint(){return getPreferences(MODE_PRIVATE).getString("endpoint","");}
  private GradientDrawable bg(int color,int radius){
    GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(px(radius));return d;
  }
  private TextView text(String s,int size,int color,boolean bold){
    TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(size);
    if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setLineSpacing(px(2),1.06f);return t;
  }
  private void gap(LinearLayout box,int v){box.addView(new View(this),new LinearLayout.LayoutParams(1,px(v)));}
  private LinearLayout card(){
    LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);
    c.setPadding(px(16),px(16),px(16),px(16));c.setBackground(bg(Color.WHITE,16));
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=px(13);
    root.addView(c,p);return c;
  }
  private Button button(String name,Runnable r){
    Button b=new Button(this);b.setText(name);b.setAllCaps(false);
    b.setTextColor(Color.WHITE);b.setTextSize(13);b.setBackground(bg(BLUE,12));
    b.setOnClickListener(v->r.run());return b;
  }
  @Override public void onCreate(Bundle saved){
    super.onCreate(saved);getWindow().setStatusBarColor(Color.rgb(247,249,252));
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
    scroll.setBackgroundColor(Color.rgb(247,249,252));
    root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(px(20),px(24),px(20),px(28));scroll.addView(root);setContentView(scroll);render();
  }
  @Override protected void onResume(){super.onResume();handler.postDelayed(timer,200);}
  @Override protected void onPause(){handler.removeCallbacks(timer);super.onPause();}
  @Override protected void onDestroy(){executor.shutdownNow();super.onDestroy();}
  private void render(){
    root.removeAllViews();
    root.addView(text("MathFormula",29,INK,true));
    root.addView(text("ALPHA-30  ·  NSE RESEARCH",11,BLUE,true));gap(root,15);
    LinearLayout box=card();
    box.addView(text(endpoint().isEmpty()?"Research / offline mode":"Read-only API mode",17,INK,true));
    gap(box,6);box.addView(text(status,12,AMBER,false));gap(box,7);
    box.addView(text("Fixed v1.0 · 30 weighted factors · 90% accuracy NOT verified",12,MUTED,false));
    LinearLayout buttons=new LinearLayout(this);buttons.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,px(48),1);left.rightMargin=px(10);
    buttons.addView(button(loading?"Refreshing…":"Refresh",this::refresh),left);
    buttons.addView(button("Connect API",this::configure),new LinearLayout.LayoutParams(0,px(48),1));
    root.addView(buttons);gap(root,20);
    root.addView(text("WATCHLIST",12,BLUE,true));gap(root,9);
    if(stocks.isEmpty()) {
      displayReference("TARIL","₹304.00","13 Oct close · earnings catalyst");
      displayReference("GAEL","₹173.26","13 Oct close · ₹174.90 breakout reference");
      displayReference("ADVANCE","₹143.97","13 Oct close · extreme prior volume");
    } else {
      for(JSONObject item:stocks)displayStock(item);
    }
    gap(root,4);
    LinearLayout rule=card();
    rule.addView(text("Trade signal rules",16,INK,true));gap(rule,7);
    rule.addView(text("Score ≥78 plus confirmed breakout, two 5-minute closes, 15-minute candle, VWAP hold, time-adjusted RVOL ≥1.5, positive sector, adequate order-book liquidity, net R:R ≥1.25 and quote freshness ≤10 seconds.",13,MUTED,false));
    gap(rule,8);
    rule.addView(text("Missing or stale data ⇒ NO SIGNAL. No automatic trades.",12,AMBER,true));
    root.addView(button("View all 30 formula factors",this::showFactors),new LinearLayout.LayoutParams(-1,px(48)));
    gap(root,14);
    root.addView(text("Research prototype. API keys never go inside this APK. BUY eligibility is not investment advice.",12,MUTED,false));
  }
  private void displayReference(String symbol,String price,String info){
    LinearLayout c=card();c.addView(text(symbol+"  ·  NO LIVE SIGNAL",16,INK,true));
    gap(c,8);c.addView(text(price,18,BLUE,true));gap(c,4);
    c.addView(text(info,12,MUTED,false));gap(c,8);
    c.addView(text("HISTORICAL REFERENCE ONLY",11,AMBER,true));
  }
  private Alpha30Engine.Snapshot parse(JSONObject j){
    Alpha30Engine.Snapshot s=new Alpha30Engine.Snapshot();
    s.symbol=j.optString("symbol","UNKNOWN");s.quoteTimeMs=j.optLong("quoteTimestampMs",0);
    s.lastPrice=j.optDouble("lastPrice",Double.NaN);
    s.breakoutLevel=j.optDouble("breakoutLevel",Double.NaN);
    s.marketOpen=j.optBoolean("marketOpen",false);
    JSONArray f=j.optJSONArray("factors");
    if(f!=null)for(int i=0;i<Math.min(f.length(),30);i++)s.factors[i]=f.optDouble(i,Double.NaN);
    JSONObject g=j.optJSONObject("gates");
    if(g!=null){
      s.tradingAllowed=g.optBoolean("tradingAllowed",false);
      s.materialRiskClear=g.optBoolean("materialRiskClear",false);
      s.breakout15m=g.optBoolean("breakout15m",false);
      s.confirmation5m=g.optBoolean("fiveMinuteConfirmation",false);
      s.vwap5m=g.optBoolean("vwap5m",false);s.vwap15m=g.optBoolean("vwap15m",false);
      s.sectorPositive=g.optBoolean("sectorPositive",false);
      s.liquidityVerified=g.optBoolean("liquidityVerified",false);
      s.rvol=g.optDouble("timeAdjustedRvol",Double.NaN);
      s.spreadBps=g.optDouble("spreadBps",Double.NaN);
      s.netRR=g.optDouble("netRiskReward",Double.NaN);
    }
    return s;
  }
  private void displayStock(JSONObject j){
    Alpha30Engine.Snapshot s=parse(j);
    Alpha30Engine.Result result=Alpha30Engine.evaluate(s,System.currentTimeMillis());
    LinearLayout c=card();String decision=result.decision.toString().replace('_',' ');
    c.addView(text(s.symbol+" · "+decision,16,result.decision==Alpha30Engine.Decision.BUY_ELIGIBLE?GREEN:INK,true));
    gap(c,7);
    String price=Double.isFinite(s.lastPrice)?String.format(Locale.US,"₹%.2f",s.lastPrice):"Price unavailable";
    c.addView(text(price+"   ·   Score "+String.format(Locale.US,"%.1f",result.score.value),17,BLUE,true));
    gap(c,5);
    c.addView(text("Coverage "+String.format(Locale.US,"%.0f%%",result.score.coverage)+
      " · Exchange timestamp "+s.quoteTimeMs,11,MUTED,false));
    for(String reason:result.reasons){gap(c,5);c.addView(text("• "+reason,12,AMBER,false));}
    if(result.reasons.isEmpty()){gap(c,5);c.addView(text("All gates pass; still requires broker/position risk approval.",12,GREEN,false));}
  }
  private void showFactors(){
    StringBuilder b=new StringBuilder();
    for(int i=0;i<30;i++){
      if(i==0)b.append("FUNDAMENTALS (27)\n");
      if(i==10)b.append("\nCATALYSTS (23)\n");
      if(i==17)b.append("\nMACRO (19)\n");
      if(i==25)b.append("\nTRADING (31)\n");
      b.append(String.format(Locale.US,"%02d  %s  (%d points)\n",i+1,Alpha30Engine.FACTORS[i],Alpha30Engine.WEIGHTS[i]));
    }
    new AlertDialog.Builder(this).setTitle("Alpha-30 fixed weights").setMessage(b.toString()).setPositiveButton("Close",null).show();
  }
  private void configure(){
    EditText edit=new EditText(this);edit.setSingleLine();edit.setHint("https://your-backend.example.com");
    edit.setText(endpoint());
    new AlertDialog.Builder(this).setTitle("Configure HTTPS data API")
      .setMessage("Enter YOUR authorized read-only backend URL (not a broker API key).")
      .setView(edit).setPositiveButton("Save",(d,w)->{
        String url=edit.getText().toString().trim().replaceAll("/+$","");
        if(!url.isEmpty()&&!url.startsWith("https://")) {
          Toast.makeText(this,"HTTPS required",Toast.LENGTH_LONG).show();return;
        }
        getPreferences(MODE_PRIVATE).edit().putString("endpoint",url).apply();
        stocks.clear();status=url.isEmpty()?"OFFLINE · No broker feed connected":"Configured; awaiting fresh snapshot";
        render();if(!url.isEmpty())refresh();
      }).setNegativeButton("Cancel",null).show();
  }
  private void refresh(){
    String base=endpoint();
    if(base.isEmpty()){status="NO SIGNAL · Connect a verified HTTPS data feed";render();return;}
    if(loading)return;loading=true;status="Fetching snapshots…";render();
    executor.execute(()->{
      List<JSONObject> data=new ArrayList<>();String error=null;
      HttpURLConnection c=null;
      try {
        c=(HttpURLConnection)new URL(base+"/v1/watchlist").openConnection();
        c.setConnectTimeout(5000);c.setReadTimeout(5000);c.setRequestProperty("Accept","application/json");
        if(c.getResponseCode()!=200)throw new IllegalStateException("Server HTTP "+c.getResponseCode());
        StringBuilder body=new StringBuilder();
        try(BufferedReader in=new BufferedReader(new InputStreamReader(c.getInputStream()))){
          String line;while((line=in.readLine())!=null)body.append(line);
        }
        JSONObject response=new JSONObject(body.toString());
        if(!Alpha30Engine.VERSION.equals(response.optString("modelVersion")))
          throw new IllegalStateException("Formula version mismatch");
        JSONArray entries=response.optJSONArray("stocks");
        if(entries!=null)for(int i=0;i<entries.length();i++)data.add(entries.getJSONObject(i));
      }catch(Exception ex){error=ex.getMessage();}
      finally{if(c!=null)c.disconnect();}
      final String err=error;
      handler.post(()->{
        loading=false;
        if(err!=null){stocks.clear();status="NO SIGNAL · "+err;}
        else{stocks=data;status="Fetched "+data.size()+" observations. Freshness checked individually.";}
        render();
      });
    });
  }
}

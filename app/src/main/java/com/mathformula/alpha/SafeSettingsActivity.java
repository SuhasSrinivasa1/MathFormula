package com.mathformula.alpha;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android-native Groww settings with automatic revalidation on resume.
 * All credentials remain in the existing AndroidKeystore-backed SecureGrowwStore.
 * Does not place orders, simulate trades, or calculate missing Alpha scores.
 * Does not instantiate Material widgets or research engines during initialization.
 */
public final class SafeSettingsActivity extends Activity {
  private static final int NAVY=Color.rgb(20,38,56);
  private static final int BLUE=Color.rgb(24,91,154);
  private static final int MUTED=Color.rgb(99,112,127);
  private static final int AMBER=Color.rgb(154,95,20);
  private static final int REQUEST_EXPORT=4811;
  private static final int REQUEST_AMFI=4812;
  private final Handler main=new Handler(Looper.getMainLooper());
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private LinearLayout content;
  private EditText tokenField,secretField,webSearchField;
  private SecureGrowwStore secure;
  private String message="Ready to connect your Groww read-only market-data account";
  private boolean checking=false;
  private boolean initialized=false;

  private int dp(int px){return (int)(getResources().getDisplayMetrics().density*px+.5f);}
  private TextView label(String text,int size,int color,boolean strong) {
    TextView v=new TextView(this);v.setText(text);v.setTextColor(color);v.setTextSize(size);
    if(strong)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
    v.setPadding(0,dp(5),0,dp(7));return v;
  }
  private EditText field(String hint,boolean password) {
    EditText e=new EditText(this);e.setSingleLine(true);e.setTextSize(15);
    e.setTextColor(NAVY);e.setHint(hint);
    e.setInputType(password?
      InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:
      InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
    e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    e.setSaveEnabled(false);
    return e;
  }
  private Button action(String title,Runnable run){
    Button b=new Button(this);b.setText(title);b.setAllCaps(false);
    b.setTextColor(Color.WHITE);b.setBackgroundTintList(ColorStateList.valueOf(BLUE));
    b.setOnClickListener(v->{try{run.run();}catch(RuntimeException ex){
      message="Action could not complete; see diagnostics";
      Toast.makeText(this,message,Toast.LENGTH_LONG).show();
    }});
    return b;
  }
  private void heading(String title){
    content.addView(label(title,18,NAVY,true));
  }
  private void addButton(String title,Runnable run){
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(50));
    p.bottomMargin=dp(8);content.addView(action(title,run),p);
  }
  private void rule(){
    View line=new View(this);line.setBackgroundColor(Color.rgb(222,228,236));
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(1));
    p.topMargin=dp(15);p.bottomMargin=dp(15);content.addView(line,p);
  }
  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    getWindow().setStatusBarColor(Color.rgb(248,250,252));
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
    scroll.setBackgroundColor(Color.rgb(247,249,252));
    content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(22),dp(22),dp(22),dp(28));
    scroll.addView(content);
    setContentView(scroll);
    try{
      secure=new SecureGrowwStore(getApplicationContext());
      initialized=true;
      draw();
    }catch(RuntimeException exception){
      initialized=false;
      content.removeAllViews();
      content.addView(label("MathFormula v4.0.3 · Settings recovery",22,NAVY,true));
      content.addView(label("The Android Keystore could not initialize. No credentials were erased. Restart your phone and reopen Settings, or export a crash log for diagnosis.",14,AMBER,false));
      addButton("Return to MathFormula",this::finish);
    }
  }
  private void draw(){
    if(!initialized||content==null)return;
    content.removeAllViews();
    heading("MathFormula 4.2.0");
    content.addView(label("Settings · read-only live market research",13,MUTED,false));
    content.addView(label(message,12,AMBER,false));
    rule();
    heading("Groww API connection");
    content.addView(label("Enter the Groww Trade API TOTP token and Base32 TOTP secret. Existing encrypted credentials remain saved when these fields are blank.",12,MUTED,false));
    content.addView(label("Saved TOTP credentials: "+(secure.configured()?"YES":"NO"),12,NAVY,true));
    long verified=GrowwConnectionHealth.verifiedAt(this);
    boolean fresh=verified>0&&System.currentTimeMillis()-verified<=20*60_000L;
    content.addView(label(GrowwConnectionHealth.label(this,secure.configured()),13,
      GrowwConnectionHealth.accountActionNeeded(this)?AMBER:fresh?Color.rgb(19,123,85):MUTED,true));
    content.addView(label("Groww access tokens expire daily at 06:00 IST. Saved TOTP credentials are used to renew them automatically. Android cannot guarantee an uninterrupted phone connection when sleeping.",11,MUTED,false));
    tokenField=field("Groww TOTP token · leave blank if saved",true);
    secretField=field("Groww TOTP secret · leave blank if saved",true);
    content.addView(tokenField);content.addView(secretField);
    addButton("Save encrypted credentials",()->saveCredentials(false));
    addButton(checking?"Checking Groww…":"Verify Groww market-data access",()->saveCredentials(true));
    rule();
    heading("Market data & public research");
    content.addView(label("NSE cash quotes, historical candles and instrument discovery: Groww Trade API. Company filings and fundamentals: official NSE, BSE and issuer publications. Macro, sectors and global catalysts: dated public internet sources.",12,MUTED,false));
    content.addView(label("Automatic full-universe scanning on this phone is subject to Groww account rate limits and Android background restrictions. A complete Alpha-40 score is shown only when all required evidence has been verified.",12,AMBER,false));
    rule();
    heading("Optional wider internet search");
    InternetResearchEngine research=new InternetResearchEngine(this);
    content.addView(label("Official public NSE sources do not require a separate search key. For broader web discovery, you may add your own Brave Search API key (optional). Search results are not automatically accepted as financial facts.",12,MUTED,false));
    content.addView(label("Internet research: "+research.status(),12,MUTED,false));
    webSearchField=field("Optional Brave Search API key · leave blank to keep",true);
    content.addView(webSearchField);
    addButton("Save optional search key",this::saveWebSearch);
    rule();
    heading("Automatic market-cap grouping");
    GrowwCapAuto caps=new GrowwCapAuto(this);
    content.addView(label("Paid Groww market-cap quotes cached: "+caps.savedCount()+
       " · updated automatically as stocks are researched.",12,NAVY,true));
    content.addView(label("Small/mid/large groups are estimated from Groww's current market capitalization using reference thresholds. They are NOT official AMFI six-month average classifications. No CSV or Excel upload needed.",11,AMBER,false));
    rule();
    heading("Diagnostics and notifications");
    content.addView(label(ResearchAlerts.permission(this)?
       "Android notification permission: enabled":"Android notification permission: disabled",12,MUTED,false));
    addButton("Vivo / Android battery settings",()->{
      try{
        Intent intent=new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        startActivity(intent);
      }catch(Exception error){
        Intent fallback=new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        fallback.setData(Uri.parse("package:"+getPackageName()));
        startActivity(fallback);
      }
    });
    content.addView(label("During NSE hours MathFormula starts a visible foreground monitoring notification where Android permits it. On market-closed days 'not started' is expected. Battery restrictions and Android 15 limits cannot be bypassed by repeated notifications.",11,MUTED,false));
    addButton("Open Android app settings",()->{
      Intent intent=new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
      intent.setData(Uri.parse("package:"+getPackageName()));
      startActivity(intent);
    });
    addButton("Export sanitized diagnostics ZIP",this::exportLogs);
    content.addView(label("No paper mode or broker orders. The app only displays sourced observations. Unavailable Alpha-30/40 scores remain N/A.",12,AMBER,false));
    addButton("Back to recommendations",this::finish);
  }
  private boolean credentialsOK(){
    String token=tokenField.getText().toString().trim();
    String secret=secretField.getText().toString().trim();
    try{
      if(!secret.isEmpty())TotpUtil.base32(secret);
      if(!token.isEmpty())secure.put("totp_token",token);
      if(!secret.isEmpty())secure.put("totp_secret",secret);
      if(!secure.configured()){
        message="Enter both Groww TOTP values before connecting";
        Toast.makeText(this,message,Toast.LENGTH_LONG).show();
        return false;
      }
      if(!token.isEmpty()||!secret.isEmpty()){
        getSharedPreferences("mathformula_groww_connection_v22",MODE_PRIVATE)
          .edit().remove("groww_verified_at_v22").apply();
      }
      return true;
    }catch(RuntimeException ex){
      message="Secure storage or TOTP format validation failed";
      Toast.makeText(this,message,Toast.LENGTH_LONG).show();return false;
    }
  }
  private void saveCredentials(boolean verify){
    if(checking)return;
    if(!credentialsOK())return;
    if(!verify){
      message="Groww credentials saved securely; verify access before market use";
      tokenField.setText("");secretField.setText("");
      draw();return;
    }
    startVerification(false);
  }
  @Override protected void onResume(){
    super.onResume();
    if(initialized&&secure!=null&&secure.configured()&&!checking&&
       GrowwConnectionHealth.needsProbe(this,true))
      startVerification(true);
  }
  private void startVerification(boolean automatic){
    if(checking||secure==null||!secure.configured())return;
    checking=true;
    GrowwConnectionHealth.markProbe(this);
    message=automatic?"Groww saved · automatically verifying connection…":
      "Checking Groww market-data access…";
    if(tokenField!=null)tokenField.setText("");
    if(secretField!=null)secretField.setText("");
    draw();
    worker.execute(()->{
      Throwable error=null;
      try{
        GrowwReadOnlyClient client=new GrowwReadOnlyClient(secure);
        GrowwReadOnlyClient.Market quote=client.quote("RELIANCE");
        if(!Double.isFinite(quote.price)||quote.price<=0)
          throw new IllegalStateException("No usable NSE quote");
        GrowwConnectionHealth.success(this);
        GrowwConnectionHealth.marketTrade(this,quote.lastTradeMs);
        new GrowwCapAuto(this).record(quote);
      }catch(Exception e){
        error=e;GrowwConnectionHealth.failure(this,e);
      }
      final Throwable issue=error;
      main.post(()->{
        if(isFinishing()||isDestroyed())return;
        checking=false;
        message=issue==null?"Groww verified automatically · real read-only API response":
          GrowwConnectionHealth.issue(this)+" · credentials retained";
        draw();
      });
    });
  }
  private void saveWebSearch(){
    String key=webSearchField.getText().toString().trim();
    if(key.isEmpty()){
      message="No change; public official internet sources remain accessible.";
      draw();return;
    }
    if(key.length()<16||key.length()>512){
      Toast.makeText(this,"Search API key must contain 16 to 512 characters",Toast.LENGTH_LONG).show();
      return;
    }
    try{
      secure.put("web_search_api_key",key);
      message="Optional search key encrypted; official NSE research remains available without it.";
      draw();
    }catch(RuntimeException ex){
      Toast.makeText(this,"Could not securely store search key",Toast.LENGTH_LONG).show();
    }
  }
  private void exportLogs(){
    Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.setType("application/zip");intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.putExtra(Intent.EXTRA_TITLE,"MathFormula_v4_Settings_Audit_"+System.currentTimeMillis()+".zip");
    startActivityForResult(intent,REQUEST_EXPORT);
  }
  @Override protected void onActivityResult(int request,int result,Intent intent){
    super.onActivityResult(request,result,intent);
    if(result!=RESULT_OK||intent==null||intent.getData()==null)return;
    if(request==REQUEST_AMFI){
      Uri selected=intent.getData();
      worker.execute(()->{
        String resultText;
        try(java.io.InputStream input=getContentResolver().openInputStream(selected)){
          int n=new AmfiClassification(this).importCsv(input,"AMFI user-imported CSV");
          resultText="Imported "+n+" classifications · source not independently authenticated";
        }catch(Exception e){resultText="AMFI CSV import failed: "+e.getMessage();}
        final String note=resultText;
        main.post(()->{
          if(isFinishing()||isDestroyed())return;
          message=note;Toast.makeText(this,note,Toast.LENGTH_LONG).show();draw();
        });
      });return;
    }
    if(request!=REQUEST_EXPORT)return;
    Uri uri=intent.getData();
    worker.execute(()->{
      String issue=null;
      try{
        DiagnosticExporter.save(getApplicationContext(),uri,new WatchStore(this),
          new ArrayList<String>(),"","");
      }catch(Exception e){issue=e.getClass().getSimpleName();}
      final String problem=issue;
      main.post(()->{
        if(isFinishing()||isDestroyed())return;
        message=problem==null?"Sanitized audit ZIP exported successfully":"Export error: "+problem;
        Toast.makeText(this,message,Toast.LENGTH_LONG).show();draw();
      });
    });
  }
  @Override protected void onDestroy(){
    worker.shutdownNow();super.onDestroy();
  }
}

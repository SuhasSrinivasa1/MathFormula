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
 * Defensive Android-native settings screen for the v4.0.1 hotfix.
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
  private final Handler main=new Handler(Looper.getMainLooper());
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private LinearLayout content;
  private EditText tokenField,secretField,urlField,viewerField;
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
      content.addView(label("MathFormula v4.0.1 · Settings recovery",22,NAVY,true));
      content.addView(label("The Android Keystore could not initialize. No credentials were erased. Restart your phone and reopen Settings, or export a crash log for diagnosis.",14,AMBER,false));
      addButton("Return to MathFormula",this::finish);
    }
  }
  private String backend(){
    return getSharedPreferences("mathformula_shared_runtime_v30",MODE_PRIVATE)
      .getString("server_url","");
  }
  private void draw(){
    if(!initialized||content==null)return;
    content.removeAllViews();
    heading("MathFormula 4.0.1");
    content.addView(label("Settings · read-only live market research",13,MUTED,false));
    content.addView(label(message,12,AMBER,false));
    rule();
    heading("Groww API connection");
    content.addView(label("Enter the Groww Trade API TOTP token and Base32 TOTP secret. Existing encrypted credentials remain saved when these fields are blank.",12,MUTED,false));
    content.addView(label("Saved credentials: "+(secure.configured()?"YES":"NO"),12,NAVY,true));
    tokenField=field("Groww TOTP token · leave blank if saved",true);
    secretField=field("Groww TOTP secret · leave blank if saved",true);
    content.addView(tokenField);content.addView(secretField);
    addButton("Save encrypted credentials",()->saveCredentials(false));
    addButton(checking?"Checking Groww…":"Verify Groww market-data access",()->saveCredentials(true));
    rule();
    heading("Independent NSE scanner");
    content.addView(label("The full Alpha-40 model, off-hours 15:00 freeze and audit workflow require a deployed HTTPS scanner. Groww market quotes alone do not supply all factors.",12,MUTED,false));
    urlField=field("https://scanner.example.com",false);
    urlField.setText(backend());
    viewerField=field("Scanner viewer token · leave blank if saved",true);
    content.addView(urlField);content.addView(viewerField);
    addButton("Save scanner configuration",this::saveBackend);
    rule();
    heading("Diagnostics and notifications");
    content.addView(label(ResearchAlerts.permission(this)?
       "Android notification permission: enabled":"Android notification permission: disabled",12,MUTED,false));
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
    checking=true;message="Checking authorized Groww market-data access…";
    tokenField.setText("");secretField.setText("");
    draw();
    worker.execute(()->{
      String issue="";
      long lastTrade=0;
      try{
        GrowwReadOnlyClient client=new GrowwReadOnlyClient(secure);
        GrowwReadOnlyClient.Market quote=client.quote("RELIANCE");
        if(!Double.isFinite(quote.price)||quote.price<=0)
          throw new IllegalStateException("No usable NSE quote");
        lastTrade=quote.lastTradeMs;
      }catch(SecurityException e){issue="Groww authorization rejected: check API/TOTP approval";}
       catch(Exception e){issue="Groww connection unavailable or rate limited; retry later";}
      final String problem=issue;final long at=lastTrade;
      main.post(()->{
        if(isFinishing()||isDestroyed())return;
        checking=false;
        if(problem.isEmpty()){
          getSharedPreferences("mathformula_groww_connection_v22",MODE_PRIVATE).edit()
            .putLong("groww_verified_at_v22",System.currentTimeMillis())
            .putLong("groww_verified_last_trade_v22",at).apply();
          message="Groww authentication succeeded · read-only price received";
        }else{
          getSharedPreferences("mathformula_groww_connection_v22",MODE_PRIVATE).edit()
            .remove("groww_verified_at_v22").apply();
          message=problem;
        }
        draw();
      });
    });
  }
  private void saveBackend(){
    String raw=urlField.getText().toString().trim().replaceAll("/+$","");
    String token=viewerField.getText().toString().trim();
    try{
      if(!raw.isEmpty()){
        URL parsed=new URL(raw);
        if(!"https".equalsIgnoreCase(parsed.getProtocol()) || parsed.getHost().isEmpty()||
          parsed.getUserInfo()!=null||(!parsed.getPath().isEmpty()&&!"/".equals(parsed.getPath()))||
          parsed.getQuery()!=null||parsed.getRef()!=null)
          throw new IllegalArgumentException("Invalid HTTPS origin");
        String effective=token.isEmpty()?secure.get("server_viewer_token"):token;
        if(effective.length()<32)throw new IllegalArgumentException("Scanner token too short");
        new BackendReadOnlyClient(raw,effective);
      }
      if(!token.isEmpty())secure.put("server_viewer_token",token);
      getSharedPreferences("mathformula_shared_runtime_v30",MODE_PRIVATE).edit()
        .putString("server_url",raw).apply();
      message=raw.isEmpty()?"Scanner connection removed":"HTTPS scanner settings saved (connection not yet verified)";
      draw();
    }catch(Exception error){
      message="Scanner requires an HTTPS domain and a 32+ character viewer token";
      Toast.makeText(this,message,Toast.LENGTH_LONG).show();
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
    if(request!=REQUEST_EXPORT||result!=RESULT_OK||intent==null||intent.getData()==null)return;
    Uri uri=intent.getData();
    worker.execute(()->{
      String issue=null;
      try{
        DiagnosticExporter.save(getApplicationContext(),uri,new WatchStore(this),
          new ArrayList<String>(),backend(),secure.get("server_viewer_token"));
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

package com.mathformula.alpha;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * Major release v4.0: real-data-only NSE research views.
 * No fake fills, no paper trades, no order endpoints, and no synthetic scores.
 * The Groww-powered phone scanner must AUTHOR the 15:00 freeze: it cannot
 * reconstruct a missed 15:00 market state after the fact.
 */
final class MajorReleaseDashboard {
  private static final String PREF="mathformula_major_v40_audit";
  private static final int INK=Color.rgb(20,38,56),MUTED=Color.rgb(100,115,131),
    BLUE=Color.rgb(24,91,154),AMBER=Color.rgb(158,98,19);
  private static final String[] CAPS={"SMALL","MID","LARGE"};
  private static final String[] LABELS={"Small-cap","Mid-cap","Large-cap"};

  private static TextView line(Activity a,String value,int size,int color,boolean bold){
    TextView v=new TextView(a);v.setText(value);v.setTextSize(size);v.setTextColor(color);
    if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
    v.setPadding(0,8,0,8);return v;
  }
  private static void append(Activity a,LinearLayout root,String title,String body){
    root.addView(line(a,title,16,INK,true));root.addView(line(a,body,12,MUTED,false));
  }
  private static boolean isVerifiedCap(JSONObject row,String cap){
    if(row==null || !row.optBoolean("capVerified",false))return false;
    String value=row.optString("amfiCap",row.optString("marketCapBucket","")).toUpperCase(Locale.US);
    return cap.equals(value)|| (cap+"-CAP").equals(value);
  }
  private static String score(JSONObject j,String key,int count){
    int actual=j.optInt(key+"FactorCount",-1);
    double val=j.optDouble(key+"Score",Double.NaN);
    if(actual==count&&Double.isFinite(val)&&val>=0&&val<=100&&j.optBoolean(key+"ScoreVerified",false))
      return String.format(Locale.US,"%.1f/100",val);
    return "N/A (factor evidence missing)";
  }
  static void capGrid(Activity a,LinearLayout root,JSONArray picks,String context){
    root.addView(line(a,context+"  |  Official AMFI buckets",17,BLUE,true));
    root.addView(line(a,"Only independently verified market-cap membership is ranked. The two scores require all 30 / 40 measured factors.",11,AMBER,false));
    for(int c=0;c<3;c++){
      root.addView(line(a,LABELS[c]+"  •  Top 3",15,INK,true));
      int found=0;
      for(int i=0;picks!=null&&i<picks.length()&&found<3;i++){
        JSONObject row=picks.optJSONObject(i);if(!isVerifiedCap(row,CAPS[c]))continue;
        found++;
        String sym=row.optString("symbol","UNKNOWN");
        TextView title=line(a,found+". "+sym+"  ·  "+row.optString("status","WATCH"),13,INK,true);
        root.addView(title);
        root.addView(line(a,"Alpha-30: "+score(row,"alpha30",30)+"    Alpha-40: "+score(row,"alpha40",40),11,MUTED,false));
        root.addView(line(a,"Price timestamp: "+(row.optLong("tradeMs",0)>0?Instant.ofEpochMilli(row.optLong("tradeMs")).atZone(MarketSchedule.IST).toString():"missing"),10,MUTED,false));
      }
      for(int j=found;j<3;j++)
        root.addView(line(a,(j+1)+". UNFILLED · verified AMFI membership / full-NSE score not supplied",12,AMBER,false));
    }
  }
  private static String date(long now){return Instant.ofEpochMilli(now).atZone(MarketSchedule.IST).toLocalDate().toString();}
  private static boolean scannerEvidence(JSONObject snapshot,long now){
    long stamp=snapshot.optLong("scannedAtMs",0);
    if(!snapshot.optBoolean("nseWideComplete",false)||
      !snapshot.optBoolean("amfiJoined",false)||
      !snapshot.optBoolean("factorsVerified",false))return false;
    ZonedDateTime mark=Instant.ofEpochMilli(stamp).atZone(MarketSchedule.IST);
    ZonedDateTime current=Instant.ofEpochMilli(now).atZone(MarketSchedule.IST);
    // Freeze must be produced at decision time and cannot be back-filled from newer data.
    return mark.toLocalDate().equals(current.toLocalDate()) &&
       !mark.toLocalTime().isBefore(LocalTime.of(14,45)) &&
       !mark.toLocalTime().isAfter(LocalTime.of(15,0)) &&
       stamp<=now && now-stamp<=15*60_000L;
  }
  static void nextDay(Activity a,LinearLayout root,JSONObject scanner,JSONArray currentCandidates){
    long now=System.currentTimeMillis();
    ZonedDateTime z=Instant.ofEpochMilli(now).atZone(MarketSchedule.IST);
    String today=z.toLocalDate().toString();
    boolean afterFreeze=!z.toLocalTime().isBefore(LocalTime.of(15,0));
    SharedPreferences prefs=a.getSharedPreferences(PREF,Context.MODE_PRIVATE);
    append(a,root,"Next-Day Picks · 15:00 IST","Live candidate research before 15:00; once frozen, the set is immutable. The 15:00 Groww snapshot must be captured in time and meet all verification rules; no backfill.");
    String storedDate=prefs.getString("freeze_date","");
    JSONArray sealed=new JSONArray();
    if(today.equals(storedDate)){
      try{sealed=new JSONArray(prefs.getString("freeze_rows","[]"));}catch(Exception ignored){}
    }
    // Capture only an observed authoritative freeze in the narrow 15:00 window.
    // A stopped phone must report MISSING, not reconstruct a fabricated freeze.
    boolean closeWindow=afterFreeze && z.toLocalTime().isBefore(LocalTime.of(15,2));
    if(!today.equals(storedDate)&&closeWindow && scannerEvidence(scanner,now) &&
      currentCandidates!=null){
      JSONArray approved=new JSONArray();
      for(int i=0;i<currentCandidates.length();i++){
        JSONObject j=currentCandidates.optJSONObject(i);
        if(j==null||!j.optBoolean("capVerified",false))continue;
        if(j.optInt("alpha30FactorCount",-1)!=30||
           j.optInt("alpha40FactorCount",-1)!=40||
           !j.optBoolean("alpha30ScoreVerified",false)||
           !j.optBoolean("alpha40ScoreVerified",false))continue;
        approved.put(j);
      }
      if(approved.length()>=5){
        prefs.edit().putString("freeze_date",today)
          .putString("freeze_rows",approved.toString())
          .putLong("freeze_at_ms",now).apply();
        sealed=approved;
      }
    }
    if(afterFreeze){
      if(today.equals(prefs.getString("freeze_date",""))){
        root.addView(line(a,"SEALED · "+today+" 15:00 IST · Snapshot stored locally",13,BLUE,true));
        capGrid(a,root,sealed,"Frozen next-session selection");
      }else{
        root.addView(line(a,"FREEZE UNAVAILABLE · No complete verified 15:00 Groww NSE-wide snapshot",13,AMBER,true));
        capGrid(a,root,new JSONArray(),"Unfilled frozen positions");
      }
    }else{
      root.addView(line(a,"LIVE PRE-FREEZE · Current candidates can change until 15:00",13,BLUE,true));
      capGrid(a,root,currentCandidates,"Research candidates before 15:00");
    }
    root.addView(line(a,"The official exchange calendar, post-freeze events and Monday live eligibility must be rechecked. No BUY or trade is inferred by this list.",11,AMBER,false));
  }
  static void closedExtras(Activity a,LinearLayout root,JSONArray closed){
    append(a,root,"Outcome intelligence & remediation","A recorded close is not automatically a winning or losing executed trade. Only verified reference entry, exit and fees can establish P&L.");
    int wins=0,losses=0,unknown=0;
    for(int i=0;closed!=null&&i<closed.length();i++){
      JSONObject j=closed.optJSONObject(i);if(j==null)continue;
      if(!j.optBoolean("outcomeVerified",false)){unknown++;continue;}
      String outcome=j.optString("outcome","");
      if("WIN".equals(outcome))wins++;
      else if("LOSS".equals(outcome))losses++;
      else unknown++;
    }
    root.addView(line(a,"Verified wins: "+wins+"  ·  Verified losses: "+losses+"  ·  Not measurable: "+unknown,13,INK,true));
    final int verifiedLossCount=losses;
    TextView remediate=line(a,"Review losses / remediate  ↗",14,BLUE,true);
    remediate.setGravity(Gravity.CENTER);
    remediate.setPadding(20,22,20,22);
    remediate.setBackgroundTintList(null);
    remediate.setOnClickListener(v->{
      long t=System.currentTimeMillis();
      JSONObject audit=new JSONObject();
      try{
        audit.put("timestampMs",t);
        audit.put("action","REMEDIATE_REQUESTED");
        audit.put("verifiedLossCount",verifiedLossCount);
        audit.put("status","PENDING_EVIDENCE_AND_BACKEND_VALIDATION");
        audit.put("weightsChanged",false);
        audit.put("reason","A single loss cannot justify a permanent scoring change; require causal audit and out-of-sample evidence.");
      }catch(Exception ignored){}
      SharedPreferences p=a.getSharedPreferences(PREF,Context.MODE_PRIVATE);
      p.edit().putString("latest_remediation",audit.toString()).apply();
      Toast.makeText(a,"Remediation review recorded · settings unchanged pending verification",Toast.LENGTH_LONG).show();
    });
    root.addView(remediate);
    root.addView(line(a,"No live weights are rewritten after one loss. Automated remediation requires verified evidence, out-of-sample validation, versioned settings and rollback, none of which are enabled yet.",11,AMBER,false));
  }
}

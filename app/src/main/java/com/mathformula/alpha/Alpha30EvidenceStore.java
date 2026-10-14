package com.mathformula.alpha;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Source-backed frozen PDF Alpha-30 evidence. No default/fabricated factor values.
 * Data-provider adapters may publish CSV records:
 * symbol,factor,value,observed_at_utc,source
 * Source must resolve to a real HTTPS primary/evidence record. Observed timestamp
 * must be the time the information was actually public, not scan/ingestion time.
 * User imported CSV is untrusted until provenance is independently validated.
 */
final class Alpha30EvidenceStore extends SQLiteOpenHelper {
  static final String CONTRACT="Alpha-30-v1.0";
  private static final long DAY=86400000L;
  // Independent TTL per factor; the number is an implementation policy and
  // cannot replace validity checks of financial releases and company events.
  private static final long[] TTL={
    180*DAY,180*DAY,180*DAY,180*DAY,180*DAY,180*DAY,180*DAY,180*DAY,180*DAY,180*DAY,
    3*DAY,45*DAY,45*DAY,45*DAY,45*DAY,60*DAY,90*DAY,
    2*DAY,2*DAY,2*DAY,2*DAY,2*DAY,2*DAY,2*DAY,2*DAY,
    5*60000L,5*60000L,5*60000L,5*60000L,5*60000L
  };
  static {if(TTL.length!=30)throw new AssertionError("30 TTLs");}
  Alpha30EvidenceStore(Context context){
    super(context.getApplicationContext(),"alpha30_sourced_evidence_v27.db",null,1);
  }
  @Override public void onCreate(SQLiteDatabase db){
    db.execSQL("CREATE TABLE factor(symbol TEXT NOT NULL, factor INTEGER NOT NULL, value REAL NOT NULL, "+
      "public_ms INTEGER NOT NULL, source TEXT NOT NULL, PRIMARY KEY(symbol,factor))");
    db.execSQL("CREATE INDEX evidence_public ON factor(public_ms)");
    db.execSQL("CREATE TABLE audit(at_ms INTEGER NOT NULL, accepted INTEGER NOT NULL, "+
      "rejected INTEGER NOT NULL, result TEXT NOT NULL)");
  }
  @Override public void onUpgrade(SQLiteDatabase db,int old,int updated){
    throw new IllegalStateException("Explicit migration required: cannot discard source evidence");
  }
  static final class EvidenceScore {
    final Alpha30Engine.Score overnight;
    final int observed,missing;
    final JSONArray missingNames;
    EvidenceScore(Alpha30Engine.Score overnight,int observed,JSONArray missingNames){
      this.overnight=overnight;this.observed=observed;
      this.missing=29-observed;this.missingNames=missingNames;
    }
    boolean shortlistEligible(){return overnight.coverage>=90 && overnight.value>=68;}
  }
  EvidenceScore overnight(String symbol,long asOf){
    double[] factors=new double[30];
    java.util.Arrays.fill(factors,Double.NaN);
    int n=0;JSONArray missing=new JSONArray();
    try(SQLiteDatabase db=getReadableDatabase();
        Cursor c=db.rawQuery("SELECT factor,value,public_ms,source FROM factor WHERE symbol=?",
            new String[]{symbol})){
      while(c.moveToNext()){
        int index=c.getInt(0)-1;
        if(index<0||index>=29)continue;
        long publicMs=c.getLong(2),age=asOf-publicMs;
        double value=c.getDouble(1);
        String src=c.getString(3);
        if(publicMs<=0||age<0||age>TTL[index]||src==null||!src.startsWith("https://")||
           !Double.isFinite(value)||value<0||value>1)continue;
        factors[index]=value;n++;
      }
    }
    for(int i=0;i<29;i++)if(!Double.isFinite(factors[i]))
      missing.put(new JSONObject().put("factor",i+1).put("name",Alpha30Engine.FACTORS[i])
        .put("weight",Alpha30Engine.WEIGHTS[i]));
    return new EvidenceScore(Alpha30Engine.score(factors,true),n,missing);
  }
  static final class ImportResult {
    int accepted,rejected,staleOrFuture;
    String description(){return accepted+" accepted, "+rejected+
      " rejected; sourced CSV is research evidence, not automatic validation of the underlying filing";}
  }
  ImportResult importEvidence(Context ctx,Uri uri) throws Exception {
    ImportResult result=new ImportResult();
    long now=System.currentTimeMillis();
    // Fail closed: a malformed import must not replace a previously valid set.
    SQLiteDatabase db=getWritableDatabase();
    db.beginTransaction();
    try(InputStream stream=ctx.getContentResolver().openInputStream(uri)){
      if(stream==null)throw new java.io.IOException("Source file unavailable");
      BufferedReader reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8));
      String header=reader.readLine();
      if(header==null||!header.replace("\uFEFF","").trim().equalsIgnoreCase(
            "symbol,factor,value,observed_at_utc,source"))
        throw new IllegalArgumentException("Expected CSV: symbol,factor,value,observed_at_utc,source");
      String line;
      int total=0;
      while((line=reader.readLine())!=null){
        if(++total>140000)throw new IllegalArgumentException("Source import exceeds 140,000 records");
        String[] a=parseFive(line);
        try{
          if(a==null)throw new IllegalArgumentException("Malformed CSV row");
          String symbol=a[0].trim().toUpperCase(Locale.US);
          int factor=Integer.parseInt(a[1].trim());
          double value=Double.parseDouble(a[2].trim());
          long published=OffsetDateTime.parse(a[3].trim()).toInstant().toEpochMilli();
          String source=a[4].trim();
          if(!symbol.matches("[A-Z0-9&-]{1,26}")||factor<1||factor>30||
             !Double.isFinite(value)||value<0||value>1||
             !source.startsWith("https://")||source.length()>1200||
             published<0||published>now){
            throw new IllegalArgumentException("Invalid factor, source or point-in-time");
          }
          ContentValues row=new ContentValues();
          row.put("symbol",symbol);row.put("factor",factor);row.put("value",value);
          row.put("public_ms",published);row.put("source",source);
          // Out-of-order old observations must never overwrite newer point-in-time evidence.
          try(Cursor prior=db.rawQuery("SELECT public_ms FROM factor WHERE symbol=? AND factor=?",
                  new String[]{symbol,String.valueOf(factor)})){
            if(prior.moveToFirst()&&prior.getLong(0)>published){result.rejected++;continue;}
          }
          db.insertWithOnConflict("factor",null,row,SQLiteDatabase.CONFLICT_REPLACE);
          result.accepted++;
        }catch(Exception invalid){result.rejected++;}
      }
      ContentValues audit=new ContentValues();audit.put("at_ms",now);
      audit.put("accepted",result.accepted);audit.put("rejected",result.rejected);
      audit.put("result","CSV import · HTTPS provenance is declared, not automatically verified");
      db.insert("audit",null,audit);db.setTransactionSuccessful();
    }finally{db.endTransaction();}
    return result;
  }
  private static String[] parseFive(String line){
    ArrayList<String> f=new ArrayList<>();StringBuilder value=new StringBuilder();boolean quoted=false;
    for(int i=0;i<line.length();i++){
      char ch=line.charAt(i);
      if(ch=='"'){
        if(quoted&&i+1<line.length()&&line.charAt(i+1)=='"'){value.append('"');i++;}
        else quoted=!quoted;
      }else if(ch==','&&!quoted){f.add(value.toString());value.setLength(0);}
      else value.append(ch);
    }
    f.add(value.toString());
    return f.size()==5?f.toArray(new String[5]):null;
  }
}

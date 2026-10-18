package com.mathformula.alpha;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

/**
 * Cap classification from an explicitly user-imported AMFI export converted to CSV.
 * This is NOT an auto-fetched or independently validated AMFI document.
 * The import is rejected unless at least 251 distinct symbols have valid buckets.
 */
final class AmfiClassification {
  private static final String PREF="mathformula_amfi_cap_v41";
  private final SharedPreferences prefs;
  private Map<String,String> classifications;
  AmfiClassification(Context c){prefs=c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);}
  static String canonical(String s){
    String x=s.trim().toUpperCase(Locale.US);
    if(x.contains("LARGE"))return "LARGE";
    if(x.contains("MID"))return "MID";
    if(x.contains("SMALL"))return "SMALL";
    return "";
  }
  private static ArrayList<String> fields(String line){
    ArrayList<String> out=new ArrayList<>();StringBuilder b=new StringBuilder();boolean quote=false;
    for(int i=0;i<line.length();i++){char c=line.charAt(i);
      if(c=='"'){
        if(quote&&i+1<line.length()&&line.charAt(i+1)=='"'){b.append('"');i++;}
        else quote=!quote;
      }else if(c==','&&!quote){out.add(b.toString().trim());b.setLength(0);}
      else b.append(c);
    }out.add(b.toString().trim());return out;
  }
  int importCsv(InputStream stream,String description)throws Exception{
    if(stream==null)throw new IllegalArgumentException("AMFI CSV stream missing");
    BufferedReader br=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8));
    String header=br.readLine();if(header==null)throw new IllegalArgumentException("Empty AMFI file");
    ArrayList<String> heads=fields(header.replace("\uFEFF",""));int symbol=-1,cap=-1,rank=-1;
    for(int i=0;i<heads.size();i++){
      String h=heads.get(i).trim().toLowerCase(Locale.US).replace(" ","").replace("_","");
      if(h.equals("symbol")||h.equals("nsesymbol")||h.equals("tradingsymbol"))symbol=i;
      if(h.equals("cap")||h.contains("categor")||h.equals("marketcapclass"))cap=i;
      if(h.equals("rank")||h.equals("mcaprank"))rank=i;
    }
    if(symbol<0||(cap<0&&rank<0))throw new IllegalArgumentException(
      "Convert the official AMFI spreadsheet to CSV with columns symbol,category (or symbol,rank)");
    HashMap<String,String> map=new HashMap<>();
    String line;int lines=0;
    while((line=br.readLine())!=null){
      if(++lines>20000)throw new IllegalArgumentException("AMFI file too large");
      ArrayList<String> f=fields(line);if(f.size()<=symbol)continue;
      String sym=f.get(symbol).trim().toUpperCase(Locale.US);
      if(!sym.matches("[A-Z0-9&-]{1,26}"))continue;
      String bucket="";
      if(cap>=0&&f.size()>cap)bucket=canonical(f.get(cap));
      if(bucket.isEmpty()&&rank>=0&&f.size()>rank){
        try{int n=Integer.parseInt(f.get(rank).replaceAll("[^0-9]",""));
          bucket=n>=1&&n<=100?"LARGE":n<=250&&n>=101?"MID":n>250?"SMALL":"";
        }catch(Exception ignored){}
      }
      if(!bucket.isEmpty())map.put(sym,bucket);
    }
    if(map.size()<251)throw new IllegalArgumentException(
      "Classification has only "+map.size()+" symbols; expected a full AMFI categorization");
    JSONObject rows=new JSONObject();for(Map.Entry<String,String> row:map.entrySet())
      rows.put(row.getKey(),row.getValue());
    prefs.edit().putString("rows",rows.toString())
      .putString("origin","USER_IMPORTED_AMFI_CSV_NOT_AUTHENTICATED")
      .putString("description",description==null?"":description.replaceAll("[^A-Za-z0-9._ -]","").substring(0,Math.min(70,description.length())))
      .putLong("importedAtMs",System.currentTimeMillis()).apply();
    classifications=map;return map.size();
  }
  private Map<String,String> all(){
    if(classifications!=null)return classifications;
    classifications=new HashMap<>();
    try{JSONObject d=new JSONObject(prefs.getString("rows","{}"));
      java.util.Iterator<String> names=d.keys();
      while(names.hasNext()){String k=names.next();String c=canonical(d.optString(k));
        if(!c.isEmpty())classifications.put(k,c);
      }
    }catch(Exception ignored){}
    return classifications;
  }
  String bucket(String symbol){return all().getOrDefault(symbol,"");}
  int count(){return all().size();}
  String origin(){return prefs.getString("origin","MISSING");}
  long importedAt(){return prefs.getLong("importedAtMs",0L);}
  JSONObject audit(){
    JSONObject j=new JSONObject();
    try{j.put("classificationRows",count()).put("source",origin())
      .put("importedAtMs",importedAt()).put("sourceAuthenticity","USER_SUPPLIED_NOT_VERIFIED");}
    catch(Exception ignored){}
    return j;
  }
  JSONArray enrich(JSONArray a){
    JSONArray out=new JSONArray();
    for(int i=0;a!=null&&i<a.length();i++){
      JSONObject obj=a.optJSONObject(i);if(obj==null)continue;
      try{
        JSONObject v=new JSONObject(obj.toString());
        String bucket=bucket(v.optString("symbol",""));
        if(!bucket.isEmpty()){
          v.put("amfiCap",bucket);
          v.put("capImported",true);
          v.put("capVerified",false); // User-supplied file provenance cannot be independently authenticated.
          v.put("capSource","AMFI CSV user-imported, unverified");
        }
        out.put(v);
      }catch(Exception ignored){}
    }
    return out;
  }
}

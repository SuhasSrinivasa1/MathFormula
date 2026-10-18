package com.mathformula.alpha;

import android.content.Context;
import android.content.SharedPreferences;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Direct official AMFI XLSX fetch: no CSV conversion or manual document picker.
 * Parser validates symbol, cap class, consistency and publisher before
 * replacing a previously verified six-month categorization.
 */
final class OfficialAmfiUpdater {
  private static final String INDEX="https://www.amfiindia.com/otherdata/categorisation-of-stocks";
  private static final String KNOWN="https://portal.amfiindia.com/spages/AverageMarketCapitalization30Jun2026.xlsx";
  private static final String PREF="mathformula_amfi_auto_v42";
  private static final long INTERVAL=14L*24*3600*1000;
  private final Context context;
  private final SharedPreferences state;
  OfficialAmfiUpdater(Context ctx){
    context=ctx.getApplicationContext();
    state=context.getSharedPreferences(PREF,Context.MODE_PRIVATE);
  }
  String status(){return state.getString("status","Automatic AMFI source has not been synchronized");}
  long lastAttempt(){return state.getLong("lastAttempt",0);}
  boolean due(){
    long now=System.currentTimeMillis();
    return (now-lastAttempt()>INTERVAL ||
      (new AmfiClassification(context).count()<251 && now-lastAttempt()>120_000L));
  }
  private static byte[] fetch(URL url,int cap)throws Exception{
    if(!"https".equalsIgnoreCase(url.getProtocol()))throw new SecurityException("HTTPS required");
    String host=url.getHost().toLowerCase(Locale.US);
    if(!"www.amfiindia.com".equals(host)&&!"portal.amfiindia.com".equals(host))
      throw new SecurityException("Not an official AMFI host");
    HttpURLConnection con=(HttpURLConnection)url.openConnection();
    try{
      con.setConnectTimeout(12000);con.setReadTimeout(24000);
      con.setInstanceFollowRedirects(false);
      con.setRequestProperty("Accept","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/html");
      if(con.getResponseCode()!=200)
        throw new java.io.IOException("AMFI download HTTP "+con.getResponseCode());
      int length=con.getContentLength();
      if(length>cap)throw new java.io.IOException("AMFI download exceeds size budget");
      try(InputStream in=con.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] buf=new byte[8192];int n;
        while((n=in.read(buf))!=-1){
          if(out.size()+n>cap)throw new java.io.IOException("AMFI payload oversized");
          out.write(buf,0,n);
        }
        return out.toByteArray();
      }
    }finally{con.disconnect();}
  }
  private String candidateUrl(){
    try{
      String html=new String(fetch(new URL(INDEX),1_000_000),StandardCharsets.UTF_8)
        .replace("&amp;","&");
      Pattern link=Pattern.compile(
        "(?:https://portal\\.amfiindia\\.com)?/spages/AverageMarketCapitalization[^\"'<>\\s]+\\.xlsx",
        Pattern.CASE_INSENSITIVE);
      Matcher m=link.matcher(html);
      String latest=null;
      while(m.find()){
        String found=m.group();
        if(found.startsWith("/"))found="https://portal.amfiindia.com"+found;
        if(found.matches("(?i)^https://portal\\.amfiindia\\.com/spages/AverageMarketCapitalization[0-9A-Za-z]+\\.xlsx$")){
          if(latest==null)latest=found; // AMFI page lists newest edition first.
        }
      }
      return latest==null?KNOWN:latest;
    }catch(Exception ignored){return KNOWN;}
  }
  private static Document parse(byte[] xml)throws Exception{
    DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    factory.setExpandEntityReferences(false);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
    return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
  }
  private static String text(Element e,String name){
    NodeList nodes=e.getElementsByTagName(name);
    return nodes.getLength()==0?"":nodes.item(0).getTextContent().trim();
  }
  private static ArrayList<String> shared(byte[] xml)throws Exception{
    ArrayList<String> all=new ArrayList<>();
    if(xml==null)return all;
    Document doc=parse(xml);NodeList items=doc.getElementsByTagName("si");
    for(int i=0;i<items.getLength();i++){
      Element e=(Element)items.item(i);StringBuilder b=new StringBuilder();
      NodeList texts=e.getElementsByTagName("t");
      for(int j=0;j<texts.getLength();j++)b.append(texts.item(j).getTextContent());
      all.add(b.toString().trim());
    }
    return all;
  }
  private static String letters(String address){
    if(address==null)return "";
    Matcher m=Pattern.compile("^[A-Z]+").matcher(address);
    return m.find()?m.group():"";
  }
  private static String value(Element e,ArrayList<String> pool){
    String kind=e.getAttribute("t");String raw=text(e,"v");
    if("s".equals(kind)){
      try{int n=Integer.parseInt(raw);return n>=0&&n<pool.size()?pool.get(n):"";}
      catch(Exception ignored){return "";}
    }
    if("inlineStr".equals(kind)){
      NodeList t=e.getElementsByTagName("t");
      return t.getLength()>0?t.item(0).getTextContent().trim():"";
    }
    return raw;
  }
  private static String normalize(String s){
    return s==null?"":s.replaceAll("\\s+"," ").trim().toUpperCase(Locale.US);
  }
  private static Map<String,String> rows(byte[] xml,ArrayList<String> pool)throws Exception{
    HashMap<String,String> mappings=new HashMap<>();
    Document doc=parse(xml);NodeList list=doc.getElementsByTagName("row");
    String nseColumn=null,categoryColumn=null;
    for(int i=0;i<list.getLength();i++){
      Element row=(Element)list.item(i);
      NodeList cells=row.getElementsByTagName("c");
      TreeMap<String,String> fields=new TreeMap<>();
      for(int c=0;c<cells.getLength();c++){
        Element cell=(Element)cells.item(c);
        fields.put(letters(cell.getAttribute("r")),value(cell,pool));
      }
      int rowNum=i+1;
      if(rowNum<=35){
        for(Map.Entry<String,String> entry:fields.entrySet()){
          String x=normalize(entry.getValue());
          if((x.contains("NSE")&&x.contains("SYMBOL"))||
             x.equals("NSE TRADING SYMBOL"))nseColumn=entry.getKey();
          if(x.contains("CATEGORI")||x.contains("CLASSIFICATION AS PER SEBI")||
             x.contains("AS PER SEBI CIRCULAR"))categoryColumn=entry.getKey();
        }
      }
      if(nseColumn==null)continue;
      String symbol=normalize(fields.get(nseColumn)).replaceAll(" ","");
      if(!symbol.matches("[A-Z0-9&-]{1,26}"))continue;
      String cap="";
      if(categoryColumn!=null)
        cap=AmfiClassification.canonical(fields.getOrDefault(categoryColumn,""));
      if(cap.isEmpty()){
        for(String cell:fields.values()){
          String t=normalize(cell);
          if(t.equals("LARGE CAP")||t.equals("MID CAP")||t.equals("SMALL CAP")){
            cap=AmfiClassification.canonical(t);break;
          }
        }
      }
      if(!cap.isEmpty())mappings.put(symbol,cap);
    }
    return mappings;
  }
  private Map<String,String> parseXlsx(byte[] input)throws Exception{
    if(input.length<1000||input[0]!='P'||input[1]!='K')
      throw new java.io.IOException("AMFI file is not an XLSX ZIP");
    byte[] strings=null;
    ArrayList<byte[]> sheets=new ArrayList<>();
    try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(input))){
      ZipEntry entry;
      while((entry=zip.getNextEntry())!=null){
        String name=entry.getName();
        boolean wanted=name.equals("xl/sharedStrings.xml")||
          name.matches("xl/worksheets/sheet[0-9]+\\.xml");
        if(!wanted)continue;
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buf=new byte[8192];int n;
        while((n=zip.read(buf))!=-1){
          if(out.size()+n>35_000_000)throw new java.io.IOException("AMFI sheet too large");
          out.write(buf,0,n);
        }
        if(name.equals("xl/sharedStrings.xml"))strings=out.toByteArray();
        else if(sheets.size()<7)sheets.add(out.toByteArray());
      }
    }
    if(sheets.isEmpty())throw new java.io.IOException("No worksheets in AMFI XLSX");
    ArrayList<String> pool=shared(strings);
    Map<String,String> strongest=new HashMap<>();
    for(byte[] sheet:sheets){
      Map<String,String> found=rows(sheet,pool);
      if(found.size()>strongest.size())strongest=found;
    }
    if(strongest.size()<251)
      throw new java.io.IOException("AMFI XLSX parser found only "+strongest.size()+" classified NSE symbols");
    return strongest;
  }
  synchronized int sync()throws Exception{
    long now=System.currentTimeMillis();
    state.edit().putLong("lastAttempt",now).putString("status","Refreshing official AMFI classification…").apply();
    try{
      String url=candidateUrl();
      Map<String,String> parsed=parseXlsx(fetch(new URL(url),12_000_000));
      int n=new AmfiClassification(context).replaceOfficial(parsed,url,
        "AMFI official six-month capitalisation");
      state.edit().putString("status","Official AMFI classifications synchronized: "+n)
        .putLong("lastSuccess",System.currentTimeMillis()).remove("lastError").apply();
      return n;
    }catch(Exception e){
      state.edit().putString("lastError",e.getClass().getSimpleName())
        .putString("status","Official AMFI download/parser unavailable; keeping previously verified classification").apply();
      throw e;
    }
  }
}

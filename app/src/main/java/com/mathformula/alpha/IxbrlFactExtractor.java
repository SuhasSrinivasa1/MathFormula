package com.mathformula.alpha;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative extraction of raw facts from NSE-hosted inline XBRL HTML.
 *
 * Extracted facts are CANDIDATES, not independently verified company financials:
 * contextRef, reporting period, unit, consolidated basis, taxonomy and filing
 * publication timestamp need validation before computing any Alpha-30 factor.
 * Never writes to Alpha30EvidenceStore or awards score coverage.
 */
final class IxbrlFactExtractor {
  private static final Pattern FACT=Pattern.compile(
    "(?is)<(?:ix:)?nonfraction\\b([^>]{1,1800})>(.{0,2500}?)</(?:ix:)?nonfraction\\s*>");
  private static final Pattern ATTRIBUTE=Pattern.compile(
    "(?i)([a-z][a-z0-9:_-]*)\\s*=\\s*(?:\"([^\"]{0,350})\"|'([^']{0,350})')");
  private static final Pattern TAGS=Pattern.compile("(?is)<[^>]*>");
  private static final int MAX_HTML=250_000;
  private IxbrlFactExtractor(){}

  static String typeOf(String tag){
    String key=tag.replaceAll("[^A-Za-z0-9]","").toLowerCase(Locale.US);
    if(key.contains("revenuefromoperations")||key.contains("revenuefromsale")||
       key.contains("revenuefromcontract")||key.contains("salesofproducts"))
      return "F01/F02 · REVENUE SOURCE";
    if(key.contains("profitloss")||key.contains("profitaftertax")||
       key.contains("profitforperiod")||key.contains("earningpershare"))
      return "F03 · PAT/EPS SOURCE";
    if(key.contains("operatingprofit")||key.contains("ebitda"))
      return "F04/F05 · OPERATING PROFIT SOURCE";
    if(key.contains("netcashflowsfromusedinoperating")||
       key.contains("cashgeneratedfromoperations"))
      return "F07 · OPERATING CASH SOURCE";
    if(key.contains("borrowings")||key.contains("debentures"))
      return "F08 · BORROWINGS SOURCE";
    if(key.contains("sharecapital")||key.contains("numberofshares"))
      return "F10 · SHARE CAPITAL SOURCE";
    return "UNCLASSIFIED XBRL ITEM";
  }
  private static String attribute(String attributes,String key){
    Matcher m=ATTRIBUTE.matcher(attributes);
    while(m.find())if(key.equalsIgnoreCase(m.group(1)))
      return m.group(2)!=null?m.group(2):m.group(3);
    return "";
  }
  static JSONArray extract(String html,String source){
    JSONArray out=new JSONArray();
    if(html==null||html.length()>MAX_HTML||!validNseDocument(source))return out;
    Matcher m=FACT.matcher(html);
    while(m.find()&&out.length()<40){
      String at=m.group(1), name=attribute(at,"name");
      String context=attribute(at,"contextRef"),unit=attribute(at,"unitRef");
      if(name.isEmpty()||context.isEmpty()||unit.isEmpty())continue;
      String raw=TAGS.matcher(m.group(2)).replaceAll("")
        .replace("&nbsp;"," ").replace("&#160;"," ")
        .replace("&minus;","-").replace("−","-").trim();
      boolean bracket=raw.startsWith("(")&&raw.endsWith(")");
      String clean=raw.replace(",","").replace(" ","")
        .replace("(","").replace(")","");
      if(!clean.matches("[+-]?[0-9]{1,18}(?:\\.[0-9]{1,12})?"))continue;
      int scale=0;
      try{String val=attribute(at,"scale");if(!val.isEmpty())scale=Integer.parseInt(val);}
      catch(Exception ex){continue;}
      if(scale< -12||scale>12)continue;
      BigDecimal amount;
      try{
        amount=new BigDecimal(clean).scaleByPowerOfTen(scale);
        if(bracket||"-".equals(attribute(at,"sign")))amount=amount.negate();
      }catch(Exception ex){continue;}
      if(amount.precision()>26)continue;
      try{
        out.put(new JSONObject().put("tag",name)
          .put("contextRef",context).put("unitRef",unit)
          .put("scale",scale).put("value",amount.toPlainString())
          .put("hint",typeOf(name)).put("source",source)
          .put("status","RAW_XBRL_EXTRACTED · ISSUER/PERIOD/BASIS NOT VERIFIED · NOT A SCORE"));
      }catch(Exception ignored){}
    }
    return out;
  }
  static boolean validNseDocument(String url){
    if(url==null)return false;
    try{
      java.net.URI uri=new java.net.URI(url);
      String host=uri.getHost(),path=uri.getRawPath();
      return "https".equalsIgnoreCase(uri.getScheme())&&host!=null&&
        (host.equalsIgnoreCase("nsearchives.nseindia.com")||
         host.equalsIgnoreCase("archives.nseindia.com")) &&
        path!=null&&path.startsWith("/corporate/ixbrl/");
    }catch(Exception ignored){return false;}
  }
}

package com.mathformula.alpha;

import java.util.Locale;

/**
 * Groww documents market_cap as a number in a quote payload. Only parse
 * explicit numbers or strings with known rupee units; never infer crores
 * from an arbitrary small numeric value.
 */
final class GrowwCapParsing {
  static String reason(Object raw){
    if(raw==null||raw==org.json.JSONObject.NULL)return "MISSING_OR_NULL";
    if(raw instanceof Number){
      double value=((Number)raw).doubleValue();
      return !Double.isFinite(value)?"NON_FINITE":
        value<=0?"ZERO_OR_NEGATIVE":"PRESENT_NUMBER";
    }
    String t=String.valueOf(raw).trim();
    if(t.isEmpty()||"null".equalsIgnoreCase(t))return "MISSING_OR_NULL";
    double parsed=parseRupees(raw);
    return Double.isFinite(parsed)&&parsed>0?"PRESENT_NUMERIC_TEXT":"UNPARSABLE_UNIT_OR_VALUE";
  }
  static double parseRupees(Object raw){
    if(raw==null||raw==org.json.JSONObject.NULL)return Double.NaN;
    if(raw instanceof Number){
      double x=((Number)raw).doubleValue();
      return Double.isFinite(x)&&x>0?x:Double.NaN;
    }
    String value=String.valueOf(raw).trim().toLowerCase(Locale.US)
      .replace(",","").replace("₹","").replace("inr","").trim();
    if(value.isEmpty())return Double.NaN;
    double multiplier=1;
    if(value.matches(".*\\s*(crores?|cr)\\.?$")){
      multiplier=10_000_000d;
      value=value.replaceFirst("\\s*(crores?|cr)\\.?$","").trim();
    }else if(value.matches(".*\\s*(lakhs?|lacs?|lac|lk)\\.?$")){
      multiplier=100_000d;
      value=value.replaceFirst("\\s*(lakhs?|lacs?|lac|lk)\\.?$","").trim();
    }else if(value.matches(".*\\s*(billions?|bn)\\.?$")){
      multiplier=1_000_000_000d;
      value=value.replaceFirst("\\s*(billions?|bn)\\.?$","").trim();
    }else if(value.matches(".*\\s*(millions?|mn)\\.?$")){
      multiplier=1_000_000d;
      value=value.replaceFirst("\\s*(millions?|mn)\\.?$","").trim();
    }
    if(!value.matches("[+]?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
      return Double.NaN;
    try{
      double x=Double.parseDouble(value)*multiplier;
      return Double.isFinite(x)&&x>0?x:Double.NaN;
    }catch(Exception ignore){return Double.NaN;}
  }
  private GrowwCapParsing(){}
}

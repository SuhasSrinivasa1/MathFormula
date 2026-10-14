package com.mathformula.alpha;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Restricts outgoing Groww calls to token exchange, quote and historical-candle GETs. NO order endpoints. */
final class GrowwReadOnlyClient {
  private static final String ROOT="https://api.groww.in";
  private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
  private final SecureGrowwStore secrets;
  private long lastAuthAttempt=0;
  private long sessionObtainedAt=0;
  GrowwReadOnlyClient(SecureGrowwStore secrets){this.secrets=secrets;}
  static final class Market {
    String symbol; double price=Double.NaN,change=Double.NaN,vwap=Double.NaN,bid=Double.NaN,ask=Double.NaN;
    double high=Double.NaN,low=Double.NaN,dayVolume=Double.NaN,spreadBps=Double.NaN;
    long lastTradeMs;int fiveCount, fifteenCount;
    double lastFiveClose=Double.NaN, lastFifteenClose=Double.NaN;
    boolean aboveVwap5=false, aboveVwap15=false;
    String note="";
  }
  private JSONObject call(String method,String path,String auth,String body) throws Exception {
    if(!path.startsWith("/v1/live-data/quote?") && !path.startsWith("/v1/historical/candles?")
       && !path.equals("/v1/token/api/access"))throw new SecurityException("Read-only API allowlist");
    HttpURLConnection c=(HttpURLConnection)new URL(ROOT+path).openConnection();
    try{
      c.setRequestMethod(method);c.setConnectTimeout(7000);c.setReadTimeout(9000);
      c.setInstanceFollowRedirects(false);
      c.setRequestProperty("Authorization","Bearer "+auth);
      c.setRequestProperty("Accept","application/json");
      c.setRequestProperty("X-API-VERSION","1.0");
      if(body!=null){
        c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
        try(OutputStream out=c.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}
      }
      int status=c.getResponseCode();
      if(status==401||status==403)throw new SecurityException("Groww authorization rejected (HTTP "+status+")");
      if(status==429)throw new IllegalStateException("Groww rate limit reached (HTTP 429)");
      if(status<200||status>=300)throw new IllegalStateException("Groww HTTP "+status);
      StringBuilder sb=new StringBuilder();
      try(InputStream stream=c.getInputStream();BufferedReader reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){
        String line;while((line=reader.readLine())!=null){sb.append(line);if(sb.length()>1000000)throw new IllegalStateException("Oversized Groww response");}
      }
      JSONObject j=new JSONObject(sb.toString());
      if("FAILURE".equalsIgnoreCase(j.optString("status")))throw new IllegalStateException("Groww API returned FAILURE");
      return j;
    }finally{c.disconnect();}
  }
  private String token() throws Exception {
    String t=secrets.get("access_token");
    java.time.ZonedDateTime now=java.time.ZonedDateTime.now(IST);
    if(!t.isEmpty()&&sessionObtainedAt>0 && now.toInstant().toEpochMilli()-sessionObtainedAt<20L*3600000L
       && now.getHour()!=5)return t;
    if(!secrets.configured())throw new IllegalStateException("Enter Groww TOTP token and secret in Settings");
    if(System.currentTimeMillis()-lastAuthAttempt<60000)throw new IllegalStateException("Authentication cooldown; retry in one minute");
    lastAuthAttempt=System.currentTimeMillis();
    String code=TotpUtil.generate(secrets.get("totp_secret"),System.currentTimeMillis()/1000L);
    JSONObject req=new JSONObject().put("key_type","totp").put("totp",code);
    JSONObject result=call("POST","/v1/token/api/access",secrets.get("totp_token"),req.toString());
    t=result.optString("token","");
    if(t.isEmpty()){
      JSONObject payload=result.optJSONObject("payload");
      if(payload!=null)t=payload.optString("token","");
    }
    if(t.isEmpty())throw new IllegalStateException("Groww did not return an access token");
    secrets.put("access_token",t);sessionObtainedAt=System.currentTimeMillis();
    return t;
  }
  synchronized void resetSession(){sessionObtainedAt=0;secrets.clearSession();}
  private String encoded(String s) throws Exception{return URLEncoder.encode(s,"UTF-8");}
  Market quote(String symbol) throws Exception {
    if(!symbol.matches("[A-Z0-9&-]{1,26}"))throw new IllegalArgumentException("Invalid NSE symbol");
    String path="/v1/live-data/quote?exchange=NSE&segment=CASH&trading_symbol="+encoded(symbol);
    JSONObject json;
    try {json=call("GET",path,token(),null);}
    catch(SecurityException denied){resetSession();throw denied;}
    JSONObject p=json.optJSONObject("payload");
    if(p==null)throw new IllegalStateException("Groww quote payload missing");
    Market m=new Market();m.symbol=symbol;
    m.price=p.optDouble("last_price",Double.NaN);
    m.change=p.optDouble("day_change_perc",Double.NaN);
    m.vwap=p.optDouble("average_price",Double.NaN);
    m.bid=p.optDouble("bid_price",Double.NaN);
    m.ask=p.optDouble("offer_price",Double.NaN);
    m.high=p.optDouble("high_trade_range",Double.NaN);
    m.low=p.optDouble("low_trade_range",Double.NaN);
    m.dayVolume=p.optDouble("volume",Double.NaN);
    m.lastTradeMs=p.optLong("last_trade_time",0);
    if(m.lastTradeMs>0&&m.lastTradeMs<100000000000L)m.lastTradeMs*=1000;
    if(Double.isFinite(m.bid)&&Double.isFinite(m.ask)&&m.bid>0&&m.ask>=m.bid)
      m.spreadBps=(m.ask-m.bid)*10000/((m.ask+m.bid)/2);
    return m;
  }
  void candles(Market m) throws Exception {
    // Groww returns candle start times in IST for some variants; only completed 5-minute bars are considered.
    java.time.ZonedDateTime now=java.time.ZonedDateTime.now(IST);
    LocalDate date=now.toLocalDate();
    if(now.toLocalTime().isBefore(LocalTime.of(9,20))||now.toLocalTime().isAfter(LocalTime.of(16,0)))return;
    String start=date+" 09:15:00", end=now.toLocalDateTime().withNano(0).toString().replace('T',' ');
    String path="/v1/historical/candles?exchange=NSE&segment=CASH&groww_symbol="
      +encoded("NSE-"+m.symbol)+"&start_time="+encoded(start)+"&end_time="+encoded(end)
      +"&candle_interval=5minute";
    JSONObject resp=call("GET",path,token(),null),p=resp.optJSONObject("payload");
    if(p==null)return;
    JSONArray arr=p.optJSONArray("candles");if(arr==null)return;
    List<double[]> bars=new ArrayList<>();
    long nowMs=System.currentTimeMillis();
    for(int i=0;i<arr.length();i++){
      JSONArray a=arr.optJSONArray(i);if(a==null||a.length()<6)continue;
      long millis=readTime(a.opt(0));
      if(millis<=0||millis+300000L>nowMs)continue; // exclude unfinished candles
      double close=a.optDouble(4,Double.NaN);
      if(!Double.isFinite(close))continue;
      bars.add(new double[]{millis,close});
    }
    bars.sort((a,b)->Double.compare(a[0],b[0]));
    m.fiveCount=bars.size();
    if(!bars.isEmpty()){
      m.lastFiveClose=bars.get(bars.size()-1)[1];
      m.aboveVwap5=Double.isFinite(m.vwap)&&m.lastFiveClose>m.vwap;
    }
    if(bars.size()>=3){
      // Require 3 consecutive completed 5-minute candles aligned to a fifteen-minute window.
      int n=bars.size();double[] a=bars.get(n-3),b=bars.get(n-2),c=bars.get(n-1);
      java.time.LocalDateTime t=java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(a[0]),IST);
      if(b[0]-a[0]==300000L&&c[0]-b[0]==300000L&&((t.getHour()*60+t.getMinute())-555)%15==0){
        m.fifteenCount=1;m.lastFifteenClose=c[1];
        m.aboveVwap15=Double.isFinite(m.vwap)&&c[1]>m.vwap;
      }
    }
  }
  private long readTime(Object t){
    if(t instanceof Number){long v=((Number)t).longValue();return v<100000000000L?v*1000:v;}
    try {
      String s=String.valueOf(t).replace('T',' ');
      LocalDateTime l=LocalDateTime.parse(s,java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
      return l.atZone(IST).toInstant().toEpochMilli();
    } catch(Exception ignored){return 0;}
  }
}

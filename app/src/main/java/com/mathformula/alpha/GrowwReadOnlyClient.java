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
  private final java.util.Map<String,String> dailyCacheDate=new java.util.HashMap<>();
  private final java.util.Map<String,Double> dailyCacheHigh=new java.util.HashMap<>();
  private final java.util.Map<String,String> dailyCacheAsOf=new java.util.HashMap<>();
  GrowwReadOnlyClient(SecureGrowwStore secrets){this.secrets=secrets;}
  static final class Market {
    String symbol; double price=Double.NaN,change=Double.NaN,vwap=Double.NaN,bid=Double.NaN,ask=Double.NaN;
    double high=Double.NaN,low=Double.NaN,dayVolume=Double.NaN,spreadBps=Double.NaN;
    long lastTradeMs;int fiveCount, fifteenCount;
    double breakoutLevel=Double.NaN; String referenceAsOf="",referenceSource="";boolean two5m=false;
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
    if(bars.size()>=2 && Double.isFinite(m.breakoutLevel)&&Double.isFinite(m.vwap)){
      int n=bars.size();double[] previous=bars.get(n-2),current=bars.get(n-1);
      m.two5m=current[0]-previous[0]==300000L && previous[1]>m.breakoutLevel && current[1]>m.breakoutLevel && previous[1]>m.vwap && current[1]>m.vwap;
    }
    // Retain the MOST RECENT complete aligned 15-minute candle, even between 15m boundaries.
    for(int n=2;n<bars.size();n++){
      double[] a=bars.get(n-2),b=bars.get(n-1),c=bars.get(n);
      java.time.LocalDateTime t=java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli((long)a[0]),IST);
      if(b[0]-a[0]==300000L&&c[0]-b[0]==300000L&&((t.getHour()*60+t.getMinute())-555)%15==0){
        m.fifteenCount++;m.lastFifteenClose=c[1];
        m.aboveVwap15=Double.isFinite(m.vwap)&&c[1]>m.vwap;
      }
    }
  }
  /**
   * Compute prior 20 completed NSE-session highs using Groww daily history. This is an observed
   * breakout reference, not an estimated profit target or a predicted 80% gain.
   */
  void dailyReference(Market m) throws Exception {
    LocalDate today=LocalDate.now(IST);
    String session=today.toString();
    Double previous=dailyCacheHigh.get(m.symbol);
    if(session.equals(dailyCacheDate.get(m.symbol)) && previous!=null){
      m.breakoutLevel=previous;m.referenceAsOf=dailyCacheAsOf.get(m.symbol);
      m.referenceSource="Groww prior 20 daily candles";return;
    }
    LocalDate start=today.minusDays(58),end=today.minusDays(1);
    String path="/v1/historical/candles?exchange=NSE&segment=CASH&groww_symbol="+encoded("NSE-"+m.symbol)
      +"&start_time="+encoded(start+" 09:15:00")+"&end_time="+encoded(end+" 15:30:00")
      +"&candle_interval=1day";
    JSONObject result=call("GET",path,token(),null),payload=result.optJSONObject("payload");
    JSONArray candles=payload==null?null:payload.optJSONArray("candles");
    if(candles==null)throw new IllegalStateException("No Groww daily candles");
    List<double[]> observed=new ArrayList<>();
    for(int i=0;i<candles.length();i++){
      JSONArray a=candles.optJSONArray(i);if(a==null||a.length()<5)continue;
      long ts=readTime(a.opt(0)); if(ts<=0)continue;
      LocalDate day=Instant.ofEpochMilli(ts).atZone(IST).toLocalDate();
      if(!day.isBefore(today))continue;
      double high=a.optDouble(2,Double.NaN);
      if(Double.isFinite(high)&&high>0)observed.add(new double[]{ts,high});
    }
    observed.sort((a,b)->Double.compare(a[0],b[0]));
    if(observed.size()<20)throw new IllegalStateException("20 completed NSE daily candles unavailable");
    int startIdx=observed.size()-20;double maximum=0;
    for(int i=startIdx;i<observed.size();i++)maximum=Math.max(maximum,observed.get(i)[1]);
    String asOf=Instant.ofEpochMilli((long)observed.get(observed.size()-1)[0]).atZone(IST).toLocalDate().toString();
    dailyCacheDate.put(m.symbol,session);dailyCacheHigh.put(m.symbol,maximum);dailyCacheAsOf.put(m.symbol,asOf);
    m.breakoutLevel=maximum;m.referenceAsOf=asOf;m.referenceSource="Groww prior 20 daily candles";
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

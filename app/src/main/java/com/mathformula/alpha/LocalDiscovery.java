package com.mathformula.alpha;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Best-effort, foreground, READ-ONLY NSE stock research discovery using the phone's existing Groww TOTP session.
 * Discovery rank is NOT frozen Alpha-30 factor score and NEVER produces a BUY signal.
 * Every universe count / validated candidate is real; failed batches remain failures.
 */
final class LocalDiscovery {
  private static final String MASTER="https://growwapi-assets.groww.in/instruments/instrument.csv";
  private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
  private static final String PREF="mathformula_phone_discovery_v16";
  private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
  private final Context app;
  private final SharedPreferences prefs;
  private final SharedPreferences historyCache;
  private final Alpha30EvidenceStore evidenceStore;
  private JSONObject universeDiagnostics=new JSONObject();
  private final Map<String,String> seriesBySymbol=new HashMap<>();
  interface Progress { void update(String message); }
  static final class Broad {
    String symbol;double price,open,high,low,move,location,rank;
  }
  LocalDiscovery(Context c){
    app=c.getApplicationContext();
    prefs=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);
    historyCache=app.getSharedPreferences("mathformula_eod_cache_v31",Context.MODE_PRIVATE);
    evidenceStore=new Alpha30EvidenceStore(app);
    // Upgrade from the v2.5 55-symbol heuristic: preserve prior history but
    // require a fresh v2.6 full-universe pass as soon as the app is opened.
    // Android may kill the process mid-scan; never leave a permanent RUNNING headline.
    // Resumable history journals remain intact for the next allowed research window.
    if(!RUNNING.get() && prefs.getString("lastAttemptState","")
        .startsWith("Scanning NSE equity universe")){
      JSONObject checkpoint=inProgress();
      prefs.edit().putString("lastAttemptState",
        "INTERRUPTED · prior scan stopped after "+checkpoint.optInt("checked",0)+
        "/"+checkpoint.optInt("total",0)+" history checks; resume pending")
        .putString("lastError","Background scan interrupted; no new completed shortlist").apply();
    }
    if(!prefs.getBoolean("v26_migrated",false)){
      prefs.edit().putBoolean("v26_migrated",true)
        .putLong("lastScan",0).putLong("lastAttempt",0)
        .remove("preliminary").remove("scanProgress").apply();
    }
  }
  JSONObject latest(){
    try{return new JSONObject(prefs.getString("latest","{}"));}
    catch(Exception e){return new JSONObject();}
  }
  JSONArray history(){
    try{return new JSONArray(prefs.getString("history","[]"));}
    catch(Exception e){return new JSONArray();}
  }
  long lastScan(){return prefs.getLong("lastScan",0);}
  long lastAttempt(){return prefs.getLong("lastAttempt",0);}
  String lastAttemptState(){return prefs.getString("lastAttemptState","Never scanned");}
  String error(){return prefs.getString("lastError","");}
  JSONObject preview(){
    try{return new JSONObject(prefs.getString("preliminary","{}"));}
    catch(Exception ex){return new JSONObject();}
  }
  JSONObject inProgress(){
    try{return new JSONObject(prefs.getString("scanProgress","{}"));}
    catch(Exception ex){return new JSONObject();}
  }
  private void recordProgress(String stage,int checked,int total,int passed,JSONObject rejects){
    try{
      prefs.edit().putString("scanProgress",new JSONObject()
        .put("stage",stage).put("checked",checked).put("total",total)
        .put("passed",passed).put("rejections",rejects)
        .put("updatedMs",System.currentTimeMillis()).toString()).apply();
    }catch(Exception ignored){}
  }
  private void countFailure(JSONObject reasons,String code){
    try{reasons.put(code,reasons.optInt(code,0)+1);}catch(Exception ignored){}
  }
  private String classify(Exception ex){
    if(ex instanceof SecurityException)return "AUTH_REJECTED";
    String msg=ex.getMessage()==null?"":ex.getMessage().toLowerCase(Locale.US);
    if(msg.contains("429")||msg.contains("rate limit")||msg.contains("cooldown"))return "API_RATE_LIMIT";
    if(msg.contains("historical_response_missing"))return "HISTORICAL_RESPONSE_MISSING";
    if(msg.contains("history_timestamp_or_candle_parse_empty"))return "HISTORICAL_PARSE_OR_EMPTY";
    if(msg.contains("insufficient_completed_sessions")){
      java.util.regex.Matcher found=java.util.regex.Pattern.compile(
        "insufficient_completed_sessions:\\s*(\\d+)").matcher(msg);
      if(found.find()){
        int count=Integer.parseInt(found.group(1));
        return count<20?"UNDER_61_SESSIONS_00_19":
          count<40?"UNDER_61_SESSIONS_20_39":
          count<61?"UNDER_61_SESSIONS_40_60":"SESSION_COUNT_INCONSISTENT";
      }
      return "UNDER_61_SESSIONS_COUNT_UNKNOWN";
    }
    if(msg.contains("eod_last_session_too_old"))return "STALE_LAST_DAILY_CANDLE";
    if(msg.contains("http 4")||msg.contains("http 5"))return "API_HTTP_ERROR";
    if(msg.contains("daily")||msg.contains("candles")||msg.contains("history")||msg.contains("session"))
      return "HISTORY_NOT_AVAILABLE";
    if(msg.contains("timed out")||msg.contains("connection")||msg.contains("network"))
      return "NETWORK_ERROR";
    return "HISTORY_API_OR_PARSE_ERROR";
  }
  private String cacheCutoffDate(){
    java.time.ZonedDateTime z=java.time.ZonedDateTime.now(IST);
    LocalDate day=z.toLocalTime().isBefore(java.time.LocalTime.of(16,30))?
      z.toLocalDate().minusDays(1):z.toLocalDate();
    return NseEquityCalendar.previous(day).toString();
  }
  private GrowwReadOnlyClient.NextSessionHistory historyCached(GrowwReadOnlyClient api,
                                                                String symbol) throws Exception{
    String cutoff=cacheCutoffDate();
    try{
      JSONObject row=new JSONObject(historyCache.getString(symbol,"{}"));
      if(cutoff.equals(row.optString("cutoff"))){
        GrowwReadOnlyClient.NextSessionHistory h=new GrowwReadOnlyClient.NextSessionHistory();
        h.sessionDate=row.getString("sessionDate");
        h.candleMs=row.getLong("candleMs");h.open=row.getDouble("open");
        h.high=row.getDouble("high");h.low=row.getDouble("low");
        h.close=row.getDouble("close");h.volume=row.getDouble("volume");
        h.previousClose=row.getDouble("previousClose");h.priorHigh20=row.getDouble("priorHigh20");h.nextHigh20=row.getDouble("nextHigh20");
        h.avgVolume20=row.getDouble("avgVolume20");h.avgValue20=row.getDouble("avgValue20");
        h.completedSessions=row.getInt("sessions");
        if(h.completedSessions>=61&&h.priorHigh20>0&&h.nextHigh20>0){
          evidenceStore.recordCompletedClv(symbol,h.sessionDate,h.high,h.low,h.close);
          return h;
        }
      }
    }catch(Exception ignored){}
    GrowwReadOnlyClient.NextSessionHistory h=api.nextSessionHistory(symbol);
    JSONObject cache=new JSONObject().put("cutoff",cutoff)
      .put("sessionDate",h.sessionDate).put("candleMs",h.candleMs)
      .put("open",h.open).put("high",h.high).put("low",h.low)
      .put("close",h.close).put("volume",h.volume).put("previousClose",h.previousClose)
      .put("priorHigh20",h.priorHigh20).put("nextHigh20",h.nextHigh20).put("avgValue20",h.avgValue20)
      .put("avgVolume20",h.avgVolume20).put("sessions",h.completedSessions);
    historyCache.edit().putString(symbol,cache.toString()).apply();
    evidenceStore.recordCompletedClv(symbol,h.sessionDate,h.high,h.low,h.close);
    return h;
  }
  static boolean overnightResearchWindow(){
    java.time.LocalTime clock=java.time.Instant.now().atZone(IST).toLocalTime();
    // Freeze the candidate universe before NSE pre-open starts.
    return !clock.isBefore(java.time.LocalTime.of(16,30)) ||
        clock.isBefore(java.time.LocalTime.of(7,30));
  }
  boolean completedForCurrentEod(){
    JSONObject last=latest();
    return "NEXT_SESSION_RESEARCH".equals(last.optString("scanMode")) &&
       cacheCutoffDate().equals(last.optString("researchCutoffDate")) &&
       last.optBoolean("completedFullResearch",false);
  }
  JSONArray partialVerifiedResearch(){
    JSONArray out=new JSONArray();
    JSONObject saved=historyJournal(cacheCutoffDate());
    ArrayList<JSONObject> records=new ArrayList<>();
    java.util.Iterator<String> symbols=saved.keys();
    while(symbols.hasNext()){
      JSONObject entry=saved.optJSONObject(symbols.next());
      JSONObject candidate=entry==null?null:entry.optJSONObject("candidate");
      if(candidate!=null)records.add(candidate);
    }
    records.sort((a,b)->Double.compare(
      b.optDouble("preliminaryRank",0),a.optDouble("preliminaryRank",0)));
    for(int i=0;i<Math.min(3,records.size());i++)out.put(records.get(i));
    return out;
  }
  JSONArray priorityWatchlist(){
    JSONArray saved;
    try{saved=new JSONArray(prefs.getString("overnightPriority50","[]"));}
    catch(Exception ignored){saved=new JSONArray();}
    if(saved.length()>0)return saved;
    // Preserve immediately usable v2.7 provisional names on first v2.8
    // install; these are just 1–3 research leads, not 50 verified stocks.
    JSONArray fallback=preview().optJSONArray("top");
    if(fallback==null||fallback.length()==0)fallback=latest().optJSONArray("top");
    JSONArray out=new JSONArray();
    if(fallback!=null)for(int i=0;i<fallback.length()&&out.length()<50;i++){
      JSONObject candidate=fallback.optJSONObject(i);
      String symbol=candidate==null?"":candidate.optString("symbol","");
      if(symbol.matches("[A-Z0-9&-]{1,26}"))out.put(symbol);
    }
    return out;
  }
  JSONObject priorityLatest(){
    try{return new JSONObject(prefs.getString("priorityLatest","{}"));}
    catch(Exception ignored){return new JSONObject();}
  }
  void refreshPriority(GrowwReadOnlyClient api) throws Exception {
    if(!MarketSchedule.weekdayTradingTime(System.currentTimeMillis()))return;
    JSONArray pool=priorityWatchlist();
    ArrayList<String> list=new ArrayList<>();
    for(int i=0;i<pool.length()&&list.size()<50;i++){
      String sym=pool.optString(i,"");
      if(sym.matches("[A-Z0-9&-]{1,26}")&&!list.contains(sym))list.add(sym);
    }
    if(list.isEmpty())return;
    JSONObject prices=api.batch("ltp",list);
    JSONObject safe=new JSONObject();
    for(String symbol:list){
      double value=prices.optDouble("NSE_"+symbol,Double.NaN);
      if(Double.isFinite(value)&&value>0)safe.put(symbol,value);
    }
    prefs.edit().putString("priorityLatest",new JSONObject()
      .put("observedMs",System.currentTimeMillis())
      .put("requested",list.size()).put("received",safe.length())
      .put("prices",safe).put("note","LTP polling time is NOT exchange trade time and NOT Alpha-30 BUY evidence")
      .toString()).apply();
  }
  private static JSONObject jsonBroad(Broad b) throws Exception {
    return new JSONObject().put("symbol",b.symbol).put("price",b.price)
      .put("open",b.open).put("high",b.high).put("low",b.low)
      .put("move",b.move).put("location",b.location).put("rank",b.rank);
  }
  private static Broad parseBroad(JSONObject j){
    Broad b=new Broad();b.symbol=j.optString("symbol","");
    b.price=j.optDouble("price",Double.NaN);b.open=j.optDouble("open",Double.NaN);
    b.high=j.optDouble("high",Double.NaN);b.low=j.optDouble("low",Double.NaN);
    b.move=j.optDouble("move",Double.NaN);
    b.location=j.optDouble("location",Double.NaN);
    b.rank=j.optDouble("rank",Double.NaN);
    return b;
  }
  private JSONObject historyJournal(String session){
    try{
      if(session.equals(prefs.getString("overnightJournalSession","")))
        return new JSONObject(prefs.getString("overnightJournal","{}"));
    }catch(Exception ignored){}
    return new JSONObject();
  }
  private void persistJournal(String session,JSONObject journal){
    // Android cancellation/restart resumes at the next unscreened candidate.
    prefs.edit().putString("overnightJournalSession",session)
      .putString("overnightJournal",journal.toString()).commit();
  }
  boolean scanning(){return RUNNING.get();}
  private String istDate(long timestamp) {
    return java.time.Instant.ofEpochMilli(timestamp).atZone(IST).toLocalDate().toString();
  }
  /** Most recent completed scan of each NSE session, NOT an exchange-authoritative close. */
  JSONArray sessionSnapshots(){
    try{return new JSONArray(prefs.getString("sessionSnapshots","[]"));}
    catch(Exception e){return new JSONArray();}
  }
  JSONObject lastClosedSession(long now){
    String today=istDate(now);
    java.time.LocalTime clock=java.time.Instant.ofEpochMilli(now)
        .atZone(IST).toLocalTime();
    boolean todayFinished=!clock.isBefore(java.time.LocalTime.of(15,30));
    JSONArray snapshots=sessionSnapshots();
    for(int i=snapshots.length()-1;i>=0;i--){
      JSONObject item=snapshots.optJSONObject(i);
      if(item==null)continue;
      String day=item.optString("sessionDate","");
      if(day.compareTo(today)<0 || (day.equals(today)&&todayFinished))return item;
    }
    return new JSONObject();
  }
  /**
   * Persist a full scan only after every batch has finished.
   * A scan started before the close belongs to that trading session even when final
   * verification finishes after 15:30; all shown quotes retain their own timestamps.
   */
  synchronized void save(JSONObject result){
    try{
      JSONArray previous=latest().optJSONArray("top");
      JSONArray current=result.optJSONArray("top");
      Set<String> seen=new HashSet<>();
      if(current!=null)for(int i=0;i<current.length();i++){
        JSONObject row=current.optJSONObject(i);
        if(row!=null)seen.add(row.optString("symbol"));
      }
      JSONArray timeline=history();
      if(previous!=null){
        for(int i=0;i<previous.length();i++){
          JSONObject old=previous.optJSONObject(i);if(old==null)continue;
          if(!seen.contains(old.optString("symbol"))){
            JSONObject archived=new JSONObject(old.toString());
            archived.put("removedAtMs",System.currentTimeMillis());
            archived.put("reason","Removed from automatic top three after a completed scan");
            timeline.put(archived);
          }
        }
      }
      if(timeline.length()>80){
        JSONArray trimmed=new JSONArray();
        for(int k=timeline.length()-80;k<timeline.length();k++){
          JSONObject record=timeline.optJSONObject(k);
          if(record!=null)trimmed.put(record);
        }
        timeline=trimmed;
      }
      long started=result.optLong("scanStartedMs",result.optLong("scannedAtMs",0));
      String sourceSession=result.optString("sourceSessionDate","");
      String day="NEXT_SESSION_RESEARCH".equals(result.optString("scanMode"))&&
        sourceSession.matches("\\d{4}-\\d{2}-\\d{2}")?sourceSession:istDate(started);
      JSONObject snapshot=new JSONObject(result.toString());
      snapshot.put("sessionDate",day);
      snapshot.put("snapshotKind","LAST_OBSERVED_SESSION_SCAN_NOT_EXCHANGE_CLOSE");
      JSONArray sessions=sessionSnapshots(),updated=new JSONArray();
      for(int i=0;i<sessions.length();i++){
        JSONObject item=sessions.optJSONObject(i);
        if(item!=null&&!day.equals(item.optString("sessionDate")))updated.put(item);
      }
      updated.put(snapshot);
      if(updated.length()>35){
        JSONArray limited=new JSONArray();
        for(int i=updated.length()-35;i<updated.length();i++)limited.put(updated.optJSONObject(i));
        updated=limited;
      }
      android.content.SharedPreferences.Editor update=prefs.edit();
      if(result.optInt("eligibleForResearch",0)>=3)update.remove("preliminary");
      update.putLong("lastAttempt",System.currentTimeMillis())
        .putString("lastAttemptState","Completed · "+result.optInt("universeCount",0)+
            " NSE symbols · "+result.optInt("quotedCount",0)+" prices · "+
            result.optInt("eligibleForResearch",0)+" qualified research candidates")
        .putString("history",timeline.toString())
        .putString("latest",result.toString())
        .putString("sessionSnapshots",updated.toString())
        .putLong("lastScan",result.optLong("scannedAtMs",System.currentTimeMillis()))
        .putString("lastError","")
        .remove("scanProgress").apply();
      // Lifecycle changes only after a successfully persisted, completed scan.
      new RecommendationLedger(app).onCompletedScan(result);
      ResearchAlerts.newShortlist(app,result,previous);
    }catch(Exception ex){failure("Could not preserve completed scan");}
  }
  /** Update displayed quotes without falsely reranking or changing validated breakout references. */
  synchronized void applyQuoteRefresh(Map<String,GrowwReadOnlyClient.Market> recent){
    if(recent==null||recent.isEmpty())return;
    try{
      JSONObject scan=latest();
      JSONArray old=scan.optJSONArray("top");
      if(old==null)return;
      JSONArray fresh=new JSONArray();
      for(int i=0;i<old.length();i++){
        JSONObject item=old.optJSONObject(i);
        if(item==null)continue;
        JSONObject copy=new JSONObject(item.toString());
        GrowwReadOnlyClient.Market market=recent.get(item.optString("symbol"));
        if(market!=null&&Double.isFinite(market.price)&&market.price>0){
          long time=System.currentTimeMillis();
          if(market.lastTradeMs>0&&market.lastTradeMs<=time+3000&&time-market.lastTradeMs<=60_000){
            double reference=copy.optDouble("breakoutLevel",Double.NaN);
            copy.put("price",market.price);
            copy.put("tradeMs",market.lastTradeMs);
            copy.put("observedMs",time);
            if(Double.isFinite(reference)&&reference>0)
              copy.put("distancePct",100*(market.price/reference-1));
            copy.put("volume",Double.isFinite(market.dayVolume)?market.dayVolume:JSONObject.NULL);
            copy.put("averageTradePrice",Double.isFinite(market.vwap)?market.vwap:JSONObject.NULL);
            copy.put("dayChangePct",Double.isFinite(market.change)?market.change:JSONObject.NULL);
            copy.put("quoteUpdatedSinceScan",true);
          }
        }
        fresh.put(copy);
      }
      scan.put("top",fresh);
      scan.put("quoteRefreshMs",System.currentTimeMillis());
      // Do not update scannedAtMs or session snapshots: this was NOT a new full scan.
      prefs.edit().putString("latest",scan.toString()).apply();
      // A fresh quote updates a watch, but never promotes it to a BUY signal.
      new RecommendationLedger(app).onQuoteRefresh(fresh);
    }catch(Exception ignored){}
  }
  void failure(String why){
    new WatchStore(app).audit("DISCOVERY","UNIVERSE","RUNNING","FAILED",safe(why),0);
    prefs.edit()
      .putString("lastAttemptState","Failed · "+safe(why))
      .putString("lastError",safe(why)).apply();
  }
  String exportJson(){
    try{
      return new JSONObject().put("source","Groww phone discovery")
        .put("formulaVersion",Alpha30Engine.VERSION)
        .put("formulaChanged",false)
        .put("lastCompleted",latest()).put("closedSessions",sessionSnapshots())
        .put("previouslyTracked",history())
        .put("preliminaryTopThree",preview())
        .put("overnightResumedCandidates",partialVerifiedResearch())
        .put("overnightPriority50Count",priorityWatchlist().length())
        .put("overnightPriority50Quotes",priorityLatest())
        .put("scanProgress",inProgress())
        .put("lastAttemptMs",lastAttempt())
        .put("lastAttemptState",lastAttemptState())
        .put("lastError",safe(error())).toString(2);
    }catch(Exception ex){return "{}";}
  }
  private static String safe(String s){
    if(s==null)return "";
    return s.length()>150?s.substring(0,150):s;
  }
  /** Share the same validated NSE equity universe with the low-cost intraday radar. */
  List<String> instrumentSymbols(Progress progress) throws Exception {
    return universe(progress);
  }
  private List<String> universe(Progress progress) throws Exception{
    File file=new File(app.getFilesDir(),"groww_nse_eq_master_v16.txt");
    long age=System.currentTimeMillis()-file.lastModified();
    if(!file.exists()||file.length()<8000||age>24L*60*60*1000L){
      progress.update("Updating NSE equity universe from Groww");
      File temp=new File(app.getFilesDir(),"groww_master_tmp_v16");
      HttpURLConnection conn=(HttpURLConnection)new URL(MASTER).openConnection();
      try{
        conn.setRequestMethod("GET");conn.setConnectTimeout(9000);conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(false);conn.setRequestProperty("Accept","text/csv");
        if(conn.getResponseCode()!=200)throw new IOException("Groww instrument list HTTP "+conn.getResponseCode());
        int rows=0;
        try(InputStream in=conn.getInputStream();OutputStream out=new FileOutputStream(temp)){
          byte[] buf=new byte[16*1024];int n;long total=0;
          while((n=in.read(buf))!=-1){
            total+=n;if(total>75_000_000)throw new IOException("Instrument list too large");
            out.write(buf,0,n);rows+=n;
          }
        }
        if(temp.length()<8000)throw new IOException("Groww master contains insufficient data");
        if(file.exists())file.delete();
        if(!temp.renameTo(file))throw new IOException("Could not store verified instrument list");
      }finally{conn.disconnect();if(temp.exists())temp.delete();}
    }
    List<String> stocks=new ArrayList<>();
    seriesBySymbol.clear();
    Set<String> unique=new HashSet<>();
    int nseCash=0,excludedNonEquity=0,excludedSeries=0,excludedFunds=0,missingSymbol=0;
    Map<String,Integer> acceptedSeries=new TreeMap<>();
    try(BufferedReader reader=new BufferedReader(new InputStreamReader(
        new FileInputStream(file),StandardCharsets.UTF_8))){
      String head=reader.readLine();
      if(head==null)throw new IOException("Missing instrument CSV header");
      head=head.replace("\uFEFF","");
      List<String> keys=csv(head);
      int ex=column(keys,"exchange"),seg=column(keys,"segment"),
         series=column(keys,"series"),kind=column(keys,"instrument_type"),
         symbol=column(keys,"trading_symbol"),isin=column(keys,"isin"),
         instrumentName=column(keys,"name");
      if(ex<0||seg<0||series<0||symbol<0)throw new IOException("Groww instrument file schema changed");
      String line;
      while((line=reader.readLine())!=null){
        List<String> row=csv(line);
        if(row.size()<=Math.max(Math.max(ex,seg),Math.max(series,symbol)))continue;
        if(!"NSE".equalsIgnoreCase(row.get(ex).trim())||
           !"CASH".equalsIgnoreCase(row.get(seg).trim()))continue;
        nseCash++;
        String typ=kind>=0&&kind<row.size()?row.get(kind).trim().toUpperCase(Locale.US):"";
        String ser=row.get(series).trim().toUpperCase(Locale.US);
        // This strategy is strictly NSE CASH normal EQ-series listed equities.
        // SME, trade-to-trade BE/BZ, ST, E1 and other series are excluded.
        if(!typ.isEmpty()&&!"EQ".equals(typ)){excludedNonEquity++;continue;}
        if(!"EQ".equals(ser)){
          excludedSeries++;continue;
        }
        String s=row.get(symbol).trim().toUpperCase(Locale.US);
        if(!s.matches("[A-Z0-9&-]{1,26}")){missingSymbol++;continue;}
        String securitiesIsin=isin>=0&&isin<row.size()?row.get(isin).trim().toUpperCase(Locale.US):"";
        String label=instrumentName>=0&&instrumentName<row.size()?
          row.get(instrumentName).trim().toUpperCase(Locale.US):"";
        // Exclude ETF/mutual fund ISINs, even when the series is EQ.
        boolean isFund=securitiesIsin.startsWith("INF")||
          s.endsWith("BEES")||s.endsWith("ETF")||
          label.contains(" ETF ")||label.startsWith("ETF ")||label.endsWith(" ETF")||
          label.contains("EXCHANGE TRADED FUND")||label.contains("EXCHANGE-TRADED FUND");
        if(isFund){excludedFunds++;continue;}
        if(unique.add(s)){
          stocks.add(s);seriesBySymbol.put(s,ser);
          acceptedSeries.put(ser,acceptedSeries.getOrDefault(ser,0)+1);
        }
        if(stocks.size()>8500)throw new IOException("Unexpectedly large NSE universe");
      }
    }
    if(stocks.size()<500)throw new IOException("Groww returned fewer than 500 NSE EQ instruments");
    Collections.sort(stocks);
    JSONObject seriesJson=new JSONObject();
    for(Map.Entry<String,Integer> e:acceptedSeries.entrySet())
      seriesJson.put(e.getKey(),e.getValue());
    universeDiagnostics=new JSONObject()
      .put("nseCashInstrumentRows",nseCash)
      .put("eligibleUniqueEquities",stocks.size())
      .put("excludedNonEquityRows",excludedNonEquity)
      .put("excludedSeriesRows",excludedSeries).put("excludedFundsAndEtfs",excludedFunds)
      .put("invalidSymbolRows",missingSymbol)
      .put("eligibleSeries",seriesJson)
      .put("note","Strict NSE normal EQ series: excludes SME, BE/BZ/ST/E1, ETFs and other instruments. Existing historical journals remain preserved.");
    return stocks;
  }
  private int column(List<String> head,String name){
    for(int i=0;i<head.size();i++)
      if(name.equalsIgnoreCase(head.get(i).trim()))return i;
    return -1;
  }
  /** RFC4180 single-line fields; CSV values may contain embedded commas inside quotes. */
  private List<String> csv(String line){
    List<String> fields=new ArrayList<>();StringBuilder current=new StringBuilder();
    boolean quoted=false;
    for(int i=0;i<line.length();i++){
      char c=line.charAt(i);
      if(c=='"'){
        if(quoted&&i+1<line.length()&&line.charAt(i+1)=='"'){current.append('"');i++;}
        else quoted=!quoted;
      }else if(c==','&&!quoted){fields.add(current.toString());current.setLength(0);}
      else current.append(c);
    }
    fields.add(current.toString());return fields;
  }
  private static double ohlcField(Object value,String field){
    String s=value instanceof JSONObject?((JSONObject)value).optString(field,""):String.valueOf(value);
    if(value instanceof JSONObject)return ((JSONObject)value).optDouble(field,Double.NaN);
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)\\b"+field+"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(s);
    if(!m.find())return Double.NaN;
    try{return Double.parseDouble(m.group(1));}catch(Exception e){return Double.NaN;}
  }
  private static double clamp(double x,double min,double max){return Math.max(min,Math.min(max,x));}
  private static double finite(double x){return Double.isFinite(x)?x:Double.NaN;}
  JSONObject scan(GrowwReadOnlyClient groww,Progress progress) throws Exception{
    return scan(groww,progress,false);
  }
  JSONObject scanForNextSession(GrowwReadOnlyClient groww,Progress progress) throws Exception{
    return scan(groww,progress,true);
  }
  private JSONObject scan(GrowwReadOnlyClient groww,Progress progress,boolean afterHours) throws Exception{
    long scanStarted=System.currentTimeMillis();
    if(afterHours&&!overnightResearchWindow())
      throw new IllegalStateException("Full-universe research paused for market session");
    if(!RUNNING.compareAndSet(false,true))throw new IllegalStateException("Discovery scan already active");
    prefs.edit().putLong("lastAttempt",scanStarted)
      .putString("lastAttemptState","Scanning NSE equity universe…").apply();
    try{
      List<String> universe=universe(progress);
      String researchSession=cacheCutoffDate();
      Map<String,Double> prices=new HashMap<>();
      List<Broad> broad=new ArrayList<>();
      int successfulBatches=0,failedBatches=0;
      boolean broadCacheUsed=false;
      if(afterHours&&prefs.getInt("overnightBroadPolicy",0)==19 &&
         researchSession.equals(prefs.getString("overnightBroadSession",""))){
        try{
          JSONArray saved=new JSONArray(prefs.getString("overnightBroadSnapshot","[]"));
          for(int i=0;i<saved.length();i++)broad.add(parseBroad(saved.getJSONObject(i)));
          broadCacheUsed=!broad.isEmpty()&&prefs.getInt("overnightQuotedCount",0)>0;
          if(broadCacheUsed){
            successfulBatches=prefs.getInt("overnightSuccessfulBatches",0);
            failedBatches=prefs.getInt("overnightFailedBatches",0);
            progress.update("Resume prior "+broad.size()+" broad candidates without full NSE refetch");
          }else broad.clear();
        }catch(Exception ignored){broad.clear();}
      }
      if(!broadCacheUsed){
        Map<String,Object> candles=new HashMap<>();
        for(int offset=0;offset<universe.size();offset+=50){
          if(Thread.currentThread().isInterrupted())throw new InterruptedException("Scan cancelled");
          List<String> group=universe.subList(offset,Math.min(universe.size(),offset+50));
          try{
            JSONObject ltp=groww.batch("ltp",group),ohlc=groww.batch("ohlc",group);
            for(String symbol:group){
              double price=ltp.optDouble("NSE_"+symbol,Double.NaN);
              Object data=ohlc.opt("NSE_"+symbol);
              if(Double.isFinite(price)&&price>0)prices.put(symbol,price);
              if(data!=null)candles.put(symbol,data);
            }
            successfulBatches++;
          }catch(SecurityException auth){throw auth;}
           catch(Exception partial){
             if("API_RATE_LIMIT".equals(classify(partial)))throw partial;
             failedBatches++;
           }
          if(offset%500==0)progress.update("Scanning "+Math.min(offset+50,universe.size())+
            " / "+universe.size()+" NSE equity symbols");
          Thread.sleep(280);
        }
        if(prices.size()<Math.max(200,(int)(universe.size()*.90)))
          throw new IOException("Incomplete Groww NSE price snapshot: "+prices.size()+" / "+universe.size());
        for(String symbol:universe){
          Double price=prices.get(symbol);Object data=candles.get(symbol);
          if(price==null||data==null)continue;
          double op=ohlcField(data,"open"),hi=ohlcField(data,"high"),
            lo=ohlcField(data,"low");
          if(!(op>0&&Double.isFinite(hi)&&Double.isFinite(lo)&&hi>=lo))continue;
          double move=100*(price/op-1);
          if(price<25||price>15000||move<0.7)continue; // strong movers remain in broad discovery (not BUY-eligible)
          double location=clamp((price-lo)/Math.max(.01,hi-lo),0,1);
          if(location<.55)continue;
          Broad item=new Broad();item.symbol=symbol;item.price=price;item.open=op;
          item.high=hi;item.low=lo;item.move=move;item.location=location;
          item.rank=55*clamp(move/7.,0,1)+45*location;
          broad.add(item);
        }
        broad.sort((x,y)->Double.compare(y.rank,x.rank));
        if(afterHours){
          JSONArray saved=new JSONArray();
          for(Broad item:broad)saved.put(jsonBroad(item));
          prefs.edit().putString("overnightBroadSession",researchSession)
            .putString("overnightBroadSnapshot",saved.toString()).putInt("overnightBroadPolicy",19)
            .putInt("overnightQuotedCount",prices.size())
            .putInt("overnightSuccessfulBatches",successfulBatches)
            .putInt("overnightFailedBatches",failedBatches).commit();
        }
      }
      if(afterHours){
        JSONArray top50=new JSONArray();
        for(int i=0;i<Math.min(50,broad.size());i++)top50.put(broad.get(i).symbol);
        prefs.edit().putString("overnightPriority50",top50.toString()).apply();
      }
      // Every eligible NSE stock goes through the broad-data scan BEFORE ranking.
      // Every broad-qualified candidate gets detailed historical verification;
      // there is NO arbitrary 55/50-stock selection cap.
      JSONArray preliminary=new JSONArray();
      for(int i=0;i<Math.min(3,broad.size());i++){
        Broad b=broad.get(i);
        preliminary.put(new JSONObject().put("symbol",b.symbol)
          .put("price",b.price).put("fromOpenPct",b.move)
          .put("preliminaryRank",b.rank)
          .put("status","PRELIMINARY · NOT VERIFIED")
          .put("reason","Groww last-traded snapshot only; 20-session EOD resistance and Alpha-30 not verified")
          .put("tradeMs",0).put("signal","NO SIGNAL"));
      }
      prefs.edit().putString("preliminary",new JSONObject()
        .put("atMs",System.currentTimeMillis()).put("eligibleUniverse",universe.size())
        .put("quotesLoaded",broadCacheUsed?prefs.getInt("overnightQuotedCount",0):prices.size()).put("broadCandidates",broad.size())
        .put("stage","Historical verification in progress")
        .put("top",preliminary).toString()).apply();
      int toVerify=broad.size();int checked=0,valid=0;
      JSONObject rejectionReasons=new JSONObject();
      JSONObject journal=afterHours?historyJournal(researchSession):new JSONObject();
      List<JSONObject> candidates=new ArrayList<>();
      recordProgress("Resumable EOD history verification",0,toVerify,0,rejectionReasons);
      for(int i=0;i<toVerify;i++){
        Broad b=broad.get(i);
        if(afterHours&&!overnightResearchWindow()){
          persistJournal(researchSession,journal);
          recordProgress("Paused before NSE open",checked,toVerify,valid,rejectionReasons);
          throw new IllegalStateException("Overnight research paused before trading opens");
        }
        if(Thread.currentThread().isInterrupted()){
          if(afterHours)persistJournal(researchSession,journal);
          throw new InterruptedException("Interrupted; EOD checkpoint retained");
        }
        checked++;
        if(afterHours){
          JSONObject saved=journal.optJSONObject(b.symbol);
          if(saved!=null){
            JSONObject prior=saved.optJSONObject("candidate");
            if(prior!=null){candidates.add(prior);valid++;}
            else countFailure(rejectionReasons,saved.optString("reject","UNCLASSIFIED"));
            if(checked%10==0)recordProgress("Restored EOD checkpoints",checked,toVerify,valid,rejectionReasons);
            continue;
          }
        }
        if(checked==1||checked%10==0||checked==toVerify){
          progress.update("Verifying NSE daily history "+checked+" / "+toVerify+
            " · "+valid+" validated");
          recordProgress("Historical review",checked,toVerify,valid,rejectionReasons);
        }
        int priorValid=valid;
        JSONObject priorRejects=new JSONObject(rejectionReasons.toString());
        boolean retryLater=false;
        try{
          JSONObject item;
          if(afterHours){
            GrowwReadOnlyClient.NextSessionHistory h=historyCached(groww,b.symbol);
            if(h.avgValue20<10_000_000||h.priorHigh20<=0||h.previousClose<=0){
              countFailure(rejectionReasons,"LIQUIDITY_OR_HISTORY");continue;
            }
            double distance=100*(h.close/h.priorHigh20-1);
            double daily=100*(h.close/h.previousClose-1);
            double location=clamp((h.close-h.low)/Math.max(.01,h.high-h.low),0,1);
            if(distance< -2.5||distance>4.0){
              countFailure(rejectionReasons,"OUTSIDE_BREAKOUT_RANGE");continue;
            }
            if(daily<0.25||location<.55){ // overextended observations are research-only, not automatic BUY
              countFailure(rejectionReasons,"MOMENTUM_OR_CLOSE_STRENGTH");continue;
            }
            double strength=40*clamp(daily/5.,0,1)+30*location+
              30*clamp(1-Math.abs(distance)/4.,0,1);
            item=new JSONObject().put("symbol",b.symbol).put("price",h.close)
              .put("priceKind","LAST_COMPLETED_SESSION_CLOSE_NOT_LIVE")
              .put("priceAsOfSession",h.sessionDate).put("dayChangePct",daily)
              .put("fromOpenPct",100*(h.close/h.open-1))
              .put("breakoutLevel",h.priorHigh20).put("distancePct",distance)
              .put("nseSeries",seriesBySymbol.getOrDefault(b.symbol,"UNKNOWN"))
              .put("stage","NEXT SESSION WATCH").put("preliminaryRank",strength)
              .put("priorDataAsOf",h.sessionDate).put("completedSessions",h.completedSessions)
              .put("approxAverageTradedValue20Inr",h.avgValue20)
              .put("liquidityValueEstimated",true).put("volume",h.volume)
              .put("relativeVolume20",h.avgVolume20>0?h.volume/h.avgVolume20:JSONObject.NULL)
              .put("tradeMs",0).put("dailyCandleMs",h.candleMs)
              .put("observedMs",System.currentTimeMillis())
              .put("status","NEXT_SESSION_RESEARCH").put("signal","NO SIGNAL")
              .put("why","Prior session technical shortlist; complete Alpha-30 and live entry gates pending");
          }else{
            GrowwReadOnlyClient.History h=groww.history60(b.symbol);
            if(h.averageValue20<10_000_000 || h.high20<=0){
              countFailure(rejectionReasons,"LIQUIDITY_OR_HISTORY");continue;
            }
            GrowwReadOnlyClient.Market q=groww.quote(b.symbol);
            long observed=System.currentTimeMillis();
            if(q.lastTradeMs<=0||q.lastTradeMs>observed+3000||
                observed-q.lastTradeMs>60_000L){
              countFailure(rejectionReasons,"STALE_INTRADAY_QUOTE");continue;
            }
            if(!Double.isFinite(q.price)||q.price<=0){
              countFailure(rejectionReasons,"INVALID_PRICE");continue;
            }
            double distance=100*(q.price/h.high20-1);
            if(distance< -2.5||distance>4.0){
              countFailure(rejectionReasons,"OUTSIDE_BREAKOUT_RANGE");continue;
            }
            double daily=100*(q.price/h.previousClose-1);
            if(daily>8.0||daily<0.25){
              countFailure(rejectionReasons,"MOMENTUM_OR_CLOSE_STRENGTH");continue;
            }
            double nearBonus=30*clamp(1-Math.abs(distance)/4.,0,1);
            double strength=40*clamp(daily/5.,0,1)+30*b.location+nearBonus;
            String stage=distance>=0?"BREAKOUT WATCH":distance>=-1.5?"NEAR BREAKOUT":"WATCHING";
            item=new JSONObject()
              .put("symbol",b.symbol).put("price",q.price)
              .put("dayChangePct",daily).put("fromOpenPct",b.move)
              .put("breakoutLevel",h.high20).put("distancePct",distance)
              .put("nseSeries",seriesBySymbol.getOrDefault(b.symbol,"UNKNOWN"))
              .put("stage",stage).put("preliminaryRank",strength)
              .put("priorDataAsOf",h.asOf).put("completedSessions",h.sessions)
              .put("approxAverageTradedValue20Inr",h.averageValue20)
              .put("liquidityValueEstimated",true).put("volume",q.dayVolume)
              .put("averageTradePrice",q.vwap).put("tradeMs",q.lastTradeMs)
              .put("observedMs",observed).put("status","RESEARCH ONLY")
              .put("signal","NO SIGNAL")
              .put("why","30-factor fundamentals, catalysts, macro and execution gates not verified");
          }
          candidates.add(item);valid++;
        }catch(SecurityException auth){
          retryLater=true;
          countFailure(rejectionReasons,"AUTH_REJECTED");throw auth;
        }catch(Exception invalid){
          String code=classify(invalid);
          if("API_RATE_LIMIT".equals(code)||"NETWORK_ERROR".equals(code)||
             "API_HTTP_ERROR".equals(code)||invalid instanceof InterruptedException){
            retryLater=true;
            countFailure(rejectionReasons,code);
            throw invalid;
          }
          countFailure(rejectionReasons,code);
          if("HISTORY_API_OR_PARSE_ERROR".equals(code))
            new WatchStore(app).audit("EOD_HISTORY",b.symbol,"CHECK","REJECTED",
              code+" · "+invalid.getClass().getSimpleName(),0);
        }finally{
          if(afterHours&&!retryLater){
            JSONObject entry=new JSONObject();
            if(valid>priorValid)entry.put("candidate",candidates.get(candidates.size()-1));
            else{
              String reason="UNCLASSIFIED";
              java.util.Iterator<String> codes=rejectionReasons.keys();
              while(codes.hasNext()){
                String name=codes.next();
                if(rejectionReasons.optInt(name)>priorRejects.optInt(name)){reason=name;break;}
              }
              entry.put("reject",reason);
            }
            journal.put(b.symbol,entry);
            if(checked%8==0)persistJournal(researchSession,journal);
          }else if(afterHours)persistJournal(researchSession,journal);
        }
        if(afterHours)Thread.sleep(300);
      }
      if(afterHours)persistJournal(researchSession,journal);
      recordProgress("Complete",checked,toVerify,valid,rejectionReasons);
      if(afterHours && !candidates.isEmpty()){
        // Never mix stale previous-session records with candidates from a newer
        // completed NSE trading session. All displayed EOD watches share a date.
        String latestSession="";
        for(JSONObject candidate:candidates){
          String session=candidate.optString("priceAsOfSession","");
          if(session.compareTo(latestSession)>0)latestSession=session;
        }
        String commonSession=latestSession;
        candidates.removeIf(candidate->
          !commonSession.equals(candidate.optString("priceAsOfSession","")));
        valid=candidates.size();
      }
      candidates.sort((a,b)->Double.compare(
        b.optDouble("preliminaryRank",0),a.optDouble("preliminaryRank",0)));
      // Keep all historically validated research records, not merely the first three,
      // so the PDF overnight factor scorer can rank the complete verified pool.
      JSONArray verifiedPool=new JSONArray();
      for(JSONObject verified:candidates)verifiedPool.put(verified);
      JSONArray top=new JSONArray();
      for(int i=0;i<Math.min(3,candidates.size());i++)top.put(candidates.get(i));
      long now=System.currentTimeMillis();
      JSONObject result=new JSONObject()
         .put("version","on-device-discovery-v2.6")
         .put("modelVersion",Alpha30Engine.VERSION)
         .put("formulaUnchanged",true)
         .put("scannedAtMs",now).put("scanStartedMs",scanStarted).put("universeCount",universe.size())
         .put("quotedCount",broadCacheUsed?prefs.getInt("overnightQuotedCount",0):prices.size()).put("batchesSucceeded",successfulBatches)
         .put("batchesFailed",failedBatches).put("broadCandidates",broad.size())
         .put("dailyHistoryVerified",checked).put("eligibleForResearch",valid)
         .put("universeCoverage",universeDiagnostics)
         .put("verificationRejectReasons",rejectionReasons)
         .put("verificationAttempted",checked)
         .put("historicalVerificationCap",0).put("intradayMoveUpperCutoffRemoved",true)
         .put("normalNseSeriesOnly","EQ").put("legacyHistoryJournalPreserved",true)
         .put("exchangeCalendar",NseEquityCalendar.VERSION)
         .put("researchCutoffDate",afterHours?researchSession:"")
         .put("completedFullResearch",afterHours&&checked==toVerify&&failedBatches==0)
         .put("priorityCount",priorityWatchlist().length())
         .put("resumeCheckpoints",afterHours?journal.length():0)
         .put("scanCompleteness",failedBatches==0?
           "All "+checked+" broad-qualified NSE symbols attempted for historical verification":
           "All broad candidates attempted; "+failedBatches+" price batches failed")
         .put("reason",afterHours?
            "Next-session shortlist from completed daily evidence; not a live BUY":
            "Technical discovery ranking, NOT Alpha-30 BUY")
         .put("scanMode",afterHours?"NEXT_SESSION_RESEARCH":"INTRADAY_RESEARCH")
         .put("priceStatus",afterHours?"LAST_COMPLETED_SESSION_NOT_LIVE":"INTRADAY_OBSERVED")
         .put("sourceSessionDate",afterHours&&top.length()>0?
           top.optJSONObject(0).optString("priceAsOfSession",""):"")
         .put("verifiedTechnicalPool",verifiedPool)
         .put("top",top);
      save(result);
      return result;
    }catch(Exception ex){
       if(ex instanceof InterruptedException||Thread.currentThread().isInterrupted()){
         JSONObject checkpoint=inProgress();
         String reason="PAUSED · Android interrupted background research at "+
           checkpoint.optInt("checked",0)+"/"+checkpoint.optInt("total",0)+
           " history checks. Resumable checkpoints retained.";
         prefs.edit().putString("lastAttemptState",reason).putString("lastError","").apply();
         new WatchStore(app).audit("DISCOVERY","UNIVERSE","RUNNING","PAUSED",reason,0);
         Thread.currentThread().interrupt();
       }else failure(ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage());
       throw ex;
     }
     finally{RUNNING.set(false);}
  }
}
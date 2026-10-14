package com.mathformula.alpha;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;

/** User-requested market-data diagnostic bundle; credentials, storage prefs and device IDs excluded. */
final class DiagnosticExporter {
  private DiagnosticExporter(){}
  static void entry(ZipOutputStream zip,String name,String content) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }
  static JSONObject visibleSnapshot(JSONObject raw) throws Exception {
    JSONObject clean=new JSONObject();
    String[] keys={
      "symbol","price","changePct","reference","referenceAsOf","referenceSource",
      "tradeMs","polledAt","high","low","volume","vwap","spreadBps","fiveCount",
      "fifteenCount","two5m","fifteenConfirmed","aboveVwap5","aboveVwap15",
      "lastFiveClose","lastFifteenClose","stage","gapPct","modelVersion",
      "firstObservedPrice","firstObservedMs","peakObservedPrice",
      "runnerPct","peakRunnerPct","drawdownFromObservedPeakPct",
      "firstSeenAfterTenPct","decision"
    };
    for(String name:keys)if(raw.has(name))clean.put(name,raw.opt(name));
    return clean;
  }
  static JSONObject modelSpec() throws Exception {
    JSONObject model=new JSONObject();
    model.put("modelVersion",Alpha30Engine.VERSION);
    JSONArray factors=new JSONArray();
    for(int i=0;i<Alpha30Engine.WEIGHTS.length;i++){
      factors.put(new JSONObject()
        .put("id",i+1).put("name",Alpha30Engine.FACTORS[i])
        .put("weight",Alpha30Engine.WEIGHTS[i]));
    }
    model.put("factors",factors);
    model.put("overnightNormalizeDenominator",91);
    model.put("overnightShortlistThreshold",68);
    model.put("liveSignalScoreThreshold",78);
    model.put("relativeVolumeGate",1.5);
    model.put("minimumNetRewardRisk",1.25);
    model.put("quoteFreshnessMs",10000);
    model.put("minimumFeatureCoveragePct",90);
    model.put("formulaUnchanged",true);
    model.put("signalCompleteness","Not available from Groww data alone");
    return model;
  }
  static JSONObject localSummary(WatchStore state,List<String> symbols) throws Exception {
    JSONObject summary=new JSONObject();
    summary.put("scannedAtMs",state.lastPoll());
    summary.put("lastScanSuccess",state.lastPollOk());
    summary.put("lastScanIssue",WatchStore.safeExplanation(state.lastError()));
    summary.put("monitorEnabledFlag",state.enabled());
    summary.put("lastPreCloseAtMs",state.lastPreClose());
    summary.put("preCloseSummary",WatchStore.safeExplanation(state.precloseSummary()));
    summary.put("watchedSymbolCount",symbols.size());
    JSONArray saved=new JSONArray();
    for(String sym:symbols){
      JSONObject item=visibleSnapshot(state.get(sym));
      item.put("symbol",sym);
      String earnings=state.earningsDate(sym);
      if(!earnings.isEmpty())item.put("userEnteredUnverifiedEarningsDate",earnings);
      saved.put(item);
    }
    summary.put("snapshots",saved);
    return summary;
  }
  static JSONObject manifest(boolean hasServer,String status,int obsLines,int auditLines) throws Exception{
    JSONObject manifest=new JSONObject();
    manifest.put("exportSchema","MathFormula-diagnostic-v1");
    manifest.put("appRelease","1.6.0");
    manifest.put("formulaVersion",Alpha30Engine.VERSION);
    manifest.put("generatedUtcMs",System.currentTimeMillis());
    manifest.put("hasServerData",hasServer);
    manifest.put("serverDataStatus",status);
    manifest.put("localMarketObservations",obsLines);
    manifest.put("localAuditEvents",auditLines);
    manifest.put("containsSecrets",false);
    manifest.put("credentialsAreNotExported",true);
    manifest.put("limitations","Market observations, not executed trades; full calibrated model still unavailable.");
    return manifest;
  }
  private static int countLines(String s){
    if(s.isEmpty())return 0;
    int n=0;for(int i=0;i<s.length();i++)if(s.charAt(i)=='\n')n++;
    return n;
  }
  static void save(Context ctx,Uri uri,WatchStore state,List<String> symbols,
                   String serverBase,String serverViewerToken) throws Exception {
    String recent=state.readExportJournal("alpha30_market_observations.jsonl",true);
    String events=state.readExportJournal("alpha30_audit.jsonl",false);
    JSONObject serverData=null;
    String serverResult="Not configured";
    if(serverBase!=null&&!serverBase.isEmpty()&&serverViewerToken!=null&&serverViewerToken.length()>=32){
      try{
        BackendReadOnlyClient client=new BackendReadOnlyClient(serverBase,serverViewerToken);
        serverData=client.get("/v1/export");
        serverResult="Read-only server export included";
      }catch(Exception ex){
        serverResult="Unavailable: "+ex.getClass().getSimpleName();
      }
    }
    try(OutputStream file=ctx.getContentResolver().openOutputStream(uri,"w")){
      if(file==null)throw new java.io.IOException("Android refused document writing");
      try(ZipOutputStream zip=new ZipOutputStream(file)){
        entry(zip,"manifest.json",manifest(serverData!=null,serverResult,
          countLines(recent),countLines(events)).toString(2));
        entry(zip,"fixed_formula.json",modelSpec().toString(2));
        entry(zip,"local_watchlist_and_health.json",localSummary(state,symbols).toString(2));
        // Automatic NSE discovery shortlist, coverage and archived promotions; no authentication fields.
        entry(zip,"local_nse_discovery.json",new LocalDiscovery(ctx).exportJson());
        entry(zip,"observations.jsonl",recent);
        entry(zip,"state_changes_and_errors.jsonl",events);
        if(serverData!=null)entry(zip,"server_research_history.json",serverData.toString(2));
        entry(zip,"README.txt",
          "MathFormula diagnostic ZIP · no Groww TOTP token/secret, bearer tokens, account details, "+
          "backend URL or device identifiers are included.\n"+
          "Upload this ZIP to ChatGPT for a POINT-IN-TIME audit of data freshness, "+
          "candidate selection, missed winners and Alpha-30 evidence coverage.\n"+
          "This is not a complete NSE trade tape, a verified 30-factor prediction history "+
          "or proof of winning trades. A formula change requires historical and forward validation, "+
          "and an explicit version change, not automatic tweaking.\n"+
          "observations.jsonl contains bounded on-device observations (rolling files).\n"+
          "server_research_history.json is included only when server connection succeeds.\n");
      }
    }
  }
}

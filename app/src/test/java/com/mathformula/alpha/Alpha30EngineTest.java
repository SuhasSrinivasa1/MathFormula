package com.mathformula.alpha;

import static org.junit.Assert.*;
import org.junit.Test;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;

/** Frozen Alpha-30 safety gates. No broker network or trading actions. */
public class Alpha30EngineTest {
  private static long sessionTime(int hour,int minute){
    return LocalDateTime.of(2026,10,15,hour,minute)
      .atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli();
  }
  private static Alpha30Engine.Snapshot allGates(long now){
    Alpha30Engine.Snapshot s=new Alpha30Engine.Snapshot();
    Arrays.fill(s.factors,1.0);
    s.marketOpen=true;s.tradingAllowed=true;s.materialRiskClear=true;
    s.noOverextension=true;s.marketRegimeClear=true;s.evidenceProvenanceVerified=true;
    s.feeScheduleVerified=true;s.breakout15m=true;s.confirmation5m=true;
    s.vwap5m=true;s.vwap15m=true;s.sectorPositive=true;s.liquidityVerified=true;
    s.lastPrice=104;s.breakoutLevel=100;s.rvol=1.6;s.spreadBps=12;s.netRR=1.30;
    s.quoteTimeMs=now;
    return s;
  }
  @Test public void exactFrozenWeights(){
    assertEquals(30,Alpha30Engine.WEIGHTS.length);
    int total=0;for(int x:Alpha30Engine.WEIGHTS)total+=x;
    assertEquals(100,total);assertEquals(9,Alpha30Engine.WEIGHTS[29]);
  }
  @Test public void overnightExcludesT30AndNormalizes91(){
    double[] x=new double[30];Arrays.fill(x,1.0);x[29]=Double.NaN;
    Alpha30Engine.Score v=Alpha30Engine.score(x,true);
    assertEquals(100.0,v.value,0.00001);assertEquals(100.0,v.coverage,0.00001);
    assertEquals(91.0,Alpha30Engine.score(x,false).coverage,0.00001);
  }
  @Test public void validGatesAloneCanProduceBuyEligible(){
    long now=sessionTime(10,0);
    assertEquals(Alpha30Engine.Decision.BUY_ELIGIBLE,
      Alpha30Engine.evaluate(allGates(now),now).decision);
  }
  @Test public void staleQuoteNeverBuys(){
    long now=sessionTime(10,0);
    Alpha30Engine.Snapshot s=allGates(now);s.quoteTimeMs=now-11000;
    assertNotEquals(Alpha30Engine.Decision.BUY_ELIGIBLE,
      Alpha30Engine.evaluate(s,now).decision);
  }
  @Test public void before930NeverBuys(){
    long now=sessionTime(9,24);
    assertNotEquals(Alpha30Engine.Decision.BUY_ELIGIBLE,
      Alpha30Engine.evaluate(allGates(now),now).decision);
  }
  @Test public void after1445NeverBuys(){
    long now=sessionTime(15,0);
    assertNotEquals(Alpha30Engine.Decision.BUY_ELIGIBLE,
      Alpha30Engine.evaluate(allGates(now),now).decision);
  }
  @Test public void noCompletedBreakoutNeverBuys(){
    long now=sessionTime(10,0);
    Alpha30Engine.Snapshot s=allGates(now);s.breakout15m=false;
    assertNotEquals(Alpha30Engine.Decision.BUY_ELIGIBLE,
      Alpha30Engine.evaluate(s,now).decision);
    s.breakout15m=true;s.confirmation5m=false;
    assertNotEquals(Alpha30Engine.Decision.BUY_ELIGIBLE,
      Alpha30Engine.evaluate(s,now).decision);
  }
  @Test public void missingFeaturesNeverBuy(){
    long now=sessionTime(10,0);
    Alpha30Engine.Snapshot s=allGates(now);
    Arrays.fill(s.factors,Double.NaN);
    assertEquals(0.0,Alpha30Engine.score(s.factors,false).coverage,0.00001);
    assertNotEquals(Alpha30Engine.Decision.BUY_ELIGIBLE,
      Alpha30Engine.evaluate(s,now).decision);
  }
  @Test public void liquidityAndRiskAreHardGates(){
    long now=sessionTime(10,0);
    Alpha30Engine.Snapshot s=allGates(now);s.spreadBps=31;
    assertNotEquals(Alpha30Engine.Decision.BUY_ELIGIBLE,
      Alpha30Engine.evaluate(s,now).decision);
    s.spreadBps=12;s.rvol=1.49;
    assertNotEquals(Alpha30Engine.Decision.BUY_ELIGIBLE,
      Alpha30Engine.evaluate(s,now).decision);
    s.rvol=1.6;s.netRR=1.24;
    assertNotEquals(Alpha30Engine.Decision.BUY_ELIGIBLE,
      Alpha30Engine.evaluate(s,now).decision);
  }
  @Test public void riskOffVetoBlocksBuy(){
    long now=sessionTime(10,0);
    Alpha30Engine.Snapshot s=allGates(now);s.marketRegimeClear=false;
    assertNotEquals(Alpha30Engine.Decision.BUY_ELIGIBLE,
      Alpha30Engine.evaluate(s,now).decision);
  }
}

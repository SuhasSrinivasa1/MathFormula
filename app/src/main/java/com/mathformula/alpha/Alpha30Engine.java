package com.mathformula.alpha;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Immutable research formula; a score is NOT a calibrated win probability. */
public final class Alpha30Engine {
  public static final String VERSION = "Alpha-30-v1.0";
  public static final int[] WEIGHTS = {
      3,2,4,3,3,2,3,3,2,2, 5,5,4,3,2,2,2, 3,4,2,2,2,2,2,2, 5,6,6,5,9
  };
  public static final String[] FACTORS = {
      "Revenue growth YoY", "Revenue acceleration", "Normalized EPS/PAT growth",
      "Operating-margin change", "Operating-profit acceleration", "ROCE / ROA / ROE",
      "Cash-flow conversion", "Leverage / credit quality", "Valuation vs peers and growth",
      "Earnings quality / dilution", "News freshness and materiality",
      "Earnings surprise", "Management guidance revision", "Orders / backlog materiality",
      "Policy / regulatory exposure", "Analyst revisions", "Ownership / governance",
      "Nifty trend and volatility", "Sector relative strength", "Sector breadth",
      "FII/DII flows", "Overnight global indices", "USD/INR exposure",
      "Crude / commodity exposure", "Rates and geopolitical stress",
      "Time-adjusted relative volume", "20-day breakout strength", "Trend / extension",
      "Closing strength", "VWAP, liquidity and net reward/risk"
  };
  static { int sum=0; for(int w:WEIGHTS) sum+=w; if(sum!=100 || FACTORS.length!=30) throw new AssertionError("Formula must have 30 factors totaling 100"); }
  public enum Decision { BUY_ELIGIBLE, WAIT, NO_SIGNAL }
  public static final class Score {
    public final double value, coverage;
    Score(double v,double c){value=v;coverage=c;}
  }
  /** Invalid/missing features contribute ZERO; their weight reduces coverage. */
  public static Score score(double[] f, boolean overnight) {
    if(f==null || f.length!=30) throw new IllegalArgumentException("Exactly 30 factor values");
    int n=overnight?29:30; double total=0, covered=0;
    for(int i=0;i<n;i++) {
      double x=f[i];
      if(Double.isFinite(x) && x>=0 && x<=1){total+=WEIGHTS[i]*x;covered+=WEIGHTS[i];}
    }
    double factor=100.0/(overnight?91:100);
    return new Score(total*factor,covered*factor);
  }
  public static final class Snapshot {
    public String symbol="";
    public long quoteTimeMs=0;
    public boolean marketOpen, tradingAllowed, materialRiskClear;
    public boolean breakout15m, confirmation5m, vwap5m, vwap15m, sectorPositive, liquidityVerified;
    public double lastPrice=Double.NaN, breakoutLevel=Double.NaN, rvol=Double.NaN;
    public double spreadBps=Double.NaN, netRR=Double.NaN;
    public double[] factors=new double[30];
    public Snapshot(){for(int i=0;i<30;i++)factors[i]=Double.NaN;}
  }
  public static final class Result {
    public final Score score;
    public final Decision decision;
    public final List<String> reasons;
    Result(Score s, Decision d, List<String> r){score=s;decision=d;reasons=r;}
  }
  private static boolean atLeast(double a,double b){return Double.isFinite(a)&&a>=b;}
  public static Result evaluate(Snapshot s,long nowMs) {
    Score p=score(s.factors,false);
    List<String> reasons=new ArrayList<>();
    if(!s.marketOpen) reasons.add("Market closed");
    if(s.quoteTimeMs<=0 || s.quoteTimeMs>nowMs+3000 || nowMs-s.quoteTimeMs>10000)
      reasons.add("Missing or stale exchange quote (>10 seconds)");
    if(p.coverage<90) reasons.add(String.format(Locale.US,"Incomplete factor coverage %.0f%%",p.coverage));
    if(!Double.isFinite(s.factors[29])) reasons.add("Missing live execution feature T30");
    if(!reasons.isEmpty()) return new Result(p,Decision.NO_SIGNAL,reasons);
    if(!s.tradingAllowed || !s.materialRiskClear) reasons.add("Instrument or corporate-risk clearance failed");
    if(p.value<78) reasons.add("Alpha-30 score below 78");
    if(!s.breakout15m || !s.confirmation5m || !atLeast(s.lastPrice,s.breakoutLevel))
      reasons.add("Sustained 15-minute breakout / two 5-minute closes missing");
    if(!s.vwap5m || !s.vwap15m) reasons.add("VWAP confirmation missing");
    if(!atLeast(s.rvol,1.5)) reasons.add("RVOL below 1.5 or unavailable");
    if(!s.sectorPositive) reasons.add("Sector breadth/strength gate failed");
    if(!s.liquidityVerified || !Double.isFinite(s.spreadBps) || s.spreadBps>30)
      reasons.add("Liquidity/spread not verified");
    if(!atLeast(s.netRR,1.25)) reasons.add("Net reward/risk below 1.25 or unavailable");
    return new Result(p,reasons.isEmpty()?Decision.BUY_ELIGIBLE:Decision.WAIT,reasons);
  }
}

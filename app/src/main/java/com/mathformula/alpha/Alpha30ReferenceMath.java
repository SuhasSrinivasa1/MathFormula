package com.mathformula.alpha;

/**
 * Raw PDF reference equations, separate from the FROZEN 0–1 normalization.
 * These formulas do not create factor scores without timestamped source data.
 * An actual financial provider and verified sector-specific mappings are required.
 */
final class Alpha30ReferenceMath {
  private Alpha30ReferenceMath(){}
  static double safeRatio(double numerator,double denominator){
    return Double.isFinite(numerator)&&Double.isFinite(denominator)&&
      denominator>0?numerator/denominator:Double.NaN;
  }
  // F01–F10
  static double growthYoY(double latest,double comparable){return safeRatio(latest,comparable)-1;}
  static double acceleration(double currentYoY,double previousYoY){
    return Double.isFinite(currentYoY)&&Double.isFinite(previousYoY)?
      currentYoY-previousYoY:Double.NaN;
  }
  static double operatingMargin(double operatingProfit,double revenue){
    return safeRatio(operatingProfit,revenue);
  }
  static double roce(double ebit,double averageCapital){return safeRatio(ebit,averageCapital);}
  static double roa(double profit,double averageAssets){return safeRatio(profit,averageAssets);}
  static double roe(double profit,double averageEquity){return safeRatio(profit,averageEquity);}
  static double cashConversion(double cfo,double pat){return safeRatio(cfo,pat);}
  static double netDebt(double borrowings,double cash){
    return Double.isFinite(borrowings)&&Double.isFinite(cash)?borrowings-cash:Double.NaN;
  }
  static double interestCoverage(double ebit,double interest){return safeRatio(ebit,interest);}
  static double pe(double marketCap,double trailingEarnings){return safeRatio(marketCap,trailingEarnings);}
  static double evEbitda(double marketCap,double debt,double cash,double ebitda){
    return safeRatio(marketCap+debt-cash,ebitda);
  }
  static double accrualRatio(double pat,double cfo,double assets){
    return safeRatio(pat-cfo,assets);
  }
  static double shareDilution(double sharesLatest,double sharesPrior){
    return safeRatio(sharesLatest,sharesPrior)-1;
  }
  // C11–C17; publication times and source URLs are REQUIRED outside this math layer.
  static double eventMateriality(double economicImpact,double ttmRevenue){
    return safeRatio(economicImpact,ttmRevenue);
  }
  static double earningsSurprise(double actual,double preReleaseConsensus){
    return Double.isFinite(actual)&&Double.isFinite(preReleaseConsensus)&&
      Math.abs(preReleaseConsensus)>1e-12?
      (actual-preReleaseConsensus)/Math.abs(preReleaseConsensus):Double.NaN;
  }
  static double guidanceDelta(double newMid,double oldMid){
    return Double.isFinite(newMid)&&Double.isFinite(oldMid)&&Math.abs(oldMid)>1e-12?
      (newMid-oldMid)/Math.abs(oldMid):Double.NaN;
  }
  static double backlogToSales(double backlog,double revenue){
    return safeRatio(backlog,revenue);
  }
  static double pledgeRatio(double pledgedPromoterShares,double totalPromoterShares){
    return safeRatio(pledgedPromoterShares,totalPromoterShares);
  }
  // M18–M25
  static double sectorRelativeStrength(double sectorReturn,double niftyReturn){
    return Double.isFinite(sectorReturn)&&Double.isFinite(niftyReturn)?
      sectorReturn-niftyReturn:Double.NaN;
  }
  static double breadth(double advancers,double decliners){
    return safeRatio(advancers,advancers+decliners);
  }
  static double netInstitutionalFlow(double purchases,double sales){
    return Double.isFinite(purchases)&&Double.isFinite(sales)?
      purchases-sales:Double.NaN;
  }
  static double currencyExposure(double fxChangeFraction,double netUnhedgedFraction){
    return Double.isFinite(fxChangeFraction)&&Double.isFinite(netUnhedgedFraction)?
      fxChangeFraction*netUnhedgedFraction:Double.NaN;
  }
  static double commodityCostImpact(double commodityChangeFraction,double unhedgedInputShare){
    return Double.isFinite(commodityChangeFraction)&&Double.isFinite(unhedgedInputShare)?
      commodityChangeFraction*unhedgedInputShare:Double.NaN;
  }
  /** Highest high across exactly the prior 20 completed NSE daily sessions. */
  static double completedHigh20(double[] sessionHighs) {
    if(sessionHighs==null||sessionHighs.length<20)return Double.NaN;
    double result=Double.NEGATIVE_INFINITY;
    for(int i=sessionHighs.length-20;i<sessionHighs.length;i++){
      double high=sessionHighs[i];
      if(!Double.isFinite(high)||high<=0)return Double.NaN;
      result=Math.max(result,high);
    }
    return result;
  }
  // T26–T30
  static double timeAdjustedRvol(double currentShares,double normalSharesAtSameTime){
    return safeRatio(currentShares,normalSharesAtSameTime);
  }
  static double closeLocation(double high,double low,double close){
    if(!Double.isFinite(high)||!Double.isFinite(low)||!Double.isFinite(close)||
       high<=low)return Double.NaN;
    return (close-low)/(high-low);
  }
  static double spreadBps(double bid,double ask){
    if(!(bid>0&&ask>=bid))return Double.NaN;
    return 10000.0*(ask-bid)/((ask+bid)/2);
  }
  interface Fees {
    /** Broker + exchange + tax + DP + slippage, for specified delivery order size. */
    double buySide(double grossBuyValue,int shares);
    double sellSide(double grossSellValue,int shares);
  }
  static double netPnl(double entry,double exit,int shares,Fees fees){
    if(shares<=0||!(entry>0&&exit>=0)||fees==null)return Double.NaN;
    double buy=entry*shares,sell=exit*shares;
    double costs=fees.buySide(buy,shares)+fees.sellSide(sell,shares);
    return Double.isFinite(costs)&&costs>=0?sell-buy-costs:Double.NaN;
  }
  /**
   * Numerically solves PDF net +1.5% target and -1.2% stop, not gross targets.
   * A real transaction requires a current, authoritative dated tariff schedule.
   * Costs may vary by exit price, so solve rather than assume fixed percentages.
   */
  static double solveExit(double entry,int shares,double netPrincipalReturn,Fees fees){
    if(shares<=0||entry<=0||fees==null||!Double.isFinite(netPrincipalReturn))
      return Double.NaN;
    double objective=entry*shares*netPrincipalReturn;
    double lo=0,hi=Math.max(entry*2,1);
    for(int attempts=0;attempts<24;attempts++){
      double result=netPnl(entry,hi,shares,fees);
      if(!Double.isFinite(result))return Double.NaN;
      if(result>=objective)break;
      hi*=2;
      if(attempts==23)return Double.NaN;
    }
    if(netPnl(entry,lo,shares,fees)>objective)return Double.NaN;
    for(int i=0;i<85;i++){
      double mid=(lo+hi)/2;
      double got=netPnl(entry,mid,shares,fees);
      if(!Double.isFinite(got))return Double.NaN;
      if(got>=objective)hi=mid;else lo=mid;
    }
    return hi;
  }
  static double targetNet15(double entry,int shares,Fees tariff){
    return solveExit(entry,shares,.015,tariff);
  }
  static double stopNet12(double entry,int shares,Fees tariff){
    return solveExit(entry,shares,-.012,tariff);
  }
  static double rewardRisk(double entry,double target,double stop,int shares,Fees tariff){
    double reward=netPnl(entry,target,shares,tariff);
    double risk=-netPnl(entry,stop,shares,tariff);
    return Double.isFinite(reward)&&Double.isFinite(risk)&&risk>0?
      Math.max(0,reward)/risk:Double.NaN;
  }
}

package com.mathformula.alpha;

import java.util.Arrays;

/** Frozen, versioned score arithmetic. Never impute missing observations. */
public final class DualScoringModel {
  public static final String VERSION="Alpha-40-v2.4-fixed";
  public static final String[] NEW_FACTORS={
    "Gap quality","Opening-range structure","Interval volume acceleration",
    "Stock-relative-to-sector strength","Sector internal participation",
    "Verified catalyst response","VWAP persistence or reclaim",
    "Volatility expansion","Liquidity stability","Exhaustion and circuit risk"
  };
  public static final double[] NEW_WEIGHTS={2,3,3,2,2,2,2,1,1,2};
  public static final class Result {
    public final double alpha30,alpha40,partial30,partial40;
    public final int observed30,observed40;
    public final double covered30,covered40;
    public final boolean full30,full40;
    Result(double a,double b,double p,double q,int n,int m,double c,double d){
      alpha30=a;alpha40=b;partial30=p;partial40=q;
      observed30=n;observed40=m;covered30=c;covered40=d;
      full30=n==30;full40=m==40;
    }
  }
  static{
    int sum=0;for(int w:Alpha30Engine.WEIGHTS)sum+=w;
    double additional=0;for(double w:NEW_WEIGHTS)additional+=w;
    if(sum!=100||additional!=20||NEW_FACTORS.length!=10)
      throw new AssertionError("Dual weights must total 100: (30-factor 80 + 10-factor 20)");
  }
  public static Result evaluate(double[] factor){
    if(factor==null||factor.length!=40)throw new IllegalArgumentException("Expected exactly 40 normalized factors");
    double s30=0,s40=0,cover30=0,cover40=0;int n30=0,n40=0;
    for(int i=0;i<40;i++){
      double x=factor[i];
      if(!Double.isFinite(x)||x<0||x>1)continue;
      if(i<30){
        n30++;s30+=Alpha30Engine.WEIGHTS[i]*x;
        cover30+=Alpha30Engine.WEIGHTS[i];
        s40+=0.8*Alpha30Engine.WEIGHTS[i]*x;
        cover40+=0.8*Alpha30Engine.WEIGHTS[i];
      }else{
        s40+=NEW_WEIGHTS[i-30]*x;
        cover40+=NEW_WEIGHTS[i-30];
      }
      n40++;
    }
    return new Result(n30==30?s30:Double.NaN,n40==40?s40:Double.NaN,
      s30,s40,n30,n40,cover30,cover40);
  }
  public static String factorName(int factor){
    if(factor<1||factor>40)throw new IllegalArgumentException("Factor 1..40 only");
    return factor<=30?Alpha30Engine.FACTORS[factor-1]:NEW_FACTORS[factor-31];
  }
}

package com.mathformula.alpha;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;

/** Sourced Alpha-30 and Alpha-40 score availability. No default factor values. */
final class DualFactorEvidence {
  private final Alpha30EvidenceStore source;
  private static final long DAY=86_400_000L;
  private static final long[] TTL_DAYS={
    180,180,180,180,180,180,180,180,180,180,3,45,45,45,45,60,90,
    2,2,2,2,2,2,2,2,2,2,2,2,0
  };
  DualFactorEvidence(Context c){source=new Alpha30EvidenceStore(c);}
  JSONObject report(String symbol,long asOfMs){
    double[] values=new double[40];Arrays.fill(values,Double.NaN);
    JSONArray missing=new JSONArray(),observed=new JSONArray();
    if(!symbol.matches("[A-Z0-9&-]{1,26}")||asOfMs<=0)return new JSONObject();
    try{
      SQLiteDatabase db=source.getReadableDatabase();
      try(Cursor c=db.rawQuery("SELECT factor,value,public_ms,source FROM factor WHERE symbol=?",
            new String[]{symbol})){
        while(c.moveToNext()){
          int id=c.getInt(0),idx=id-1;
          if(id<1||id>30)continue;
          long published=c.getLong(2),age=asOfMs-published;
          String url=c.getString(3);
          double val=c.getDouble(1);
          long ttl=idx==29?5*60_000L:TTL_DAYS[idx]*DAY;
          if(age<0||age>ttl||url==null||!url.startsWith("https://")
            ||!Double.isFinite(val)||val<0||val>1)continue;
          values[idx]=val;
          observed.put(new JSONObject().put("id",id).put("value",val)
            .put("publishedAtMs",published).put("source",url)
            .put("provenance","DECLARED_NOT_INDEPENDENTLY_VERIFIED"));
        }
      }
    }catch(Exception ignored){}
    DualScoringModel.Result result=DualScoringModel.evaluate(values);
    for(int i=0;i<40;i++)if(!Double.isFinite(values[i]))
      try{missing.put(new JSONObject().put("id",i+1)
        .put("name",DualScoringModel.factorName(i+1))
        .put("weight",i<30?Alpha30Engine.WEIGHTS[i]*(i<30?0.8:1.0):
          DualScoringModel.NEW_WEIGHTS[i-30]));}
      catch(Exception ignored){}
    JSONObject out=new JSONObject();
    try{
      out.put("symbol",symbol).put("asOfMs",asOfMs)
        .put("model30",Alpha30Engine.VERSION).put("model40",DualScoringModel.VERSION)
        .put("alpha30FactorCount",result.observed30)
        .put("alpha40FactorCount",result.observed40)
        .put("alpha30CoveragePct",result.covered30)
        .put("alpha40CoveragePct",result.covered40)
        .put("partialAlpha30",result.partial30)
        .put("partialAlpha40",result.partial40)
        .put("alpha30Score",result.full30?result.alpha30:JSONObject.NULL)
        .put("alpha40Score",result.full40?result.alpha40:JSONObject.NULL)
        .put("alpha30ScoreVerified",false)
        .put("alpha40ScoreVerified",false)
        .put("provenanceNote","Stored HTTPS links are declared references; independent authenticity and observation-time verification not established")
        .put("missingFactors",missing).put("observations",observed);
    }catch(Exception ignored){}
    return out;
  }
  JSONArray reports(JSONArray candidates,long asOf){
    JSONArray data=new JSONArray();
    for(int i=0;candidates!=null&&i<candidates.length()&&i<120;i++){
      JSONObject pick=candidates.optJSONObject(i);if(pick==null)continue;
      data.put(report(pick.optString("symbol",""),asOf));
    }
    return data;
  }
}

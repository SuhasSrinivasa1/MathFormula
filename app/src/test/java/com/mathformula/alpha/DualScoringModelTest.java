package com.mathformula.alpha;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Arrays;

/** Core financial correctness; never promote partial evidence to a complete score. */
public final class DualScoringModelTest {
  @Test public void allFortyFullyPresentScoreHundred(){
    double[] values=new double[40];Arrays.fill(values,1d);
    DualScoringModel.Result r=DualScoringModel.evaluate(values);
    assertTrue(r.full30);assertTrue(r.full40);
    assertEquals(100d,r.alpha30,1e-9);
    assertEquals(100d,r.alpha40,1e-9);
    assertEquals(100d,r.covered30,1e-9);
    assertEquals(100d,r.covered40,1e-9);
  }
  @Test public void noCompleteAlpha40FromAlpha30Only(){
    double[] values=new double[40];Arrays.fill(values,Double.NaN);
    for(int i=0;i<30;i++)values[i]=1d;
    DualScoringModel.Result r=DualScoringModel.evaluate(values);
    assertEquals(30,r.observed30);
    assertTrue(r.full30);assertFalse(r.full40);
    assertEquals(80d,r.partial40,1e-9);
    assertTrue(Double.isNaN(r.alpha40));
  }
  @Test public void missingOneAlpha30FailsClosed(){
    double[] values=new double[40];Arrays.fill(values,1d);values[13]=Double.NaN;
    DualScoringModel.Result r=DualScoringModel.evaluate(values);
    assertFalse(r.full30);assertFalse(r.full40);
    assertTrue(Double.isNaN(r.alpha30));assertTrue(Double.isNaN(r.alpha40));
  }
  @Test public void impossibleFactorsNeverCountTowardCoverage(){
    double[] values=new double[40];Arrays.fill(values,Double.NaN);
    values[0]=1.1d;values[1]=-0.1d;values[2]=1d;
    DualScoringModel.Result r=DualScoringModel.evaluate(values);
    assertEquals(1,r.observed30);assertEquals(1,r.observed40);
    assertEquals(4d,r.partial30,1e-9);
    assertEquals(3.2,r.partial40,1e-9);
  }
  @Test(expected=IllegalArgumentException.class)
  public void incompleteFactorArrayRejected(){
    DualScoringModel.evaluate(new double[30]);
  }
}

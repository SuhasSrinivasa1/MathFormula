package com.mathformula.alpha;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class GrowwCapAutoTest {
  @Test public void rejectsMissingCap(){
    assertEquals("",GrowwCapAuto.estimate(Double.NaN));
    assertEquals("",GrowwCapAuto.estimate(0));
    assertEquals("",GrowwCapAuto.estimate(-100));
  }
  @Test public void labelsAtMarketCapThresholds(){
    assertEquals("SMALL",GrowwCapAuto.estimate(32_000d*10_000_000d));
    assertEquals("MID",GrowwCapAuto.estimate(33_664d*10_000_000d));
    assertEquals("MID",GrowwCapAuto.estimate(100_000d*10_000_000d));
    assertEquals("LARGE",GrowwCapAuto.estimate(106_346d*10_000_000d));
  }
}

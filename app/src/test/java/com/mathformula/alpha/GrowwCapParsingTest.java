package com.mathformula.alpha;

import static org.junit.Assert.*;
import org.junit.Test;
import org.json.JSONObject;

public final class GrowwCapParsingTest {
  @Test public void parsesGrowwNumericRupeeField(){
    assertEquals(5_000_000_000d,GrowwCapParsing.parseRupees(5_000_000_000L),.01);
    assertEquals(5_000_000_000d,GrowwCapParsing.parseRupees("5000000000"),.01);
    assertEquals("PRESENT_NUMBER",GrowwCapParsing.reason(123456L));
  }
  @Test public void parsesOnlyExplicitKnownUnits(){
    assertEquals(123_450_000_000d,GrowwCapParsing.parseRupees("₹12,345 Cr"),.01);
    assertEquals(5_000_000_000d,GrowwCapParsing.parseRupees("500 crore"),.01);
    assertEquals(10_000_000d,GrowwCapParsing.parseRupees("100 lakh"),.01);
  }
  @Test public void refusesAbsentAndUnrecognizedCap(){
    assertTrue(Double.isNaN(GrowwCapParsing.parseRupees(JSONObject.NULL)));
    assertEquals("MISSING_OR_NULL",GrowwCapParsing.reason(JSONObject.NULL));
    assertTrue(Double.isNaN(GrowwCapParsing.parseRupees("not available")));
    assertEquals("UNPARSABLE_UNIT_OR_VALUE",GrowwCapParsing.reason("₹0.5 imaginary-units"));
    assertEquals("ZERO_OR_NEGATIVE",GrowwCapParsing.reason(0));
    assertEquals("",GrowwCapAuto.estimate(Double.NaN));
  }
  @Test public void strictLastTradeTimestampGate(){
    long now=1_800_000_000_000L;
    WatchState.Progress stale=WatchState.decide(105,100,now-11000,now,true,true,true,true);
    assertEquals(WatchState.STALE,stale.stage);
    assertFalse(stale.fresh);
    WatchState.Progress fresh=WatchState.decide(105,100,now-9000,now,true,false,false,false);
    assertEquals(WatchState.CROSSED,fresh.stage);
    assertFalse(fresh.next.contains("BUY"));
  }
}

package com.mathformula.alpha;

import static org.junit.Assert.*;
import java.time.LocalDate;
import org.junit.Test;

public final class NseEquityCalendarTest {
  @Test public void octoberHolidaySkipped(){
    assertTrue(NseEquityCalendar.session(LocalDate.parse("2026-10-19")));
    assertFalse(NseEquityCalendar.session(LocalDate.parse("2026-10-20")));
    assertEquals(LocalDate.parse("2026-10-21"),
      NseEquityCalendar.next(LocalDate.parse("2026-10-19")));
    assertEquals(LocalDate.parse("2026-10-19"),
      NseEquityCalendar.previous(LocalDate.parse("2026-10-20")));
  }
  @Test public void nonTradingDaysNotEligible(){
    assertFalse(NseEquityCalendar.session(LocalDate.parse("2026-11-10")));
    assertFalse(NseEquityCalendar.session(LocalDate.parse("2026-10-18")));
    assertFalse(NseEquityCalendar.session(LocalDate.parse("2027-01-01")));
    assertEquals(LocalDate.parse("2026-10-21"),
      NseEquityCalendar.next(LocalDate.parse("2026-10-20")));
  }
  @Test public void standardSeriesOnly(){
    assertTrue(LocalDiscovery.isNormalCashEquitySeries("EQ"));
    assertTrue(LocalDiscovery.isNormalCashEquitySeries(" eq "));
    for(String series:new String[]{"SM","ST","BE","BZ","E1","ETF",""})
      assertFalse(LocalDiscovery.isNormalCashEquitySeries(series));
  }
}

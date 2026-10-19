package com.mathformula.alpha;

import static org.junit.Assert.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.Test;

public final class ThreePmCaptureWindowTest {
  private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
  private static long when(String day,int hour,int minute,int second) {
    return LocalDate.parse(day).atTime(LocalTime.of(hour,minute,second))
      .atZone(IST).toInstant().toEpochMilli();
  }
  @Test public void exactlyTwoMinuteCaptureWindow(){
    assertFalse(ThreePmCapture.captureWindow(when("2026-10-19",14,59,59)));
    assertTrue(ThreePmCapture.captureWindow(when("2026-10-19",15,0,0)));
    assertTrue(ThreePmCapture.captureWindow(when("2026-10-19",15,1,59)));
    assertFalse(ThreePmCapture.captureWindow(when("2026-10-19",15,2,0)));
  }
  @Test public void neverCaptureExchangeHoliday(){
    assertFalse(ThreePmCapture.captureWindow(when("2026-10-20",15,0,0)));
  }
}

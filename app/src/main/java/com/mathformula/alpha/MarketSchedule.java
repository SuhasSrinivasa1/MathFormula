package com.mathformula.alpha;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Clock scheduling only; exchanges may close on holidays, so a provider calendar is still required. */
final class MarketSchedule {
  static final ZoneId IST=ZoneId.of("Asia/Kolkata");
  static final LocalTime OPEN=LocalTime.of(9,15);
  static final LocalTime CLOSE=LocalTime.of(15,30);
  static boolean weekdayTradingTime(long millis){
    ZonedDateTime z=Instant.ofEpochMilli(millis).atZone(IST);
    DayOfWeek day=z.getDayOfWeek();
    LocalTime time=z.toLocalTime();
    return NseEquityCalendar.session(z.toLocalDate()) &&
      !time.isBefore(OPEN)&&time.isBefore(CLOSE);
  }
  static boolean preClose(long millis){
    LocalTime t=Instant.ofEpochMilli(millis).atZone(IST).toLocalTime();
    return !t.isBefore(LocalTime.of(14,30))&&t.isBefore(CLOSE);
  }
  static boolean checkpoint(long millis) {
    if(!weekdayTradingTime(millis))return false;
    ZonedDateTime z=Instant.ofEpochMilli(millis).atZone(IST);
    int t=z.getHour()*60+z.getMinute();
    return t==14*60+45 || t==15*60 || t==15*60+15 || t==15*60+25;
  }
  static String checkpointLabel(long millis){
    ZonedDateTime z=Instant.ofEpochMilli(millis).atZone(IST);
    return String.format(java.util.Locale.US,"%02d:%02d IST",z.getHour(),z.getMinute());
  }
  static long minutesUntilClose(long millis) {
    ZonedDateTime z=Instant.ofEpochMilli(millis).atZone(IST);
    if(!weekdayTradingTime(millis))return 0;
    return Math.max(0,java.time.Duration.between(z.toLocalTime(),CLOSE).toMinutes());
  }
  static boolean dueSoon(String isoDate,long millis) {
    if(isoDate==null||isoDate.isEmpty())return false;
    try{
      java.time.LocalDate event=java.time.LocalDate.parse(isoDate);
      java.time.LocalDate today=Instant.ofEpochMilli(millis).atZone(IST).toLocalDate();
      long days=java.time.temporal.ChronoUnit.DAYS.between(today,event);
      return days>=0&&days<=5;
    }catch(Exception e){return false;}
  }
  static boolean isCurrentQuote(long quoteTime,long now){
    return quoteTime>0&&now>=quoteTime&&now-quoteTime<=10000L;
  }
}

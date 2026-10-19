package com.mathformula.alpha;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * NSE regular equity trading calendar. 2026 dates sourced from NSE's official
 * 2026 equities holiday list (including 15 Jan Maharashtra local-election closure).
 * Special Muhurat session is NOT a regular cash session.
 *
 * Fail closed for freeze eligibility outside published 2026 calendar coverage.
 * Full updated 2027 calendar is required when year changes.
 */
final class NseEquityCalendar {
  static final String SOURCE="https://www.nseindia.com/resources/exchange-communication-holidays";
  static final String VERSION="NSE_EQUITIES_2026_PUBLIC_HOLIDAYS";
  private static final Set<String> HOLIDAYS_2026=new HashSet<>(Arrays.asList(
    "2026-01-15","2026-01-26","2026-03-03","2026-03-26","2026-03-31",
    "2026-04-03","2026-04-14","2026-05-01","2026-05-28","2026-06-26",
    "2026-09-14","2026-10-02","2026-10-20","2026-11-10","2026-11-24",
    "2026-12-25"
  ));
  static boolean supported(LocalDate d){return d!=null&&d.getYear()==2026;}
  static boolean session(LocalDate d){
    if(d==null||!supported(d))return false;
    DayOfWeek w=d.getDayOfWeek();
    return w!=DayOfWeek.SATURDAY&&w!=DayOfWeek.SUNDAY&&!HOLIDAYS_2026.contains(d.toString());
  }
  static LocalDate previous(LocalDate day){
    if(day==null||!supported(day))throw new IllegalStateException("NSE regular cash calendar unavailable for date");
    LocalDate candidate=day;
    for(int n=0;n<10;n++,candidate=candidate.minusDays(1))
      if(session(candidate))return candidate;
    throw new IllegalStateException("Unable to resolve last NSE trading date");
  }
  static LocalDate next(LocalDate day){
    if(day==null||!supported(day))throw new IllegalStateException("NSE regular cash calendar unavailable for date");
    LocalDate candidate=day.plusDays(1);
    for(int n=0;n<10;n++,candidate=candidate.plusDays(1))
      if(session(candidate))return candidate;
    throw new IllegalStateException("Unable to resolve next NSE regular trading date");
  }
  static String sessionAvailability(LocalDate day){
    if(!supported(day))return "CALENDAR_NOT_VERIFIED_FOR_YEAR";
    return session(day)?"OPEN_REGULAR_SESSION":"CLOSED_HOLIDAY_OR_WEEKEND";
  }
  private NseEquityCalendar(){}
}

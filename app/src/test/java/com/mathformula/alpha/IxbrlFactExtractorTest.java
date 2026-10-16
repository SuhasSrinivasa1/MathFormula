package com.mathformula.alpha;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class IxbrlFactExtractorTest {
  private static final String SOURCE=
    "https://nsearchives.nseindia.com/corporate/ixbrl/results-2026.htm";

  @Test public void preservesContextUnitsAndScaleWithoutCreditingFactor() throws Exception{
    String html="<html><ix:nonFraction name='in-gaap:RevenueFromOperations' "+
      "contextRef='FY2026Q2Consolidated' unitRef='INR' scale='6' decimals='0'>"+
      "1,234.56</ix:nonFraction></html>";
    JSONArray facts=IxbrlFactExtractor.extract(html,SOURCE);
    assertEquals(1,facts.length());
    JSONObject row=facts.getJSONObject(0);
    assertEquals("1234560000",row.getString("value"));
    assertEquals("FY2026Q2Consolidated",row.getString("contextRef"));
    assertEquals("INR",row.getString("unitRef"));
    assertTrue(row.getString("hint").contains("F01"));
    assertTrue(row.getString("status").contains("NOT A SCORE"));
  }
  @Test public void negativeAmountsAndBrokenRowsNeverCreateScores() throws Exception{
    String html="<ix:nonFraction name=\"a:ProfitLoss\" contextRef=\"Current\" "+
      "unitRef=\"INR\" sign=\"-\" scale=\"3\">230</ix:nonFraction>"+
      "<ix:nonFraction name='a:RevenueFromOperations' contextRef='Current' "+
      "scale='4'>99</ix:nonFraction>";
    JSONArray facts=IxbrlFactExtractor.extract(html,SOURCE);
    assertEquals(1,facts.length());
    assertEquals("-230000",facts.getJSONObject(0).getString("value"));
  }
  @Test public void rejectNonOfficialHostsAndOversize(){
    String html="<ix:nonFraction name='x:RevenueFromOperations' contextRef='q' "+
      "unitRef='INR'>90</ix:nonFraction>";
    assertEquals(0,IxbrlFactExtractor.extract(html,
      "https://nsearchives.nseindia.com.evil.example/corporate/ixbrl/doc.html").length());
    assertEquals(0,IxbrlFactExtractor.extract(html,"file:///etc/passwd").length());
    assertEquals(0,IxbrlFactExtractor.extract("x".repeat(250001),SOURCE).length());
  }
  @Test public void rejectsUnmappedNameAndFalseHost(){
    assertFalse(IxbrlFactExtractor.validNseDocument(
      "https://nsearchives.nseindia.com.evil.example/corporate/ixbrl/2026.htm"));
    assertEquals("UNCLASSIFIED XBRL ITEM",IxbrlFactExtractor.typeOf("abc:SomeRandomFact"));
    assertEquals(0,IxbrlFactExtractor.extract("nothing",SOURCE).length());
  }
}

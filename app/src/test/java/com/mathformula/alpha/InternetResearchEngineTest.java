package com.mathformula.alpha;

import static org.junit.Assert.*;
import org.junit.Test;
import org.json.JSONArray;
import java.util.List;

public class InternetResearchEngineTest {
  @Test public void frozenFactorRegistryHasExactThirtyMappings(){
    assertEquals(30,FactorSourceCatalog.SOURCE.length);
    assertEquals(30,FactorSourceCatalog.URL.length);
    assertEquals(30,Alpha30Engine.WEIGHTS.length);
    for(int i=1;i<=30;i++){
      assertTrue(FactorSourceCatalog.sourceFor(i).length()>12);
      assertTrue(FactorSourceCatalog.link(i).startsWith("https://"));
    }
    assertTrue(FactorSourceCatalog.consensusRequired(12));
    assertTrue(FactorSourceCatalog.consensusRequired(16));
    assertFalse(FactorSourceCatalog.consensusRequired(3));
  }
  @Test public void queryPlannerDeduplicatesAndCapsWork(){
    JSONArray symbols=new JSONArray();
    symbols.put("BHEL");symbols.put("BHEL");symbols.put("GAEL");
    symbols.put("TATAELXSI");symbols.put("HCLTECH");symbols.put("not a symbol");
    List<String> queries=InternetResearchEngine.queries(symbols);
    assertEquals(10,queries.size()); // 3 x 3 plus macro
    assertTrue(queries.get(0).contains("BHEL"));
    assertTrue(queries.get(3).contains("GAEL"));
    assertTrue(queries.get(6).contains("TATAELXSI"));
    for(String query:queries)assertTrue(query.length()<600);
  }
  @Test public void suspiciousDomainsDoNotMasqueradeAsOfficial(){
    assertTrue(InternetResearchEngine.evidenceTier(
      "https://www.nseindia.com/companies-listing").startsWith("OFFICIAL_HOST"));
    assertTrue(InternetResearchEngine.evidenceTier(
      "https://sebi.gov.in/filings").startsWith("OFFICIAL_HOST"));
    assertTrue(InternetResearchEngine.evidenceTier(
      "https://nseindia.com.evil.example/fake").startsWith("EXTERNAL_SEARCH_RESULT"));
    assertEquals("UNVERIFIED",InternetResearchEngine.evidenceTier("http://www.nseindia.com"));
    assertEquals("UNVERIFIED",InternetResearchEngine.evidenceTier("file:///etc/passwd"));
  }
}

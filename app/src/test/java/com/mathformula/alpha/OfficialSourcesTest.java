package com.mathformula.alpha;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.HashSet;
import java.util.Set;

public class OfficialSourcesTest {
  @Test public void fourDistinctOfficialNseFeedsWithoutLogin(){
    assertEquals(4,OfficialNseFeed.FEEDS.length);
    Set<String> names=new HashSet<>(),urls=new HashSet<>();
    for(String[] feed:OfficialNseFeed.FEEDS){
      assertTrue(names.add(feed[0]));
      assertTrue(urls.add(feed[1]));
      assertTrue(feed[1].startsWith("https://"));
      assertTrue(feed[1].contains(".nseindia.com/"));
    }
  }
  @Test public void originalPublicationTimeIsRequired(){
    long publish=OfficialNseFeed.publishedAt("Thu, 15 Oct 2026 07:00:00 GMT");
    assertTrue(publish>0);
    assertEquals(0,OfficialNseFeed.publishedAt("Yesterday"));
    assertEquals(0,OfficialNseFeed.publishedAt(""));
  }
}

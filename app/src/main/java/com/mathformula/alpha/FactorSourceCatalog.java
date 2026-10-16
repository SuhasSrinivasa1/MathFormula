package com.mathformula.alpha;

/**
 * Maps F01..T30 to actual *kinds* of evidence, never to pre-filled factor values.
 * Discovery sources are human-auditable, not an assertion that the provider has
 * a stable/open machine API or that a search result is a fact.
 */
final class FactorSourceCatalog {
  static final String[] SOURCE={
    "NSE financial results / XBRL","NSE comparative financial results","NSE EPS / PAT filing",
    "NSE quarterly financial results","NSE operating profit history","Annual report and balance sheet",
    "Annual report cash flow","Balance sheet and credit rating releases","NSE filings plus Groww market cap / peer reports",
    "Annual report, audit and capital disclosures",
    "NSE/BSE exchange announcements","Earnings filing + pre-release consensus provider",
    "Exchange releases / investor presentation","Exchange order announcements and trailing sales",
    "SEBI and government notifications","Dated analyst consensus / revisions provider",
    "NSE shareholding and governance filings",
    "Groww Nifty candles and volatility","NSE sector indices / Nifty historical candles",
    "NSE sector advance/decline data","NSE institutional participant published flow",
    "Approved overseas index price data","RBI FX reference rates + issuer disclosures",
    "Approved commodity price data + issuer report","RBI and official policy reports / macro datasets",
    "Groww 5m volume versus 20 prior same-time sessions",
    "Groww last 20 completed session highs + completed bars",
    "Groww historical price trend and extension",
    "Groww completed OHLC (automated T29)",
    "Groww live exchange-timestamped VWAP/depth and current broker fee schedule"
  };
  static final String[] URL={
    "https://www.nseindia.com/companies-listing/corporate-filings-financial-results",
    "https://www.nseindia.com/companies-listing/corporate-filings-financial-results",
    "https://www.nseindia.com/companies-listing/corporate-integrated-filing",
    "https://www.nseindia.com/companies-listing/corporate-integrated-filing",
    "https://www.nseindia.com/companies-listing/corporate-filings-financial-results",
    "https://www.nseindia.com/companies-listing/corporate-filings-annual-reports",
    "https://www.nseindia.com/companies-listing/corporate-filings-annual-reports",
    "https://www.nseindia.com/companies-listing/corporate-filings-financial-results",
    "https://www.nseindia.com/companies-listing/corporate-filings-financial-results",
    "https://www.nseindia.com/companies-listing/corporate-filings-annual-reports",
    "https://www.nseindia.com/companies-listing/corporate-filings-announcements",
    "https://www.nseindia.com/companies-listing/corporate-filings-financial-results",
    "https://www.nseindia.com/companies-listing/corporate-filings-announcements",
    "https://www.nseindia.com/companies-listing/corporate-filings-announcements",
    "https://www.sebi.gov.in/",
    "https://www.nseindia.com/companies-listing/corporate-filings-announcements",
    "https://www.nseindia.com/companies-listing/corporate-filings-shareholding-patterns",
    "https://groww.in/trade-api/docs/curl/historical-data",
    "https://www.niftyindices.com/",
    "https://www.nseindia.com/",
    "https://www.nseindia.com/",
    "https://www.niftyindices.com/",
    "https://www.rbi.org.in/",
    "https://www.nseindia.com/",
    "https://www.rbi.org.in/",
    "https://groww.in/trade-api/docs/curl/historical-data",
    "https://groww.in/trade-api/docs/curl/historical-data",
    "https://groww.in/trade-api/docs/curl/historical-data",
    "https://groww.in/trade-api/docs/curl/historical-data",
    "https://groww.in/trade-api/docs/curl/live-data"
  };
  static {
    if(SOURCE.length!=30||URL.length!=30||Alpha30Engine.FACTORS.length!=30)
      throw new AssertionError("Factor-source registry must cover all frozen 30 factors");
  }
  private FactorSourceCatalog(){}
  static String sourceFor(int oneBasedFactor){
    if(oneBasedFactor<1||oneBasedFactor>30)throw new IllegalArgumentException("Factor 1..30");
    return SOURCE[oneBasedFactor-1];
  }
  static String link(int oneBasedFactor){
    if(oneBasedFactor<1||oneBasedFactor>30)throw new IllegalArgumentException("Factor 1..30");
    return URL[oneBasedFactor-1];
  }
  static boolean marketDataOnly(int oneBasedFactor){
    return oneBasedFactor>=26&&oneBasedFactor<=30;
  }
  static boolean consensusRequired(int oneBasedFactor){
    return oneBasedFactor==12||oneBasedFactor==16;
  }
}

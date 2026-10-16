# MathFormula v3.3 — Internet Research Engine (16 October 2026)

## User requirement

The app should research NSE stocks like an evidence-grounded assistant: search the
internet, retrieve primary-source materials, extract financial and economic facts,
calculate the already frozen Alpha-30 normalized factors, and evaluate live gates.
All work stays on the user's Vivo phone; notifications are local. No trading API
order submission or external notification service.

## Implemented on the phone in v3.3

- No extra key required: downloads NSE announcements, integrated financial
  filing announcements, annual-report updates, and shareholding RSS updates,
  as separate source feeds with retrieved and published timestamps and per-feed
  errors. NSE feeds have changed historically; missing feeds remain explicit.
- Existing Groww full-universe/priority/radar source, exact prior-20-session
  breakout reference and T29 completed-OHLC normalization remain intact.
- Registry for all 30 *exact* F01–T30 factors, each with source category and
  official landing link. No weights, hard gates or thresholds modified.
- Optional direct-to-Internet discovery using a user-owned **Brave Search API**
  key stored in Android Keystore. No search provider key ships in the APK.
  Configurable in Settings > Internet research engine. Queries contain only
  public NSE ticker symbols, filing/news/governance terms and macro topics.
- Bounded nightly research: up to 3 current priority symbols, 3 distinct
  questions per symbol and 1 macro query, 8 search results per question,
  2 requests per OS-permitted background job, idempotent checkpoint/resume
  over the trading night, per-day completion marker and error/429 cooldown.
- Ranks discovered links by the source's domain (official NSE/BSE/SEBI/RBI vs
  external secondary), saves citations/headlines/brief excerpts locally,
  and retrieves a bounded excerpt from an official NSE iXBRL HTML source
  when one is discovered.
- The Market watch dashboard displays latest discovered citations as
  clickable source links with an explicit source-validity warning.
- Supports feed collection without Groww authorization, and deep searches
  without transferring Groww tokens to the search provider.
- New automated tests for 30-factor source maps, query boundedness,
  spoofed domains, 4 NSE source feeds and publication-time parsing.

## Meaning of "automatically collected" (IMPORTANT)

1. DATA_DISCOVERED: public source URL/headline retrieved.
2. DOCUMENT_READ: official HTML/iXBRL document text successfully retrieved.
3. FACT_EXTRACTED: field and units identified, company/ticker matched,
   financial period and consolidated-vs-standalone basis resolved.
4. FACT_VALIDATED: timestamp and issuer provenance checked, multiple
   periods/revisions deduplicated, peer-appropriate definitions.
5. FACTOR_NORMALIZED: existing Alpha-30 normalization applied to actual
   numeric facts, value 0–1, source and observation timestamp persisted.
6. BUY_ELIGIBLE: 30-factor score >=78, weighted coverage >=90%, all 12
   broker-grade G01–G12 conditions passed at the observed live instant.

v3.3 implements additional phases **1 and a narrow subset of 2**. It does
NOT auto-populate financial, analyst, macro, valuation, ownership or C11–C17
normalized scores from an unverified headline. Existing automated T29 is the
only proven automatic factor, and live T30 needs verified order book/fees.

## Fundamental data needed to complete F01–F10

NSE integrated quarterly XBRL/financial results/annual reports; issuer mapping;
series of consolidated values for revenues, margins, EPS, operating profit, ROCE,
CFO, debt, dilution, peer valuation and restatement handling; robust XBRL
taxonomy and period parser. HTML text snippets alone are insufficient.

## Catalyst data needed for C11–C17

Exchange filings, board meetings, order wins, guides, governance filings and
shareholding snapshots. C12 earnings surprise and C16 analyst revisions need
point-in-time pre-announcement consensus/revision data, which free NSE RSS
and Groww quotes do not offer as normalized historical datasets.

## Macro and sector data needed for M18–M25

NSE/Nifty sector indices, sector constituent breadth, FII/DII flows, RBI FX
and policy releases, independent overseas benchmark and commodity data,
and issuer exposure metrics. Raw market returns are not issuer-specific
currency/commodity *exposure* scores.

## Execution data needed for T26–T30

Groww historical intraday price/volume and exchange timestamps, time-of-day
matched volume baseline for T26, completed 15m+5m breakout confirmations,
VWAP, live order book, spread, market/sector breadth and versioned Groww
fees/slippage for net RR. Already required 12 gates are unchanged.

## External dependencies and costs

The optional Brave Search API has its own subscription/key and potential
usage costs; the app does not automatically subscribe or charge the user.
Sources may deny bot access, change schemas, demand paid licensing or
respond with stale metadata. A web search result is **not** a verified fact.
No model weights or fabricated coverage are used as a workaround.

ChatGPT's internet research includes search, page reading and reasoning.
Replicating its complete capability would additionally require a capable
LLM or a tightly specified suite of deterministic extraction modules,
which is not bundled with this Android QA app. An LLM's generated
conclusions must never silently become trade-entry scoring inputs.

## Release constraints

This is a QA build, not certified NSE-wide autonomous trading research:
Vivo/Android background restrictions and the Android 15 dataSync
foreground-service time limit apply. No backend server, FCM or external
notification receiver is required for *local* status alerts. The user must
launch and authorize the app; internet data retrieval is not available
offline. After uninstall of an older QA APK, Keystore credentials, evidence
database and scan history are lost.

## Official references

- https://www.nseindia.com/static/rss-feed
- https://www.nseindia.com/companies-listing/corporate-integrated-filing
- https://www.nseindia.com/companies-listing/corporate-filings-announcements
- https://groww.in/trade-api/docs/curl/live-data
- https://groww.in/trade-api/docs/curl/historical-data
- https://api-dashboard.search.brave.com/api-reference/web/search/get

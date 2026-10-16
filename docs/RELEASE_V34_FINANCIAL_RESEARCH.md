# MathFormula v3.4 — New installation / audited Internet financial-source extraction
Date: 16 October 2026. Branch: feat/v34-verified-financial-evidence-20261016.

## Implemented
- Previous v3.3 full NSE scanner, point-in-time completed Groww candles, four official
  filing RSS sources, optional Brave Internet search, local-only Android notifications
  and Material 3 core components.
- New pure-Java NSE inline-XBRL HTML numeric extractor with security bounds:
  official domain and /corporate/ixbrl/ URL allowlist, 250 KB HTML cap,
  40 raw fact cap, mandatory XBRL name/contextRef/unitRef,
  decimal/scale/negative sign parsing and source URL.
- New FREE keyless NSE financial document collector: from exchange RSS integrated
  financial announcements, retrieve one eligible official inline XBRL HTML record
  per four-hour window and locally preserve factual numeric candidates, reporting
  context, source, original publication timestamp and collection timestamp.
- If optional web search is configured, source discovery widened to up to six
  priority ticker research sets plus one macro sweep per completed day; all
  requests are checkpointed, quota-aware, and never contain Groww secrets.
- Search-discovered official inline-XBRL HTML can also yield saved raw facts
  (rather than just 1,800 characters of unstructured text).
- App Settings: Check official NSE research button, best-effort and rate-limited.
- Research dashboard: available raw financial facts and exact unavailable/undated
  source status explicitly shown as NOT normalized Alpha-30 evidence.
- JUnit tests for source allowlist, raw numeric scale/unit/context, negative
  numbers, oversize data and query planner. Existing frozen Alpha-30 tests retained.

## What is NOT complete — MUST NOT claim full 30-factor automation
- Raw inline-XBRL tags are NOT issuer-verified or consolidated-vs-standalone matched.
  A single document provides no reliable quarter-over-quarter or comparable-year
  data, and cannot be assigned normalized Alpha-30 score under the frozen reference
  curves. We intentionally do not write these candidates to Alpha30EvidenceStore.
- T29 from Groww completed OHLC remains the automatically normalized source-backed
  factor. Other F/C/M/T numeric feature adapters, financial statement taxonomy,
  issuer mapping, currencies/units, issuer-period tie-outs and audited scoring
  require further work; C12/C16 pre-earnings consensus may need paid sources.
- This APK does NOT contain ChatGPT/LLM runtime. Optional search uses the user's
  Brave Search API token, which the external search provider can charge for.
- Phone-only JobScheduler + foreground service cannot guarantee scanning under
  Vivo battery restrictions and Android 15 dataSync foreground time quotas.
  No Firebase or off-device notifications are used.
- This is a QA APK (com.mathformula.alpha.stable.qa) with temporary debug signing.
  Stable signing identity and physical Vivo acceptance are not certified.
- No broker BUY authorization unless all Alpha-30 factors, >=90% coverage,
  >=78 score, broker tick freshness and all 12 hard gates pass. No autotrading.

## Clean installation
1. Install the signed QA APK. It does not require an older installed app.
2. Launch once; set up a Groww API TOTP token and secret in secure Settings.
3. Allow notification permission and background activity where available.
4. Research RSS metadata runs automatically through scheduled jobs; the financial
   document path attempts eligible official HTML records only, if published.
5. For broader web searching, opt in with a user-owned Brave Search API token.
6. During market hours check foreground notification AND scanner heartbeat.
7. Never interpret a WATCH, search result or raw XBRL fact as an authorized BUY.

Official reference: MathFormula Alpha-30 Master Methodology (15 Oct 2026),
unchanged weights and gate specification. Failure to obtain a required input
results in a missing feature, not an inferred probability or false precision.

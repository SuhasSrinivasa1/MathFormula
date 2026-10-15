# MathFormula v3.0 QA — implemented scope and limitations
Date: 15 October 2026. Alpha-30-v1.0 immutable formula.

## Included
- v2.9 hardened Groww encrypted TOTP storage, 401 token reset and 403 approval diagnostics.
- Groww Connect / Recheck, clearer session statuses; saved credentials not mistaken for an expired session.
- Shared HTTPS backend viewer settings between Settings and the home dashboard; visible server setup controls.
- New best-effort NSE-wide intraday radar using Groww batch LTP/OHLC across the current exchange instrument master every ~15 minutes.
- Cached H20 levels shown only if sourced from >=61 completed EOD sessions and >= INR 1 crore historical turnover; unknown history stays preliminary.
- Dynamically prioritize current-session candidates for detailed monitoring; retain overnight candidates as fallback.
- Scanner health: quoted/universe counts, quote-data timestamp, history reference coverage, scan failures and interrupted history checkpoint.
- Professionalized home interactions: four health indicators, current-session research cards, accessible focusable tabs, 48dp controls, ripple feedback and scrolling position.
- Research-only output from bulk quotes, untouched Alpha-30 weights (27/23/19/31), score thresholds and all 12 mandatory BUY gates.
- Read-only: no order placement or simulated live BUY verification.

## NOT IMPLEMENTED OR NOT VERIFIED
- True closed-phone 24/7 market-wide scanning: separate always-on backend and push service not deployed.
- Automatic completion of all 29/30 financial, news, catalyst and macro factors from independently verified data providers.
- Fully live tick-certified trade gate inputs and fee-adjusted targets/stops. Phone research does not emit verified Alpha-30 BUY.
- Firebase push for server-side BUY alerts, token refresh/dedupe/cancel.
- Full Jetpack Compose Material 3 visual rewrite and exhaustive Vivo device/accessibility testing.
- Production signing, broker account approval validation, deployed backend and full end-to-end acceptance testing.
- Immediate scanning directly on APK install: Android requires first launch and relevant authorizations; an offline phone cannot retrieve internet quotes.

## Install QA safely
- The new Android QA package remains com.mathformula.alpha.stable.qa.
- GitHub runner debug signing keys can differ across builds, so APK may NOT install as an update over previous v2.9 QA.
- Export old diagnostic ZIP first. If Android shows signing conflict, uninstall old MathFormula QA only and install new APK.
- Uninstallation erases encrypted Groww credentials and local scan history. Re-enter own TOTP API token and Base32 secret.
- Open app, grant notifications, use Settings > Connect Groww > Connect/Recheck.
- If Groww demands approval (documented daily at its Cloud API keys page), complete that outside MathFormula.
- Market page must say current scanner mode and stale/complete timestamps. Bulk discovery cards remain WATCH only.

## Release gates for real production
1. Deploy durable licensed NSE universe/EOD, financial/catalyst/macro ingestion and always-on server.
2. Complete normalized point-in-time 30-factor scoring, 90% coverage, 12 gates and live broker-fee calculations.
3. Implement FCM push and signal retraction even when phone closed.
4. Verify QA with real authorized Groww account; rate/expiry tests, Vivo Doze/restart and full stock-universe coverage.
5. Add stable APK signing key, full Material 3 design migration, accessibility automation and paper trading.

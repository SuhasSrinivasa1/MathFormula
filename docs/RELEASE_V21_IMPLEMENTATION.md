# MathFormula v2.1 — source-gated Alpha-30 preview

## What this build really does
- Retains the frozen Alpha-30 v1.0 weights, 78-point BUY threshold and 90% weighted source coverage minimum.
- Runs the independent full-NSE technical candidate scan when the server is deployed with real market data.
- Persists dated research recommendations and immutable decision/closure event rows in SQLite.
- Evaluates the current exchange trade timestamp, all available normalized factor evidence, execution-source timestamps, completed 15m + two 5m candle gates, VWAP, RVOL, sector gate, spread, liquidity, target, stop and source-provided net R:R.
- Exposes token-protected read-only `/v1/recommendations/live` and `/v1/recommendations/closed`.
- Android Settings -> Independent NSE scanner: configure public HTTPS domain and separate APP_READ_TOKEN. Then Home shows authoritative recommendations and 30-factor details. When no server is configured, local Groww discovery remains RESEARCH ONLY.
- No trading/order APIs; no imaginary scores, returns, or fabricated BUY alerts.
- A record does not close merely because its top-30 ranking falls. The backend can expire research reviews after three *recorded subsequent EOD sessions*, not weekday estimates.

## What is NOT automatically operational
The preview APK is NOT a standalone whole-market BUY recommendation system. It does not ship with a deployed scanner, a licensed stream subscription, corporate-results/filings/newspaper feeds, fixed normalization functions for every factor, all independent real-time candle/execution evidence feeds, or Firebase credentials. Without these actual services the app correctly says NO SIGNAL. Operator-provided pre-normalized factor CSV is a supported integration contract, NOT automated source analysis. Server webhook is supported but native Firebase push is not provisioned.

Do not use an unsupported CSV or an LLM's unsourced score to enable BUY. Complete data-provider agreements, data mappings and source validation before enabling any live decision.

## Server quick start
1. Deploy the repository's `server/` Docker Compose stack to a secure Linux instance with HTTPS.
2. Set the Groww **server-only** environment credentials and random `APP_READ_TOKEN` (>=32 chars) in `server/.env`. Never commit these secrets.
3. Provision authoritative NSE instrument and 60+ completed EOD sessions using documented import/bootstrap. Confirm completeness via `/v1/status`.
4. Populate normalized sourced **factor evidence** for each symbol: `FACTORS_FILE=/data/factors.csv`, CSV schema: `symbol,factor,value,observed_at_utc,source`. Exactly 30 factor numbers; missing/stale inputs stay missing. Importer accepts normalized 0..1 but does *not* calculate them automatically.
5. For independent verified live technical + risk inputs, set `EXECUTION_EVIDENCE_FILE=/data/execution.csv`, CSV schema:
```csv
symbol,observed_at_utc,source,breakout15m,confirmation5m,vwap5m,vwap15m,rvol,sector_positive,liquidity_verified,spread_bps,net_rr,entry,stop_loss,target,trading_allowed,material_risk_clear
```
Here `source` is an HTTPS provenance pointer to the validated calculation. Boolean values must be 0 or 1. This is an integration contract for a trusted process that actually computes **completed** candles, time-adjusted RVOL, verified bid/ask/liquidity, market-risk clearance and NET (after estimated trading costs) reward/risk. The adapter refuses inconsistent price plans and timestamps older than 5 minutes at import; the live evaluator requires evidence no older than 10 seconds. A manually filled demonstration spreadsheet is NOT a verified live feed.
6. On Android Settings, enter the HTTPS server origin and its read-only viewer token. The connected UI updates every ~30 seconds while open; real-time eligibility expires with its quote, so verify broker quotes independently before decisions.
7. Configure authenticated HTTPS `ALERT_WEBHOOK_URL` for server events only after receiving, filtering and delivering them from a trusted notification service. There is no native FCM client in this preview.

## Immutable gates
`server/mathformula/alpha30.py` and `Alpha30Engine.java` are the reference scoring functions; thresholds are unchanged. `server/mathformula/recommendations.py` strictly requires:
- Live market and exchange trade time <= 10s
- >=90% weighted coverage; mandatory T30 present
- score >=78
- confirmed 15m breakout, two completed 5m closes, both VWAP confirmations
- time-adjusted RVOL >=1.5, sector-positive, liquid and spread <=30bps
- independent trusted entry/target/stop & net-RR >=1.25 with net-RR <= gross RR
- instrument trade and material-risk clearance

Decision output is `NO_SIGNAL` if data are unavailable/stale, `WAIT` for failed gates despite complete evidence, and `BUY_ELIGIBLE` only when every gate passes at the recorded instant. Because the Android UI polls periodically rather than streaming, a 10-second-valid signal may have expired by the time the user sees it; the app therefore downgrades stale UI evidence.

## Acceptance results
- Python source/SQLite regression tests run on each push in `.github/workflows/server-ci.yml`.
- Android Gradle assemble and artifact upload run on each feature-branch push in `.github/workflows/android.yml`.
- These tests verify code paths, not full market coverage, broker connectivity, financial statement normalization, device performance, prediction accuracy, delivery of notifications or production deployment.
- Preview has an independent Android package ID `com.mathformula.alpha.v21` so it coexists with debug-signed v1.9/v2.0. Preview builds use transient debug signing; final in-place production upgrades require a persistent production keystore.

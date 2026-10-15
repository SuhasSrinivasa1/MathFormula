# MathFormula v2.9 — 15 Oct 2026 recovery audit and production gate

Status: **HARDENING BRANCH / NOT PRODUCTION CERTIFIED**. Baseline: feat/v28-overnight-quota-safe.
The frozen **Alpha-30-v1.0** weights, thresholds, 29-factor overnight normalization,
12 hard gates and no-autotrade policy are unchanged.

## Diagnostic source of truth: user-exported MathFormula_Alpha30_Audit_20261015_2133.zip

- App release: **2.8.0**; export 2026-10-15 21:33:57 IST.
- 2026-10-14 23:14:40 IST last completed overnight discovery: universe 2,697,
  quotes 2,695, broad candidates 649, detailed histories 55, eligible research 0.
  Snapshot explicitly labeled partial.
- 2026-10-15 09:03:09 IST provisional scan: 3,535 universe / 3,527 quotes,
  386 preliminary candidates, no completed full verification.
- 2026-10-15 09:04:54 IST checkpoint: checked 160/386; only 4 passed technical research checks.
- 2026-10-15 11:58:12 IST three priority prices polled, not exchange-timestamped BUY evidence.
- Last attempted scan 21:33:44 IST still labeled "Scanning NSE equity universe…".
- Local market observations = 0; local audit events = 0; monitoring_enabled=false,
  watchlist still TARIL / GAEL / ADVANCE; backend unconfigured.
- Authentication rejection or actual Android process kill CANNOT be proven from this ZIP.
  No secrets or account identifier were included.

## Confirmed code paths and repairs on this branch

1. ScannerService.watchlist had a **25 minute** maximum age on the overnight scan,
   making next morning's monitored list empty. Monitor up to five saved priority names
   during the next session, bounded by a 4-day maximum age. Still research-only.
2. Settings treated any verification older than **15 minutes** as "verify again."
   This is *not* the same as losing encrypted Groww TOTP material. Clarify status.
   The Groww access token must renew daily; source implements renewal at 06:00 IST.
3. LocalDiscovery persisted a permanent "Scanning..." headline after process death.
   Recover an INTERRUPTED status on next initialization and preserve resumable checkpoints.
4. When app is visible, local phone monitoring is now requested automatically if credentials
   are configured, NSE market is open, backend not configured and the user has not opted out.
   This is NOT 24/7 scanning, cannot override device process management and cannot
   start unattended immediately on APK installation before first launch/permissions.
5. Warn explicitly that phone-only live monitoring is **limited**, not NSE-wide.
6. Freeze 30-factor implementation and introduce Android contract tests before release builds.

## Production architecture required (not provided by this app-only patch)

- **Always-on server**: licensed NSE EQ master, >=60 completed sessions per eligible
  stock, 20-day INR traded-value >= 1Cr, corporate actions; backfill and point-in-time store.
- **Off-hours research**: filings, statements, point-in-time catalysts, consensus,
  ownership, sector macro, source ids/timestamps; complete 29-factor/91-weight scoring.
- **Live market loop**: batch discovery covering eligible universe at scheduled intervals,
  streaming only selected candidates, 5m/15m *completed* bars, timestamped
  broker last-trade, VWAP, time-adjusted RVOL, order-book spread/depth.
- **Strategy**: 30 frozen weights (27/23/19/31); >=90% eligible weighted coverage;
  WATCH>=68, BUY>=78; all G01–G12 gates must be true, including quote <=10s,
  15m breakout + two 5m closes, RVOL >=1.5, spread <=30bps,
  fee-adjusted net R:R>=1.25; 09:30–14:45 IST routine entry evaluation only.
- **Execution economics**: current versioned Groww tariff, order size, all buy/sell
  charges and slippage, numerical solution for +1.5% net target and -1.2% net stop
  within two sessions. No order-placement endpoints.
- **Server authorization**: account-owned broker credentials held in secure server secret
  storage (not uploaded to public GitHub or APK); user-specific token strategy,
  expiry monitoring and secure read-only app authorization.
- **Reliability**: supervisor/restart, durable queues, idempotent jobs, rate-limit
  governor across ALL consumers, deterministic jitter, exponential backoff, circuit-breaker
  and alert on 401/403/429/disconnect; hard stop on missing provider permissions.
- **Push**: Firebase Cloud Messaging to phone; dedupe/expiry/cancel/retract,
  payload only for BUY ELIGIBLE with verified exact snapshot.
- **Observability**: persist source/dataset age, universe denominators, symbol-level
  rejection codes, 30 raw inputs/normalizers/provenance, scores, gate results, worker
  heartbeat, last success/attempt/error, backlog depth, feed disconnects, queue latency,
  missed signal audits, push delivery acknowledgement and versions. Secret-safe logs.
- **Dashboard**: Compose Material 3 migration on separate tested UI branch;
  one primary Market screen with states WATCH, CONFIRMING, BUY ELIGIBLE, INVALIDATED.
  Include visible health strip: MARKET / GROWW / SCANNER / NSE COVERAGE / DATA AGE.
  Detail pages should explain all missing gates and source attribution.
  Screen-reader labels, 48dp minimum click targets, responsive text, back navigation,
  Android permission explanations, not repeated credential fields on every refresh.

## RELEASE BLOCKERS (do NOT claim production readiness before closing)

P0: Deploy/reach independent scanner; stable broker sessions and licensed live feed;
    verify full universe coverage and scheduled hourly/nightly recovery; server currently
    "not configured" in user ZIP.
P0: Ingest and validate financial/catalyst/macro inputs for real normalized scoring;
    current Groww market data alone cannot satisfy 30 factors.
P0: Real transaction fees, completed-bar/order-book proof, G01–G12 integration and
    certified signal states; current client intentionally emits NO broker-verified BUY.
P0: Reliable FCM push/cancel integration and Android device closed/off test.
P0: Stable signing identity matches installed app, appId=com.mathformula.alpha.stable.
P1: Instrumented Android 13–16 and Vivo Funtouch OS tests; Doze/reboot/process-kill,
    timezone/session/holiday, 06:00 token rollover, 401/403/429 and broker outages.
P1: Full Material 3 UI migration/accessibility tests and Play policy review.
P1: Point-in-time walk-forward and multiweek paper-trade acceptance report.

## Explicit platform constraints

- Groww's published access token expires daily at 06:00 IST. Retained TOTP material
  can be used to renew tokens, subject to valid Groww service access.
- Android JobScheduler/WorkManager best-effort jobs can be delayed in Doze, particularly
  on OEM firmware. 15-minute periodic execution is not guaranteed to be exact.
- Android 15 target SDK 35 dataSync foreground service has a shared 6-hour/24-hour
  background time allowance; BOOT_COMPLETED cannot directly launch this service type.
- Installing an APK cannot silently establish brokerage credentials, bypass Android
  runtime permission prompts, deploy a remote 24/7 server, or guarantee offline
  internet market data. Startup can schedule tasks **after the first launch**.

## Acceptance tests

1. 2026-10-14 23:14 overnight shortlist may be monitored 2026-10-15 09:15
   without becoming BUY eligible; older-than-four-day watchlist expires.
2. Closing the app during a scan yields INTERRUPTED + progress checkpoint after restart,
   never a permanent fictitious RUNNING headline; resume completes without refetch storms.
3. Settings at +16 minutes says credentials *saved*, not "re-enter/verify again";
   Groww API access renewal after 06:00 IST must succeed or show a clear auth fault.
4. User chooses Stop monitoring -> foreground auto-start remains off until explicit restart.
5. Current and completed NSE scan dates are always visible; no stale-day live BUY label.
6. Screened universe = enumerated / quoted / histories / coverage / rejections, with
   periodic and error heartbeat audit.
7. No BUY before 09:30, after 14:45, stale quote, missing factors, insufficient RVOL,
   failed 15m/5m bars, excessive spread or net R:R below 1.25.
8. Phone off + server online: scheduled research and verified push/cancel work.
   Phone offline: backend runs and queued notification recovers on reconnection.
9. 401/403/429, expired TOTP, clock skew, missing exchange holidays and rate quotas
   fail closed with explanatory UI rather than an unchanged "Top 3".
10. Production APK requires signed build, same signing certificate as current app
    and physical device smoke test; keep current installed app until verified.

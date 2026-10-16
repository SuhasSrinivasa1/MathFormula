# MathFormula v3.2 — Phone-only local monitoring QA (16 Oct 2026)

## User design clarification
This is a PRIVATE single-device NSE research app. Alerts display on the SAME phone.
No Firebase project, FCM sender, server, third-party push destination or order submission.
The phone connects to Groww and permitted public websites via HTTPS for data retrieval.
The only remote services contacted are data providers, not notification platforms.

## Implemented in v3.2
1. Material 3 dashboard/settings from v3.1, secure Android Keystore Groww TOTP.
2. Persistent LOW importance foreground local status notification during market hours,
   showing monitored quote count, full-universe radar count/progress and IST time;
   separate HIGH importance on-device breakout research alerts.
3. A 60-second local foreground heartbeat in app-private preferences plus dynamic
   recovery on next visible launch when Vivo kills the process but leaves enabled=true.
   Updated receiver reschedules Android JobScheduler periodic research after reboot
   or package replacement. It deliberately does NOT launch a dataSync FGS at boot.
4. A best-effort ~15-minute entire eligible NSE-universe market-data scan from
   Groww batched quotes, dynamically replacing the small detailed-monitor watchlist.
   Active foreground service can launch the radar even when the UI is closed, while
   remaining under a conservative global Groww quota limiter.
5. On-device hourly collection of public NSE official corporate announcements RSS:
   https://archives.nseindia.com/content/RSS/Online_announcements.xml
   Store source links, dates, headlines and source freshness; refuse to convert a
   headline into an invented catalyst score or misassociate company name with symbol.
6. Completed Groww daily history automatically stores **T29 (closing strength)** at
   0..1 using a public-at timestamp; valid 20-session H20 includes the previous day.
   Other normalized factors are accepted only from existing time-stamped evidence import.
7. Individual intraday research cards expose actual normalized weighted coverage and
   number of observed factors, with explicit WATCH-only/no-BUY status.
8. Failed jobs and stalled monitors record diagnosable, privacy-safe states. FGS
   busy latch now releases even after exceptions.
9. Removed FirebaseMessagingService, Firebase SDK, cloud-push and confusing server
   notification statuses (optional pre-existing server viewer code still exists but
   is not required/used in phone-only mode).
10. All frozen Alpha-30-v1.0 factors, 27/23/19/31 weights, thresholds, R:R and
    G01–G12 remain EXACTLY unchanged.

## Physical/platform limits — do not describe as a guaranteed 24/7 scanner
- Android 15 targeting API 35 limits dataSync foreground services to **6 h total
  in 24 h** in background. A persistent notification DOES NOT bypass that cap.
  The app voluntarily ends a service session by 5h55; NSE cash market stays open
  for 6h15. The remainder relies on OS-scheduled jobs/visible-app restarts, without
  exact timing guarantee. Android JobScheduler 15m periodic runs may be delayed in
  Doze/Vivo battery restrictions. Force-stop disables jobs until a user relaunches.
- Bootstrap jobs can schedule after reboot but cannot legally start dataSync FGS
  from BOOT_COMPLETED on targetSdk35. No fake wakelocks, overlay permission or
  playback type are used to circumvent Android policy.
- Full automatic normalized collection of ALL 30 factors is NOT complete because
  Groww market data and a headline RSS do not provide timestamped corporate
  financials, forecast consensus, guidance, institutional flow or sector macro
  data with validated per-factor normalization. Missing evidence remains missing.
  The existing evidence-store input contract is the integration point for future
  primary-source feeds. Research cards expose weighted coverage. BUY remains
  prohibited until >=90% features and ALL hard gates.
- RSS can be unavailable or throttled. Freshness and errors are shown, not silently
  filled. Filings metadata is not a verified fundamental score.
- Groww daily 06:00 token expiry, account approval and API subscription still
  apply. HTTPS internet needed. Android notifications need user permission.
- No manual Vivo device instrumentation or Play Store policy acceptance completed.
  Stable production signing is not configured: QA uses per-run debug certificate.

## Installation & acceptance
1. Export v3.1 diagnostics first if needed. Debug-signer may change and require
   uninstalling old MathFormula QA. Uninstalling destroys saved Groww TOTP
   and all SQLite scan/factor history; reenter own credentials securely.
2. Open MathFormula, configure Groww and approve the requested access,
   grant notifications and allow background work in Vivo battery/app settings.
3. At 09:15–15:30 IST on a trading day, view the ongoing "MathFormula · local
   NSE monitoring" notification, updated heartbeat, and NSE radar quote coverage.
4. Lock the phone for 20+ minutes. Verify updated heartbeat after unlocking;
   note OS job delays/coverage gaps honestly. Check Groww 429 and errors.
5. After 16:30 IST, verify EOD research checkpoint progress and NSE RSS
   download freshness; no guessed company metrics or Alpha-30 BUY.
6. Perform device-specific reboot, battery saver, process kill, provider 401/403/429,
   market holiday, quote staleness, session rollover and signing continuity tests.

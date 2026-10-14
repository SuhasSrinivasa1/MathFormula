# MathFormula v2.5 — 24/7 Next-Session Research

## Behavior
MathFormula continues to use the v2.2 inline masked Groww TOTP credentials, Save / Refresh & Connect actions, verified Groww connection indicator, and v2.3 Live / Closed + scanner status layout. It keeps the v2.4 stable Android application ID `com.mathformula.alpha.stable` and signing certificate. Version code increases from 240 to 250.

**Intraday (NSE weekday clock 09:15–15:30 IST):** the app scans the Groww NSE CASH universe during the session (normal interval 15 minutes, pre-close 5 minutes), refreshes shortlisted quotes every 30 seconds while open, and records prospective technical watches. 10-second Alpha-30 live trading gates are NOT established by this discovery layer.

**Outside market hours (including weekends):** the app runs a second, read-only next-session research scan against *completed* NSE daily candle data. The latest daily bar is not eligible until 16:30 IST on the date of that bar. The scan uses the same Groww NSE instrument universe, batch LTP/OHLC for broad candidate prescreening, and the latest **61 or more completed daily candles** for verification of leading candidates. The prior 20-session high and mean traded value/volume are calculated EXCLUDING the candidate's observed EOD candle, avoiding look-ahead. No fresh live trade is asserted.

- Screen: **Scan Tomorrow's Top 3** is available after hours and **Scan Now** during market hours.
- Automatic: foreground after-hours research not more than once every **4 hours** after a completed scan; failed scans are retried with a bounded interval. Background Android JobScheduler checks are best-effort (periodic 15m, after-hours full research cadence at least 4h) and may not run when the OS suspends the app. A server is required for guaranteed 24/7 unattended monitoring.
- Research candidates must still pass the existing technical and liquidity screening conditions; a completed scan with no qualifying candidates must display **zero**, never fabricated Top 3 symbols.
- Live tab contains the research watch with the **completed NSE session date**, source price marked **not live**, original resistance and technical reasoning. Closed tab retains only actual archived research history; the bell does not close an active watch.
- While the NSE market is closed, verified BUY eligibility is **unavailable**. The normalized 30-factor Alpha-30 decision process additionally requires independent company financial, catalyst, macro and execution evidence, not merely Groww EOD prices.
- After-hours metadata: `scanMode=NEXT_SESSION_RESEARCH`, `priceStatus=LAST_COMPLETED_SESSION_NOT_LIVE`, `sourceSessionDate`. Each candidate contains `priceAsOfSession` and `dailyCandleMs`.

## Tradeoffs and failure handling
- Groww may not expose reliable post-market batch LTP/OHLC snapshots for all symbols. Missing or insufficient live-data batch coverage is an explicit error; this app does not silently call an incomplete scan complete.
- Historical requests to verify all candidates are quota-limited. The current implementation only verifies the *leading* broad-screened candidates, not all factors for every company. The shortlist is technical research, not full Alpha-30 scoring and not trading advice.
- The schedule uses weekday clock estimates; exchange holidays and unscheduled sessions require a trusted NSE calendar for fully authoritative scheduling.
- Read-only data. No trade placement. Personal Groww credentials remain encrypted on the device using Android Keystore.
- Package and cert compatibility: v2.5 installs **over signed v2.4** if its certificate matches. v2.3 and earlier had separate package IDs. The Gradle workflow produces an unsigned release APK if GitHub signing secrets have not been set, suitable only for local signing with the v2.4 PKCS12 keystore. **Never publish an unsigned artifact as an installable app**.

## QA checklist
1. Confirm Build / release Gradle job succeeds; check app ID, vCode 250, and certificate SHA-256 against v2.4.
2. On market-closed day with valid Groww credentials, tap Scan Tomorrow's Top 3. Expect progress, full/partial coverage with result or explicit diagnostic, and EOD observation dates for any candidates.
3. Test quote freshness: no green verified BUY from EOD prices, including weekends and NSE holidays.
4. On a trading day, verify intraday 15m/5m intervals, and research list transitions to intraday observation without overwriting old audit records.
5. Test app restart, interrupted scans, failed batches, insufficient daily data, invalid credentials, and Android background throttling.
6. Real-account Groww data and device behavior require physical device testing; CI compilation does not prove exchange API access.

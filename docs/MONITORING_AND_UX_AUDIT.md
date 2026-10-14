# MathFormula v1.2 · Fixed Formula / Continuous Watch UI

## The unchanged mathematics
`Alpha30Engine.java` weights and hard gates remain **unchanged** from Alpha-30 v1.0. No fake calibrated probability, buy recommendation, or order placement. Groww alone cannot fill all 30 inputs.

## What user sees
- A persistent card for every watchlisted symbol, even on a failed breakout or stale feed.
- Last known price versus **expected breakout** = highest HIGH from the prior 20 *completed* Groww daily sessions (never future prices and never a profit forecast). Reference as-of date accompanies the value.
- Difference in rupees and % to the breakout, LAST KNOWN versus LIVE price indicator, explicit exchange last-trade timestamp, and local fetch timestamp.
- State ladder: WATCHING (more than 1.5% below) → NEAR BREAKOUT (within 1.5%) → BREAKOUT CROSSED (price crossed) → CONFIRMING (two completed 5-minute closes above breakout and Groww average traded price) → FULL MODEL PENDING (price structure confirmed, other factors absent).
- DATA STALE and DATA UNAVAILABLE override bullish display states. Even FULL MODEL PENDING is **NO SIGNAL** until missing fundamentals, new events, macro inputs, proper time-adjusted RVOL, risk limits, and full model are verified.
- Every state change and fetch error is written to a capped local audit log that users can open via Audit History; formula version and timestamps are recorded.
- Last scan timestamp, delay, and an explicit **COVERAGE GAP** state appear on screen if a monitoring heartbeat is missed.

## Background monitoring
- Android foreground `dataSync` service runs after a user taps **Start background** while inside 09:15–15:30 IST on a weekday; holidays require exchange calendar integration.
- Polls quotes every minute (boundary + 3 seconds) and completed candle confirmation every five minutes, including when UI is closed, and posts a persistent notification; separate breakout watch notifications are opt-in on Android 13+.
- Android 15 imposes a cumulative **six-hour per 24-hour** dataSync FGS cap. The session proactively terminates at 5h45 from user start, or market close, whichever is earlier. This is best-effort rather than continuous 24/7 monitoring.
- A force-stop, network outage, battery restrictions, authentication expiry or OS process kill may interrupt a phone scanner. **No guarantee of every opportunity.**
- No automatic start at boot: Android 15 restricts background service launches and dataSync starts from BOOT_COMPLETED. Reopen app/start monitoring when needed. The UI will indicate downtime.
- Market-closed or delayed sessions must not produce BUY.
- 20 watchlist stocks maximum to reduce rate load. Groww live-data type limits are 10 requests/s and 300/min; data rate-limit errors produce audit events.

## What is still required to avoid missed opportunity windows
A server-hosted scheduled scanner with persistent broker feed, full-NSE instrument universe, point-in-time filings, financial statements, macro inputs, a durable event queue, monitoring heartbeat, market-holiday calendar, and push alerts to Android. That infrastructure is **not implemented** in this APK. The Android foreground service is a best-effort stepping stone.

## Manual acceptance checks
1. Open app on Android 13–15 and grant notifications; authenticate privately with Groww token/secret.
2. Check live quote exchange timestamp and reference date; do not call stale prices live.
3. Verify 20-day breakout equals the max of the previous 20 **completed** daily candles.
4. Move across WATCHING/NEAR/BREAKOUT/CONFIRMING; verify wait conditions and zero BUY while missing required factors.
5. Start background session with app visible; lock screen; observe persistent notification, subsequent Last Scan refresh and audit record.
6. Turn off network; verify coverage/error reporting; reconnect; inspect audit.
7. Stop background; verify service and notification stop.
8. Let Android restrict/kill service; verify app reports STOPPED/COVERAGE GAP and never promises guaranteed uninterrupted scanning.

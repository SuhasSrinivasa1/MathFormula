# MathFormula — Alpha-30 v1.2.0 (Groww read-only)

**Frozen mathematical model:** `Alpha30Engine.java` retains exactly the original 30 weighted factors, all gates and thresholds unchanged.

## What is new in v1.2
- Explicitly user-started **Android foreground monitoring** service that continues when the app is sent to the background, with an ongoing notification.
- **Market quotes every minute**, completed 5-minute candle evaluation at 5-minute boundaries; most recent completed 15-minute candle retained.
- Watchlist candidates **remain displayed until manually removed**, including failed breakout and temporarily stale data.
- Every stock shows: observed price and movement, last-trade timestamp, market-data last poll timestamp, **expected breakout** (max high over previous 20 completed NSE daily sessions), reference date, distance to breakout, stage and NEXT REQUIRED CONDITION.
- WATCHING → NEAR BREAKOUT → BREAKOUT CROSSED → CONFIRMING → FULL MODEL PENDING; explicit DATA UNAVAILABLE/STALE/COVERAGE GAP states.
- Local audit journal of stock state changes and fetch errors; background service heartbeat / coverage display. Opt-in alerts for breakout watch milestones, **not BUY recommendations**.
- Direct Groww TOTP Token + Secret remains encrypted through Android Keystore, no keys in GitHub or APK, no order endpoints and no static trading IP.

## Critical scope and reliability limitations
1. This is **best-effort phone background monitoring**, NOT 24/7 guaranteed. Android 15+ `dataSync` foreground services have a 6-hour/24-hour limit; battery restrictions, network failures, Groww auth expiry, market holidays and user force-stop cause gaps. User must explicitly start monitoring while app is visible during NSE hours; monitoring stops at the session timeout or close.
2. The phone monitors a user-defined **up to 20 NSE stocks**, not all approximately 4,000 symbols. A hosted server is required for complete overnight scanning, nonstop coverage and push alerts.
3. Groww market data alone is insufficient to validate the remaining fundamentals, catalysts, macro factors and net trade target. The app deliberately returns **NO SIGNAL** even when it observes a breakout. A price breakout is not an executable BUY.
4. Formula target 90% accuracy remains **unproven**. No orders can be placed.
5. Android 13+ requests notification permission for breakout/watch alerts.
6. The historical 20-day high is an **observed breakout level**, *not a predicted profit target*.

Build using GitHub Actions workflow `Build MathFormula APK` on branch `feat/alpha30-android`. Download `MathFormula-debug-apk` artifact. See [Monitoring & UX Audit](docs/MONITORING_AND_UX_AUDIT.md) and [Groww](docs/GROWW.md).

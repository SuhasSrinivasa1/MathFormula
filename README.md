# MathFormula v1.3.0 — Alpha-30 NSE Research

## What changed
Read-only Groww watchlist with:
- **Live** view: observed prices, prior 20-session breakout resistance, freshness indicators, completed 5/15-minute confirmation and explicit next condition.
- **Pre-close** view: watches selected stocks with attention to 14:45, 15:00, 15:15, 15:25 IST. The event date is **manually entered** and NOT a verified exchange filing.
- **Runners & misses** view: observed peak return and drawdown since the *first valid quote captured on this device*. Flags first observations already up 10% as potential late discovery. This is not market-wide performance auditing.
- Local audit history of state changes, missed watches and monitoring checkpoints.
- Groww TOTP Token + Secret encrypted using Android Keystore; **zero order endpoints**, no broker keys committed.
- Phone quotes every minute; 5-minute candle refresh on 5-minute boundaries. Android foreground data-sync service explicitly started by user.
- At 2:30 PM the monitoring notification prompts the user to open MathFormula and tap **Renew close coverage** for the closing hour. This is only best effort because Android 15+ targets limit background `dataSync` service time.

## Fixed formula
`app/src/main/java/com/mathformula/alpha/Alpha30Engine.java` is unchanged, still `Alpha-30-v1.0`, 30 immutable weights (fundamentals 27, catalysts 23, macro 19, technical 31), the same score limits and gating. **A price breakout alone NEVER triggers BUY**. The 90% win goal is unproven.

## Known missing components
A **full-universe** (all NSE instruments) overnight + intraday scanner, licensed price streaming, server infrastructure, real earnings calendar and timestamped filings, point-in-time fundamental/macro data, and calibrated execution targets are NOT included. The APK only monitors up to 20 user-defined symbols. No guaranteed unattended all-day coverage, no post-close filings collection and no order placement.

## Usage
1. Install the APK; open Groww Settings; enter your private TOTP token and secret (do not commit or send them).
2. Edit up to 20 NSE symbols.
3. During NSE market hours, tap Start background; allow notifications.
4. Check Live / Pre-close / Runners & misses; view Audit History for timestamps.
5. For final hour Android coverage, open the app around 2:30 PM, tap Renew close coverage; independently verify the subsequent Last Scan timestamp.
6. If quotes are old or feed unavailable, the app must show LAST OBSERVED / NO SIGNAL, not a BUY.

Build: GitHub Actions workflow `Build MathFormula APK`; download `MathFormula-debug-apk`. Consult [the v1.3 user and refresh audit](docs/RELEASE_1_3.md).

The debug build uses Android's debug signing identity; upgrading from an older APK with a different debug certificate may require an uninstall (device-local data may be lost).

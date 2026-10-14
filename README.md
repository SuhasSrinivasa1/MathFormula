# MathFormula v1.8 — Read-only NSE discovery & notifications

**Live / Closed** main interface, automatic price refresh and NSE discovery.
Added device-side **research notifications** and conservative Groww API quota
limits. Android requests notification permission on first launch and schedules
a best-effort recurring background job (~15 minute minimum; OS may delay).

### Types of notifications
- **Breakout watch:** fresh Groww quote crosses the previous 20-session high.
- **Technical confirmation:** completed five-/fifteen-minute breakout and
  VWAP confirmation plus a verified spread threshold.
Notifications are deduplicated per stock/type/trading day. Neither is a BUY;
**Alpha-30 v1.0 remains mathematically unchanged**. No orders are placed.

### Quota guardrails
Groww Live Data's published quota is 10 requests/second and 300/minute
shared across LTP/Quote/OHLC. MathFormula limits itself to a maximum 150
live calls/minute, at least 400ms apart; historical data capped at 30/min
locally; TOTP token exchanges capped at 8/day in the app process.
Tokens are reused from encrypted app storage until the documented daily
06:00 IST expiry. All rate limits are per app process; external Groww
consumption cannot be seen or controlled.

### Important limitation
**There is no guarantee of background notifications.** Android may defer the
minimum-15-minute job and the independently hosted NSE scanner and FCM push
have NOT been deployed. This version gives best-effort device alerts, not
uninterrupted cloud-based alerts. Never assume that no notification means
that no qualifying stock existed.

See [notification and quota documentation](docs/NOTIFICATIONS_QUOTAS_V18.md),
[server deployment](server/README.md) and
[diagnostic export](docs/EXPORT_DIAGNOSTICS.md).

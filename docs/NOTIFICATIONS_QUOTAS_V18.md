# MathFormula v1.8 — Automatic research notifications, within conservative Groww budgets

## Behaviour
- The home screen is still **Live / Closed**. No manual refresh button.
- On first launch, app requests Android notifications permission (Android 13+).
  The user must grant it and enable notifications in Android settings.
- The app registers Android's **JobScheduler periodic job** (minimum 15 minutes).
  When Android lets it run during NSE hours, it uses existing encrypted Groww
  TOTP access and attempts broad NSE discovery if the saved scan is >15 minutes
  old; then verifies up to three latest candidates.
- While the app is open, discovery follows its existing 15-minute schedule,
  accelerating to 5 minutes after 14:30 IST. Top candidate quote refreshes
  approximately every 30 seconds. Alert evaluation is attempted about once
  every 5 minutes while open, and during background jobs whenever they execute.
- Alert types:
  0. **New research shortlist** — a new top-ranked stock is within 1.5% below
     to 3% above verified resistance, with sufficiently recent quote and
     preliminary technical ranking; limited to at most two per day and
     separated by at least one hour.
  1. **Price breakout watch** — a Groww exchange-timestamped quote not older
     than 30 seconds crosses a validated prior 20-session resistance reference.
  2. **Technical setup confirmed** — two completed 5-minute candles and a
     completed 15-minute candle close above resistance and Groww average
     traded price, with verified bid/ask spread <=30 bps.
- A single notification of each type per stock per local trading date;
  tapping opens MathFormula. No BUY labels, orders, position sizing or
  unsupported predictions. The full 30-factor Alpha-30 BUY remains unavailable.
- If a quote is stale, a historical reference unavailable, the market is
  closed, or the data feed fails, **no bullish alert is produced**.
- Live scanner-status card displays notifications permission, last evaluation
  time, errors and explicit Groww budget policy.
- Diagnostic export includes `quota_and_alert_health.json` with local request
  counters, permission state, alert-check timestamp and policy; no credentials.

## Published Groww quotas (checked October 2026)
Source: https://groww.in/trade-api/docs/curl
- LIVE market data (quote, LTP, OHLC together): 10 req/sec and 300 req/min
- AUTH: 5 req/sec, 30/min, token exchange <=150/day
- Instrument batch LTP and OHLC: up to 50 instruments/call
The app enforces process-shared conservative quotas:
  - LIVE <=150 calls per rolling minute, minimum 400 ms between calls
  - HISTORICAL <=30 calls/min, minimum 1.5 seconds between calls (separate
    conservative budget; Groww docs do not provide this category's assured cap)
  - AUTH <=8 calls per 24 hours; reuse encrypted 6:00 AM-expiring Groww
    access tokens across app foreground and background job processes.
  - On HTTP 429 apply a local cooldown >=2 minutes.
These are DEVICE-level guards and CANNOT account for usage by other Groww apps,
other devices, accounts, servers or independent API sessions. The actual account
quota can still be reached externally. Groww can change rates.

## Android limitations
- JobScheduler's 15-minute interval is **not a guaranteed exact schedule**:
  battery saver, Doze, force-stop, no connectivity, OEM policies and OS quotas
  may postpone or prevent checks and alerts. An APK alone cannot guarantee
  early breakout or fully uninterrupted full-universe monitoring.
- Android 15 dataSync foreground services have a cumulative 6-hour/24-hour
  background cap and are not an appropriate permanent scanner.
- Native remote Firebase Cloud Messaging **is not configured** and the
  independently deployed server is not online. The existing server code is
  not proof of live deployment.
- Production-grade "do not miss opportunities" requires a licensed,
  continuously running hosted market scanner, full point-in-time 30-factor
  feeds, and FCM push with retries/monitoring; none is falsely labelled done.

## Model integrity
Alpha30Engine.java, Alpha-30-v1.0, weights and all fixed hard gates are
unchanged. Discovery heuristic and technical breakout alerts are research
signals and are NEVER executable BUY recommendations.

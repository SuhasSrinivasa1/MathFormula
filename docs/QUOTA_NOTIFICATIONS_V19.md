# MathFormula v1.9.0 — Research notifications and conservative Groww quota

## Confirmed Groww REST limits
Groww official cURL API docs currently list a shared Live Data category (Quote,
LTP and OHLC) of 10 requests/second and 300 requests/minute. Groww token exchange
is limited separately (5/second, 30/minute, 150/day). The app applies:
- 6 Live Data requests/second and 210 Live Data requests/minute, across Android
  foreground scanner, background ResearchJob and legacy ScannerService.
- A local 90/day token-exchange ceiling.
- A 75-second cooldown upon HTTP 429 (applied to local data requests).
- Historical candles capped to 45 requests/minute and 250 ms spacing as a
  conservative engineering policy, not a claimed officially documented limit.

These are **per Android app process**. Other devices, other Groww integrations,
or separately hosted services using the same Groww account are not observable;
therefore no client can guarantee staying below the *aggregate account-level*
quota unless all apps share a single centralized limiter.

## Notifications
Existing Android v1.8 alerts retained and enabled:
- new qualified **research candidate** (max 2/day, cooldown);
- price-breakout watch (one per symbol/stage/day);
- completed technical confirmation, only if 5m/15m and VWAP/spread pass.

Every notification is **NOT BUY** because financial, macro, corporate-event and
risk factors for Alpha-30 are not fully connected.
Notification permission is requested on Android 13+.

The OS schedules a background ResearchJob nominally every 15 minutes
and may delay, skip, or stop it for Doze, battery optimization, connectivity
and app restrictions. The app does not have always-on FCM push or a
deployed cloud NSE scanner. No missed-opportunity guarantee is possible.

## UX
Only Live and Closed main sections, auto-refresh when screen is open,
quota status and last alert check under Live.
Export diagnostic ZIP from Closed, with no secret values.

## Testing
CI compiles APK and Python server. Authenticated quote rates, device-specific
JobScheduler delivery and notification permission still require testing on
the user's phone. Never interpret price breakout as a verified BUY.

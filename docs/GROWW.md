# MathFormula — Direct Groww TOTP Integration

## What this release does
- No static IP requirement for data-only requests. Groww's mandatory static IP is for API order placement.
- Configure Groww **TOTP Token + TOTP Base32 Secret** directly in app Settings, like Multify/Univest. It computes a six-digit RFC 6238 TOTP locally and obtains an access token through POST /v1/token/api/access (key_type=totp).
- Stores both the TOTP token/secret and access token encrypted using Android Keystore AES-GCM. No secrets in GitHub, debug logs, preferences in plaintext, backup, or APK constants.
- Directly uses **only** GET /v1/live-data/quote and GET /v1/historical/candles; plus the authentication POST. Does not import or call any order create/modify/cancel APIs.
- Displays NSE price, day % move, Groww-reported traded volume, average traded price, bid/ask spread, quote timestamp, completed 5-minute candles and available 15-minute confirmation.
- User can select a watchlist of up to 20 symbols. Foreground refresh every 5 minutes, and manual Refresh. A phone is not a replacement for a permanent full-NSE overnight scanner.
- **NO SIGNAL**, not fabricated BUY, if the entire 30-factor dataset, RVOL, credible target, or trade permission cannot be verified. The Alpha-30 mathematical weights remain frozen.

## Setup
1. On Groww, subscribe to the Trading APIs (if necessary) and go to Trading APIs → Generate TOTP token.
2. Copy your **TOTP Token** and **TOTP Secret** into MathFormula Settings. Do **not** paste either in GitHub issues, chat, or code.
3. Use Refresh. Authentication can fail if the device clock is incorrect or Groww's rate-limits are triggered. Session credentials expire daily (typically 6 AM IST); the app re-authenticates as necessary.
4. The phone needs connectivity. If no trade has happened for >10 seconds, the quote is explicitly marked stale; this does not mean the last price is zero.

## Architecture limitation and next milestone
Groww market data supplies technical features, **not** all quarterly fundamentals, geopolitical news, institutional flows or earnings surprises. Completing the NSE-wide overnight scan and net expectancy requires a separately hosted data-engine and a research-source feed. No static IP is needed solely for view-only usage.

The Android app does not place trades or request trading permissions; no API endpoint for orders exists in code. The access token nevertheless has the entitlements of the user's Groww key. Protect phone credentials and revoke the key from Groww if a device is lost.

Official docs:
- https://groww.in/trade-api/docs/curl
- https://groww.in/trade-api/docs/curl/live-data
- https://groww.in/trade-api/docs/curl/backtesting
- https://groww.in/blog/static-ip-api-trading-setup

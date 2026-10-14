# MathFormula v1.6 — Auto NSE Research Discovery (phone)

## What is activated
- Uses the **Groww TOTP credentials already saved on this Android device**.
- Fetches Groww's official instrument master daily and filters NSE CASH, EQ series.
- Screens the NSE universe in batches of 50 with Groww LTP and batch OHLC.
- Initial prefilter uses **intraday open-to-price momentum** and position in today's range, *not* the Alpha-30 score.
- Verifies up to 55 leading technical candidates against at least 60 completed daily candles, previous 20-session high, estimated 20-session traded value, current detailed quote and freshness.
- Excludes already highly extended candidates (over +8% from previous close), poor proximity, insufficient history and estimated illiquidity; returns the top **three research candidates** or an empty state.
- Automatically refreshes when the app is in foreground during NSE market hours, approximately every 15 minutes normally and five minutes after 2:30 PM.
- The new launcher has three sections: Opportunities, Watchlist and History; the legacy detailed controls are under Settings.
- Candidate replacement requires a **completed** scan. Old candidates are archived, never silently recycled into Top 3. Historical picks are not marked BUY.
- Diagnostic ZIP includes local_nse_discovery.json, whose universe coverage and selected-candidate decisions are point-in-time records.

## Important boundaries
- **There is still no remotely deployed cloud scanner.** The existing phone Groww connection cannot automatically authenticate a separate server; server secrets remain server-only.
- This is a foreground, best-effort phone scanner. Android can defer or stop background work; no guarantee of unattended all-day monitoring. The prior manual 20-stock foreground service is still available in Advanced Settings, not a substitute for the NSE-wide server.
- The *technical* ranking is not the frozen Alpha-30 formula. The formula weights/gates in Alpha30Engine.java remain unchanged and no BUY signals are generated without fundamentals, news, macro, time-adjusted RVOL, liquidity and risk/reward.
- Prior 20-session high is technical resistance; liquidity is estimated from daily close × volume and is not official turnover. NSE eligibility uses actual completed history and current quotes.
- Universe is not necessarily all ~4,000 NSE shares. The app reports actual downloaded eligible symbols, batch successes, returned prices, histories checked and qualified count. If coverage falls below 70% (minimum 200 quotes), it rejects the scan. Official Groww market data is subject to API entitlements and rate limits.
- A new download cannot be assumed to preserve previously entered Groww TOTP if the existing install was signed by a different debug key. Review Android installation warnings before uninstalling (which loses local history).
- Data collection and lookups are **not validated on the user's authenticated device**, even though CI compiled the APK. Upload the exported ZIP to check for any API response/permission issues.

## Future
For complete unattended market coverage and fast pre-close alerts, deploy the existing Python/Docker NSE-wide research server with Groww credentials provisioned **directly on that host**, full EOD history, corporate events and TLS viewer API. The APK does not silently transfer the phone's TOTP secret to a server.

## User flow
1. Install v1.6 and open MathFormula.
2. If Groww credentials already exist on this device, the scan starts automatically while NSE is open. Otherwise tap the Settings gear and connect.
3. See Top 3 and checked universe counts. A scan may require multiple minutes for bulk quotes and daily-history verification.
4. Tap Watchlist for selected candidates; History for archived selections, export diagnostic logs.

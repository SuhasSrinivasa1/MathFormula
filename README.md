# MathFormula v1.4.0 — NSE-wide discovery + Groww research dashboard

**Repo:** Android APK + Python server. **Alpha-30 mathematical formula remains frozen v1.0**; no broker order endpoints exist.

## Android
The new **NSE Scanner** section connects to an independently hosted HTTPS MathFormula server.
Tap **Server Settings** and supply your scanner's HTTPS domain and the separate
`APP_READ_TOKEN` (never enter your Groww credentials in this field). Viewer token is
stored encrypted with Android Keystore. Monitor:
- Real actual EQ universe size, NSE daily-history coverage and last completed scan.
- Top 30 technical research candidates, their 20-session breakout prices, ranking and missing
  frozen model factors; never falsely marks technical rank as an Alpha-30 BUY.
- Latest pre-close 14:45 / 15:00 / 15:15 / 15:25 IST summaries.
- Verified five-session future outcome audits, explicitly distinguished from selection-time data.
- Complete last-update timestamps, stale quote notices and no-data states.

The earlier **Live**, **Pre-close** (local watchlist), **Runners & Misses** (local), and
Groww TOTP functionality remain available. Android's foreground monitoring is best effort;
the independently deployed server owns continuous discovery.

## Server
See [server deployment guide](server/README.md). The scanner ingests actual NSE EQ
instrument CSV and dated official EOD full-bhavdata, and uses Groww's 50-instrument LTP batches
for broad snapshots. It maintains stored research snapshots and audits on SQLite.
The server offers authenticated **read-only** `/v1/status`, `/v1/watchlist`, `/v1/preclose`,
`/v1/misses`, `/v1/events`, `/v1/audit`.

Cloud deployment, Groww TOTP credentials, exchange/provider access, DNS, TLS and official EOD
history bootstrapping are required. **Merely building the APK does not deploy the server.**
Only sources actually present contribute to features; model completeness and actual
eligible universe coverage are always explicit.

## Important limitations
- The NSE-wide discovery list is a documented *price/volume heuristic*, not Alpha-30's
  normalized fixed 30-factor score.
- Full earnings, analyst, macro, ownership and validated news inputs are **not** provided by
  Groww alone; points cannot be filled with guesses.
- No validated 90% win probability, no untested overnight earnings strategy, and **no BUY**.
- No Firebase project supplied: optional HTTPS webhook notifications are implemented
  server-side, but native server-to-Android push remains unconfigured. The dashboard
  syncs while foregrounded.
- NSE EQ normal equity only, at least 60 past trading days; newly listed IPOs need a separate
  validated model and are deliberately excluded from this fixed-model shortlist.

## Build and CI
GitHub Actions `Build MathFormula APK` uploads `MathFormula-debug-apk`.
`MathFormula Server CI` checks Python unit tests, source syntax and Docker image build.
Debug APK Android 8.0+; installing over a differently signed debug package may require
uninstallation and re-entering stored credentials.

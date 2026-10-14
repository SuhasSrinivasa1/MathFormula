# MathFormula v1.7 — Two-section Home: Live and Closed

## Home navigation
There are ONLY two tabs: **Live** and **Closed**. Advanced authentication
controls remain behind the settings gear.

### LIVE — current market opportunities
- Default tab while NSE is within normal Monday–Friday 09:15–15:30 IST hours.
  Exchange holidays remain an explicitly identified missing feed.
- Automatically initiate NSE discovery using the Groww TOTP credentials
  encrypted on the device, without a Refresh button.
- Broad NSE screening repeats approximately 15 minutes, or 5 minutes from
  14:30 IST, only while the app is foregrounded. Scans can take several
  minutes and cannot be guaranteed on Android in the background.
- The top three **research** selections change after a completed, valid scan.
  Three stocks aren't mandatory. If data are stale, incomplete or no
  stocks qualify, the user sees an honest empty/coverage state.
- Each card shows NSE symbol, last seen price, day move, prior 20-session high
  (expected breakout), distance from resistance, last-trade time and
  **STALE** when the trade timestamp exceeds 60 seconds. Price quote
  refresh is attempted roughly every 30 seconds for only the top three
  while the app is active; this is distinct from a complete NSE rescan.
  Any quote refresh does NOT alter frozen model gates, candidate ranking
  or the recorded full scan time.
- Price data are observations, not actual broker orders. **NO SIGNAL** remains
  until the 30-factor model and executable trading gates can be verified.

### CLOSED — completed-session review
- Default tab outside regular trading hours; available anytime by tapping Closed.
- Shows the most recent session for which an NSE screen actually completed
  before/after that day's exchange close. Session date and exact data
  observation timestamp are displayed.
- A session snapshot contains the three research candidates (or empty state),
  scan coverage, the observed price and expected breakout at the time of the
  scan. It is NOT a verified final exchange auction/closing price.
- When a trading session is still in progress, Closed shows only a **previous**
  completed session, never the current unfinished trading day.
- Keeps up to 35 daily snapshots and 80 archived selection records.
  Replaced stocks remain accessible under Previously Tracked.
- **Export diagnostic logs (ZIP)** is placed in Closed instead of the Live
  dashboard. Its local_nse_discovery.json includes the daily session
  records and archived picks. Groww credentials and tokens aren't exported.

## Reliability
- If the app is closed or loses its connection, Live must not invent a
  current quote or silently show an old shortlist.
- A capture at 14:45 is described as the **last observed scan**, never as the
  15:30 close; if no data were recorded, Closed states so.
- An unavailable NSE-wide market history never becomes an official return.
- A separately hosted scanner is still required for uninterrupted coverage,
  fully automated exchange holiday handling, official close prices,
  live filings and complete Alpha-30 factors.

## Model integrity
The **Alpha30Engine.java** mathematical weights, frozen formula version and
score thresholds are unchanged. Technical discovery ranking continues to be
separate from Alpha-30 and cannot issue BUY automatically.

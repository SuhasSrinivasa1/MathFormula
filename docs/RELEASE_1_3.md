# MathFormula 1.3 — Refresh and usability audit

**Fixed formula:** all values in Alpha30Engine.java remain identical to Alpha-30 v1.0.
**No actual BUY:** direct Groww market feeds are insufficient for all thirty factors. A crossing is not an executable signal.

## User-facing additions
- Clear sections: Live, Pre-close, Runners & Misses.
- Visible expected breakout = prior 20 completed sessions highest high; explicit date and distance.
- A stock stays in the user's watchlist until manually removed; errors, staleness and waiting states remain visible.
- Local earnings-calendar tags are **manually entered, not verified company filings**. The user may enter an ISO date per symbol; this is an event reminder and risk indicator, not a prediction.
- Pre-close checkpoints: 14:45, 15:00, 15:15, 15:25 IST. Only monitored symbols can be reviewed; these are observations, not full-universe discovery.
- Runner tracking from **first observed price on this device**: observed peak gain, last observed gain and drawdown. Not a forward forecast or backtest.
- A watchlist-only "late discovery" observation is flagged if first observed after +10% session performance. True NSE-wide missed winners require the server pipeline.
- Scan heartbeat, last successful poll, last error, quote timestamp, source refresh and completed candle timestamp are exposed. Saved audit is local and can be lost if the app is uninstalled.

## Background constraints
Android 15 target 35 limits background dataSync foreground service to six hours in any rolling day; a six-hour-and-fifteen-minute NSE market session exceeds it.
- The code makes background coverage **best effort**: 1-minute polling of monitored symbols and 5-minute candle updates while active.
- Around 14:30, proactively remind the user to open the app to refresh the monitoring session for the final hour. Bringing the app to the foreground resets Android's service budget per its published behaviour.
- The service advertises estimated stop and remaining coverage, and stops cleanly when the cap is reached. Re-starting automatically without user involvement is not permitted.
- No automatic overnight activity, and no OS-level guarantee of every quote or alert. To avoid missing opportunities, deploy an independent market-data backend and push notifications.

## Backend requirements (not deployed)
Full NSE stock master and EOD updates, corporate filings with dissemination timestamps, scheduled results, financials, industry/macro features, 4,000-symbol screening at 15-minute intervals (5-minute intervals after 14:30), streaming 20–30 instruments, five-minute scoring, point-in-time persisted candidate and outcome history, security and monitoring. A broker quote or a stock-specific 20-day resistance alone cannot satisfy frozen Alpha-30.

## User acceptance checks
1. Connect Groww via Settings on the phone and verify timestamps/quotes/candles.
2. Enter a forthcoming earnings date, then check the PRE-CLOSE tab for event warnings.
3. Observe 14:45/15:00/15:15/15:25 checkpoint entries in Audit History.
4. Lock the phone; confirm the foreground notification and per-minute Last Scan update. If Android stops it, confirm COVERAGE GAP.
5. Review Runner tab after at least two valid observations. First-observed baseline must be shown.
6. Verify fifteen-minute confirmations require a completed fifteen-minute candle **closing above** breakout/VWAP.
7. Confirm no order-placement API exists, no broker secrets are stored in GitHub, and no BUY is issued without complete model factors.

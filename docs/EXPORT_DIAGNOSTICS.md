# MathFormula v1.5 — Diagnostic Log Export

Inside MathFormula tap **Export Logs (ZIP) · share for review**, choose an Android
folder in Files, then upload the resulting ZIP here. No upload is performed by
the app, and no storage permission is required.

ZIP entries:
- manifest.json — export time, formula version, counts and source availability
- fixed_formula.json — Alpha-30 v1.0 factors/weights and the frozen signal gates
- local_watchlist_and_health.json — watched symbols, latest cached price,
  breakout references, timestamps and phone monitoring status
- observations.jsonl — up to two ~3 MB segments of minute-by-minute snapshots,
  recorded **starting with this update**, including price, breakout level,
  volume, VWAP, pending candle confirmation and source timestamps
- state_changes_and_errors.jsonl — bounded state transitions, pre-close
  checkpoints, coverage interruptions, and redacted diagnostics
- server_research_history.json — optional when an independently hosted server
  is connected: bounded historical shortlist, scanner windows, outcomes,
  event metadata and latest factor values; row totals expose truncation

The export does NOT read Groww TOTP token/secret, access token, viewer API token,
API headers, app-private preferences, account numbers, device identifier or
server URL. Error notes are sanitized for URL, email and credential-shaped
strings. The bundle still reveals your watchlisted symbols; review before
sharing.

Important: The local app only stores watched-stock observations, not the whole
NSE tick tape. Server research history has capped row counts, and imported
factor values are **latest** values, not a fully historical event-sourced
factor store. Backtests must not rely on postdated facts. Price breakout and
preliminary discovery ranking are NOT executable Alpha-30 BUY signals.

Uploading a ZIP allows discussion of whether the scanner missed an
opportunity or suffered stale data. It does not automatically modify the
formula. Any proposal to modify Alpha-30 needs out-of-sample point-in-time
evidence across winners and losers, including costs and slippage, followed by
a new explicitly named formula version.

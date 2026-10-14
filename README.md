# MathFormula v1.6 — Automatic NSE Discovery

The launch screen is now **Opportunities · Watchlist · History**, not the previous
large button grid. It uses the **existing Groww TOTP connection on the phone**
to discover NSE CASH/EQ opportunities through Groww's instrument master, batch
LTP + OHLC, and selected daily-candle history. No manual stock selection or
refresh button is required.

- Fresh qualified candidates replace older ones after a completed scan.
- Top three is a **research discovery rank, NOT an Alpha-30 BUY**.
- Prior 20-session breakout high, distance, quote timestamps, actual
  scan coverage, historical readiness and error conditions are displayed.
- Older selections move into the History tab and enter exported diagnostics.
- An empty state is displayed instead of hardcoding TARIL, GAEL or ADVANCE.
- Frozen 30-factor Alpha-30 mathematical formula unchanged.

**On-device scanning is not a substitute for a deployed always-on server.**
No cloud host has been provisioned; see [deployment guide](server/README.md).
For detailed reliability/limitations see [v1.6 documentation](docs/ACTIVATION_V16.md).

# MathFormula — Alpha-30 Android App

An Android research companion implementing the **30-factor fixed Alpha-30 v1.0 mathematical score** and strictly gated BUY ELIGIBLE / WAIT / NO SIGNAL outcomes.

**Build:** GitHub Actions → Build MathFormula APK → successful run → Artifacts → `MathFormula-debug-apk`. Or build with Android SDK 35, Java 17, Gradle 8.9 using `gradle :app:assembleDebug`.

**Offline:** displays TARIL, GAEL and ADVANCE with clearly labeled **13 Oct historical closing prices**, never as live quotes.

**Live:** enter an HTTPS backend URL with the `/v1/watchlist` contract documented in [docs/API.md](docs/API.md). The Android app validates timestamp freshness (<10 seconds), factor coverage, formula version, completed candle confirmation, VWAP, time-adjusted volume, market, spread/liquidity and net risk/reward. It only provides read-only research signals and does **not** place trades.

**Formula weights:** fundamentals 27 + catalysts 23 + macro 19 + trading 31 = 100; score = sum of (fixed weight × normalized factor). Overnight available-factor score uses first 29 factors with normalization 100/91. **A score is not a calibrated probability. The 90% precision objective has NOT been verified.**

**Not included:** connected licensed broker streaming data, full-universe fundamentals/news feed, empirical backtest, always-on server scheduler, user authentication, broker trading or an investor-grade deployment. Connect these through the secure backend; do not put broker keys in the app.

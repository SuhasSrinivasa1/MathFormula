# MathFormula — Alpha-30

An Android research prototype for the fixed Alpha-30 NSE stock-screening formula (30 weighted factors). The GitHub Actions workflow builds an installable debug APK. The app is read-only; no trading orders are placed. All BUY/WAIT decisions require timestamped verified broker data and 5-minute/15-minute confirmation. The 90% win-rate objective is **not validated**.

See source and CI workflow on the `feat/alpha30-android` branch. All broker credentials belong on a secure backend, never inside the APK.

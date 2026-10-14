# MathFormula — Alpha-30 (Groww TOTP Read-only)

Android NSE stock research app with **direct Groww authentication** using TOTP Token + Secret, Android Keystore-encrypted credentials, actual timestamped market quotes and historical candles.

**No static IP or order execution.** The app calls only official Groww authentication, quotes and historical candle endpoints.

**Build APK:** GitHub Actions → `Build MathFormula APK` → `MathFormula-debug-apk` artifact. Android 8.0+ / Java 17 / SDK 35.

**Run:** Settings → enter Groww TOTP Token and TOTP Secret → Save & Connect. Update NSE watchlist (max 20 symbols). Market-data refreshes while app is open every 5 minutes.

**Important:** Groww only provides the price/volume part of the research. A full NSE overnight fundamental/macro/news scan and validated BUY signal require additional data. Until then the frozen 30-factor Alpha-30 model fails closed with **NO SIGNAL**; its 90% precision target is not validated.

See [docs/GROWW.md](docs/GROWW.md) for usage and data safety.

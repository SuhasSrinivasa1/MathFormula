# MathFormula v1.5 — Exportable Alpha-30 Diagnostic Logs

MathFormula is a read-only Android Groww watchlist with an independently
hosted NSE-wide research scanner. The **Alpha-30 v1.0 mathematical formula
and all 30 factor weights remain unchanged**.

**New:** Tap **Export Logs (ZIP) · share for review** in the Android app,
save the ZIP using the Android document picker, and upload it to ChatGPT.
The export captures bounded local market observations, monitored symbols,
data freshness, breakout transitions and errors, the model/version and
optionally historical server research records. **No Groww secret, API token,
password, account ID, device ID or backend URL is exported.**

See [detailed ZIP schema and audit guidance](docs/EXPORT_DIAGNOSTICS.md).
Log recording starts with installation of v1.5 and cannot reconstruct data
that was never observed. The APK does not deploy the backend; see
[server deployment instructions](server/README.md).
Full Alpha-30 BUY decisions require verified financial, catalyst, macro
and execution inputs not currently supplied by Groww. A market-research
score or breakout is NOT proof of a profitable trading signal.

CI workflows: `Build MathFormula APK` and `MathFormula Server CI`.

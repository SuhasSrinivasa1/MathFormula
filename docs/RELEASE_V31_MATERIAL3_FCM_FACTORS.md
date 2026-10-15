# MathFormula v3.1 Material 3 + Research Notifications — QA

**Frozen Alpha-30-v1.0 remains unchanged.** Build package com.mathformula.alpha.stable.qa, debug-signed by GitHub Actions.

## What is implemented
- Material3.Light theme, real MaterialCardView and MaterialButton components, Material TabLayout and outlined Material TextInputLayout for Groww credentials. Uses AppCompatActivity.
- Improved data-health presentation for local notifications versus configured Firebase, and existing Groww/scanner/data age statuses.
- FirebaseMessagingService registered for receiving research-only, timestamp-bounded, deduplicated event notifications when a Firebase project and authenticated sender are configured. All pushes explicitly say NOT BUY. Handles deleted message diagnostics and token availability.
- On-device 15m batched Groww NSE radar (existing v3.0), resumable off-hours discovery, foreground quotes and best-effort JobScheduler after the app is launched, subject to Android/OEM restrictions.
- Corrected next-session H20 rolling window: last 20 *completed* sessions, including prior day. Historical cache version upgraded to invalidate old incorrect references.
- Verified completed daily candles automatically produce T29 close-location factor 0..1 with a provenance URL, public timestamp and SQLite persistence. T29 is just **one** of the 30 factors; others stay missing unless trustworthy normalized evidence is ingested.
- Research watch notifications now consider overnight session validity separately from quote freshness and can identify newly discovered intraday radar stocks with fresh exchange last-trade quotes. Does not certify BUY.
- Rulebook full model unchanged: weights F01–F10=27, C11–C17=23, M18–M25=19, T26–T30=31; coverage>=90%, overnight watch>=68, live BUY>=78 and all 12 hard gates.

## Important missing / not production ready
- **No configured Firebase sender, google-services.json, backend deployment or device token registration.** Firebase receiver alone does not deliver pushes.
- **No guaranteed background/closed-phone scanning.** Vivo Funtouch OS, Doze, Android 15 6-hour/24-hour dataSync limit and 15-minute JobScheduler minimum can delay/stop on-device scanning. Force-stopped apps cannot receive FCM normally.
- **All 30 automatically collected factors are NOT available.** Groww price/candle APIs do not contain dated financial statements, earnings surprises, analyst consensus, guidance, ownership, primary news, currency sensitivities, global flows, etc. An approved data-provider integration is required. Do not infer any BUY from incomplete factor coverage.
- A complete future Material3 *design-system overhaul* (icons, adaptive layout, comprehensive accessibility tests/screenshots, coherent typography, dark theme, Compose rewrite) has not been tested; this APK migrates **core screens/components** only.
- Broker authorization still depends on daily Groww token lifecycle and user account approval. Android APK cannot silently bypass Groww.
- No current Groww official live fee schedule, independent execution-grade feed or net R:R certified; **no verified Alpha-30 BUY** from phone-only mode.

## Install
- This QA APK may be signed with a different GitHub runner's debug key than v3.0 QA; export logs first and uninstall old MathFormula QA if Android rejects an in-place update.
- Uninstall deletes local Groww encrypted credentials and scan/evidence state. Re-enter Groww TOTP API token and Base32 secret under Settings.
- Permit notifications, approve broker account access, open battery settings and grant background permissions if applicable; this cannot override all Android restrictions.
- Verify actual source timestamp, NSE coverage and factor availability in the dashboard. Do not trade from research-only observations.

## Remaining production tasks
1. Arrange eligible official/publisher data-source access and time-stamped normalization for 29/30 factors; validate against rulebook mapping.
2. Deploy independent always-on scanner with secure broker credential vault or approved data feed, quota governor and audit DB.
3. Create Firebase project, package-specific google-services config, register authenticated device token and authorize event sender; test push when app closed on Vivo.
4. Complete Material3 design migration, device accessibility/instrumentation, stable signing certificate and broker API acceptance tests.
5. Paper-test both missed-opportunity audits and candidate-driven valid G01-G12 BUY/NO-SIGNAL transitions.

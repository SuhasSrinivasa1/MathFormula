# MathFormula v1.9 — Groww quota-aware research alerts

Two-section UI: **Live** and **Closed**. Automatic on-screen discovery and
best-effort Android background research alerts; no trading or BUY signals.

The Groww API request governor shares a conservative Live Data limit of
**6 requests/sec and 210 requests/min** between discovery scans, quote refresh,
background research alerts and the legacy monitoring service. Groww's published
Live Data limit is **10 requests/sec and 300/min**. The app also enforces
a local auth budget and cooldown on 429; it does NOT know requests made in
other apps under the same Groww account.

See [limits, limitations and background alert notes](docs/QUOTA_NOTIFICATIONS_V19.md).
**Alpha-30 v1.0's mathematics remain unchanged**. All notification content
is labelled Research / NOT BUY until complete model and execution verification.

A deployed 24/7 scanner and server-to-phone push service remain necessary for
reliable unattended operation; the Android OS can defer background jobs.

# MathFormula: NSE-wide scanner server (v1.0)

Read-only NSE cash-equity discovery service, independently running from the phone. It **does not place trades**, and it **does not imply that the full Alpha-30 model is operational** unless point-in-time fundamental/catalyst/macro sources are supplied.

## Prerequisites
- A Linux VPS/cloud instance with Docker and publicly reachable HTTPS domain. This is the always-on component; GitHub Actions is not a persistent scanner.
- Groww Trading API subscription, Groww TOTP token and secret. Put both ONLY in `server/.env` on the host; NEVER in the APK or repository.
- A legitimate NSE-listed EQ cash symbol master, updated daily. Source: [NSE securities available for trading](https://www.nseindia.com/static/market-data/securities-available-for-trading) or [Groww instruments CSV](https://groww.in/trade-api/docs/python-sdk/instruments). Mount as `server/data/universe.csv`.
- 60 or more *past completed trading sessions* for each eligible stock, incl. rupee traded value (avg 20-day >= ₹1Cr). Import actual licensed/official history, never fabricated data.
- An operator-provided `server/data/eod/*.csv` set with canonical header **symbol,date,open,high,low,close,volume,turnover_inr**. The importer intentionally requires explicit schema rather than guessing NSE UDiFF column units.
- Optional sourced events CSV `symbol,event_type,title,published_at_utc,scheduled_date,source_url`; event `published_at_utc` must be real dissemination time with timezone.
- Optional sourced 30-factor CSV `symbol,factor,value,observed_at_utc,source`; values 0..1, with past observed timestamps and provenance.

## Official NSE file automation
With `AUTO_NSE_IMPORT=1`, the worker attempts the NSE EQ master at 08:00 IST
and the completed NSE full-bhavdata report at 16:45, 17:45 and 19:15 IST.
NSE network access and any applicable license are required; failures are reported.
The turnover in the full-bhavdata report is expressed in **lakhs of rupees** and is
converted to INR before 20-session average traded-value checks. Schema/date
validation fails closed.

For INITIAL 60+ sessions, run `docker compose run --rm scanner python -m mathformula bootstrap-eod`
after setting your deployment data volume and accepting permitted data usage.
This attempts at most 130 calendar days of official full-bhavdata archives and skips
unavailable exchange holidays; it is not a substitute for a reliable licensed EOD provider.

## Deploy
```sh
cd server
mkdir -p data/eod
cp .env.example .env
# Update DOMAIN, random APP_READ_TOKEN and credentials privately in .env
# Import your real universe and last >=60 daily EOD sessions in data/
docker compose up -d --build
docker compose logs -f scanner
```
DNS must point to the server and ports 80/443 be reachable; Caddy provisions HTTPS. `scanner` has no public port; only Caddy exposes HTTPS.

For manual file import (after deploying CSV):
```sh
docker compose exec scanner python -m mathformula import-universe /data/universe.csv
docker compose exec scanner python -m mathformula import-eod /data/eod/2026-10-13.csv
docker compose exec scanner python -m mathformula import-events /data/events.csv
```
The server also detects changed files during normal operation. Import EOD **only once the official data becomes available**. After close 16:15/19:30 IST it records new decisions, never rewrites historical input data as if it existed earlier.

## Actual discovery schedule (IST)
- 09:15–14:29: all loaded EQ symbols via Groww batch LTP (50 per request), every 15 minutes. Each batch retains server observed timestamps; **bulk LTP has no exchange last-trade timestamp**, so it cannot issue BUY.
- 14:30–15:29: all-symbol LTP every 5 minutes.
- Shortlist detailed quote snapshots: every minute while market open. Detailed quote `last_trade_time` is retained separately.
- Pre-close snapshot: 14:45, 15:00, 15:15, 15:25. Shortlist and as-of evidence recorded, no trade recommendations.
- EOD scan 16:15 and 19:30, only where point-in-time EOD data has already arrived.
- Local event/factor CSV file changes detected every minute. **Not automatic NSE filings ingestion** until a licensed/source-backed event feed writes events.csv.
- Audited 5-session subsequent +10% movers, using only *completed future days* in the outcome evaluation (never as discovery input).

## Data quality and ranking
The primary list is a **technical discovery heuristic**, not the 30-factor Alpha-30 score and **not** a BUY signal. It requires >=60 previous completed sessions, minimum avg20 turnover >=₹1Cr, and sufficient intraday quote freshness. It shows all excluded/eligible counts in server status. Stocks without history are excluded with clear coverage numbers. IPOs with <60 sessions remain a separate strategy not yet implemented.

The actual frozen 30-factor score is calculated only when dated, normalized, sourced inputs are available. A 90% feature-weight coverage minimum is enforced before the score is considered complete. No Alpha-30 BUY is emitted by this scanner because executable 5m/15m + VWAP + slippage/risk controls are not fully integrated. Accuracy above 90% is NOT validated.

## HTTPS read-only API
All `/v1/*` endpoints require `Authorization: Bearer <APP_READ_TOKEN>`. Do not expose the app token or credentials. Health check `/healthz` returns only liveness.

`GET /v1/status`: actual loaded universe, EOD trading sessions, fresh LTP coverage, last scan health.
`GET /v1/watchlist`: top 30 candidates with source, breakout, coverage and *NO SIGNAL*.
`GET /v1/preclose`: last available pre-close window.
`GET /v1/misses`: subsequent +10% historical movers and whether a frozen shortlist captured them.
`GET /v1/events`: operator-imported dated events.
`GET /v1/audit`: time-stamped event and scanner logs.

Set `ALERT_WEBHOOK_URL` only to a trusted HTTPS automation endpoint for PRE_CLOSE_WATCH messages. Actual Android Firebase push notifications are **not configured** without a project service-account and client integration.

## Security and limits
- Groww REST endpoints allowlist only token exchange, `/live-data/ltp` and `/live-data/quote`; order endpoints absent.
- Data-sharing and Groww licensing must permit the configured usage. App viewer token is separate from sensitive Groww credentials.
- A rate-limiter serializes outgoing requests; 4,000 stocks at 50/request = ~80 requests per broad sweep, plus shortlisted quote updates. Respect Groww's subscriptions and limits.
- This is a deployment-ready reference, not a claim of live production deployment, end-to-end integration tests with your Groww subscription, guaranteed push delivery, or complete licensed 30-factor coverage.

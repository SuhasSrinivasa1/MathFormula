# Alpha-30 Integration Contract

## Background
This project contains a FIXED 30-factor research score and the read-only Android interface. It does not contain a connected broker credential or verified live feed. The objective of 90% accuracy remains unproven. The service must return true exchange timestamps, never simulated quotes.

## GET /v1/watchlist
```json
{
  "modelVersion":"Alpha-30-v1.0",
  "stocks":[{
    "symbol":"GAEL","quoteTimestampMs":0,"marketOpen":false,
    "lastPrice":null,"breakoutLevel":174.90,
    "factors":[null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null],
    "gates":{"tradingAllowed":false,"materialRiskClear":false,
      "breakout15m":false,"fiveMinuteConfirmation":false,
      "vwap5m":false,"vwap15m":false,"timeAdjustedRvol":null,
      "sectorPositive":false,"liquidityVerified":false,
      "spreadBps":null,"netRiskReward":null}
  }]
}
```
This example intentionally produces NO SIGNAL. Each normalized feature is in [0,1] and must be calculated from verified point-in-time data. Missing data is null. Quote timestamp is UNIX milliseconds. T30 is compulsory for a live BUY.

## Data refresh
After market close: NSE universe OHLCV/delivery, quarterly fundamentals, corporate disclosures, macro, 20-day relative volume, breakout reference, shortlist top 20–30. Morning at 08:30 IST: news and global macro. From 09:15 IST: licensed broker WebSocket. At each completed 1-minute interval: aggregate bars; evaluate shortlist every 5 minutes using completed 15-minute candles from 09:30 onward. End-of-day: outcomes and audit. Exchange holidays and quote staleness must prevent signals.

A phone's foreground five-minute refresh is NOT an always-on background scanner. Production scheduling, WebSocket ingestion, authentication and secrets management belong on the server. Broker and news API keys must never be embedded in the APK. This client doesn't place trades.

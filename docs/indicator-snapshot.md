# IndicatorSnapshot JSON (schemaVersion 3)

The latest technical snapshot of a listing: every indicator the future technical agents (Murphy, Keith Gill, Paul
Tudor Jones, Stanley Druckenmiller, and the risk overlay) will read, as one versioned JSON document. It is data, not a
signal: no score, no buy / sell verdict, no model call (the preset summaries, `GET .../summary`, derive a stance and
prices from it by fixed rules: [v1_docs/TECHNICAL_DOCS.md](v1_docs/TECHNICAL_DOCS.md) section 8). Computed in Java (`technical.engine.SnapshotCalculator`) from
stored daily prices, the IHSG bars and corporate events. It is technical only: no macro data (that is the Macro page,
[v1_docs/MACRO_DOCS.md](v1_docs/MACRO_DOCS.md)).

- API: `GET /api/v1/technical/{exchange}/{ticker}/snapshot` (permission COMPANIES).
- Storage: `indicator_snapshot(company_id, as_of_date, schema_version, snapshot_json JSONB, input_hash, computed_at)`,
  one row per company (the latest). The nightly technical refresh recomputes every active listing; a request
  recomputes a snapshot whose `input_hash` changed (new or re-adjusted prices, new index bars, events,
  settings or engine revision) and serves the stored one otherwise. Being JSONB, the stored text lists keys in
  PostgreSQL's order, not the order below.
- Versioning: `schemaVersion` changes on any incompatible change (a field renamed, removed or redefined); new fields
  may be added within a version. Consumers must ignore unknown fields. `SnapshotCalculator.ENGINE_REVISION` (not in
  the document) changes when a value is computed differently without a schema change.

The IHSG index has a snapshot too (`.../IHSG/snapshot`): the same groups on its bars in index points (levels rounded to
0.01), computed on request and not stored; `relativeStrength.ratio` / `ratioChange50Pct` (vs itself), the auto-rejection
counts, `price.lotSize`, `price.valueTraded` and the value averages are null, with a note.

So does every daily **Forex pair or commodity** (`.../FOREX/USDIDR/snapshot`, `.../COMMODITY/GOLD/snapshot`).
- **Bars:** its own, in its quote currency (`currency` JPY for USD/JPY), with levels rounded to its tick (`price.tickSize`).
- **Stored:** no; it is computed on request.
- **Null, with a note:**
  - `relativeStrength.ratio`, `ratioChange50Pct`, the percentiles and the sector comparison;
  - the auto-rejection fields;
  - `risk.sessionsAfterLastBar`, `missingSessions60`, `possiblySuspended`;
  - `price.lotSize`, `price.valueTraded` and the value averages.
- **Volume:** a currency pair has none, so its volume indicators are 0 or null.
- **Shared groups:** `marketRegime` is that of IDX.

Coal (monthly) has no snapshot (422).

**Version history.**
- **3** (2026-10-16): the `macro` section is removed; Technicals reads no macro data (the Macro page does).
- **2** (2026-10-16): `macro` gained `series`, `signals` and `regimes`, and every macro value is point in time
  (section macro).
- **1:** the first version.

## Conventions

| Rule | |
|---|---|
| Prices | Split-adjusted daily bars as traded since the last split (`price_daily.close_price`; the provider's close is split-adjusted), in the exchange's currency (IDR on IDX). A company that reports in USD (INDY, SMDR, PTSN, RAJA) has its stored USD prices converted back to the IDR quote with the rate they were converted with. Dividend adjustment is never applied in the snapshot. |
| Levels | Every support / resistance, retracement, swing, 52-week, all-time, base and volume-node price is rounded to a valid IDX price (tick table in `neracalab.technical.tick-sizes`: Rp 1 below 200, 2 below 500, 5 below 2,000, 10 below 5,000, 25 from 5,000); a zone's lower edge is rounded down, its upper edge up. |
| Periods | In daily bars (trading days): 21 ≈ 1 month, 63 ≈ 3 months, 126 ≈ 6 months, 252 ≈ 1 year. |
| Percentages | In percent (12.5 = 12.5 %), named `...Pct`. Ratios (0.618) are plain numbers. |
| Numbers | Rounded to 4 decimals (6 significant digits below 0.01). |
| Missing | `null` when not computable: too little history (an indicator is never an average of fewer bars than its name says), no source, no data. `notes` says why for whole groups. |
| `usedBy` | Which future agents read the group (documentation only). |

## Top level

| Field | Type | Meaning |
|---|---|---|
| `schemaVersion` | int | 2 |
| `exchange`, `ticker`, `companyName`, `sector` | string | the listing (`sector` may be null) |
| `currency` | string | currency of every price (the exchange's: IDR) |
| `asOfDate` | date | the listing's last stored trading day |
| `priceBasis` | string | the price basis in words |
| `history` | object | `tradingDays`, `firstDate`, `lastDate`, `limited` (fewer than `minHistoryDays` = 250), `minHistoryDays` |
| `price` | object | `open high low close` of the last bar, `changePct` vs the previous close, `volume` (shares), `valueTraded` (close x volume: the exchange's own value is not stored), `tickSize` at the close, `lotSize` (100) |
| `notes` | string[] | why values are null (limited history, no earnings dates, no suspension source, ...) |

## trend (Murphy, PTJ, Druckenmiller)

| Field | Meaning |
|---|---|
| `sma20 sma50 sma100 sma200` | simple moving averages of the close |
| `ema20 ema50` | exponential moving averages (alpha 2 / (n + 1), seeded with the first close) |
| `sma200Slope20dPct` | SMA 200 today / SMA 200 20 bars ago - 1 |
| `priceVsMaPct.{sma20,...,ema50}` | close / average - 1 |
| `sma50Vs200Cross.state` | `GOLDEN` (SMA 50 above SMA 200) or `DEATH` (below); null before both exist |
| `sma50Vs200Cross.lastCrossDate`, `barsSinceCross` | the bar where the current state began; null when it held since both averages exist |
| `adx14`, `plusDi14`, `minusDi14` | ADX (14, Wilder) with +DI / -DI (14) |

## momentum (Murphy, Druckenmiller)

| Field | Meaning |
|---|---|
| `rsi14` | RSI (14, Wilder) |
| `macd.line`, `macd.signal`, `macd.histogram` | EMA 12 - EMA 26; EMA 9 of it; line - signal |
| `stochastic.k`, `stochastic.d` | slow stochastic (14, 3, 3): %K = SMA 3 of the fast %K (14), %D = SMA 3 of %K |
| `rocPct.d21 d63 d126 d252` | close / close n bars ago - 1 |
| `momentum12_1Pct` | close 21 bars ago / close 252 bars ago - 1 (12-month momentum skipping the last month) |

## volatility (PTJ, Gill, risk)

| Field | Meaning |
|---|---|
| `atr14`, `atrPct` | ATR (14, Wilder); ATR / close in % |
| `atrPctPercentile252` | percentile rank (0-100) of today's ATR% among the last 252 bars' ATR% (needs 252 bars) |
| `bollinger20_2.upper middle lower` | SMA 20 ± 2 population standard deviations |
| `bollinger20_2.bandwidthPct` | (upper - lower) / middle |

## volume (Murphy, Gill, risk)

| Field | Meaning |
|---|---|
| `avgVolume20`, `avgVolume50` | average shares traded |
| `avgValue20`, `avgValue50` | average value traded (close x volume) |
| `obv` | on-balance volume |
| `obvSlope20` | least-squares slope of OBV over the last 20 bars (shares per bar) |
| `obvSlope20PerAvgVolume` | that slope / the 20-bar average volume |
| `upDownVolumeRatio50` | volume on up-close days / volume on down-close days, last 50 bars (null without a down day) |
| `volumeSpikes60.multiple` | 2.0: a spike is a day with at least 2 x the average volume of the 50 bars before it |
| `volumeSpikes60.upDays`, `downDays`, `days[]` | spikes of the last 60 bars on up / down days; `days[]`: `date`, `direction` (UP / DOWN / FLAT), `volumeMultiple` |

## structure (Murphy, Gill, PTJ)

| Field | Meaning |
|---|---|
| `swings.highs[]`, `swings.lows[]` | swing points of the last `lookbackDays` (250) bars: a bar whose high (low) is the extreme of `barsEachSide` (2) bars on each side (5-bar fractals); `date`, `price` |
| `zones[]` | support / resistance zones: swing points sorted by price, a point joins a cluster while within `zoneTolerancePct` (2 %) of its mean. `type` SUPPORT (zone below the close), RESISTANCE (above) or PIVOT (around it); `low`, `high`, `mid`, `touches`, `firstTouch`, `lastTouch`, `distancePct` (mid vs close). The 8 strongest (most touches, then most recent), ordered from the top price |
| `volumeProfile` | volume by price over the last 120 bars in 24 equal price buckets (each bar's volume spread over its high-low range): `from`, `to`, `bucketCount`, `topNodes[]` (`price` = the bucket's middle as a valid price, `low`, `high`, `volume`, `sharePct`, `distancePct`). All buckets: the `/levels` API |
| `retracement` | the last major swing (at least `majorSwingAtrMultiple` = 3 x ATR 14, found by a zigzag over the last 500 bars) and its retracements: `direction` (UP / DOWN), `swingStart`, `swingEnd` (`date`, `price`), `amplitude`, `levels[]` (`ratio` 0.33 / 0.382 / 0.5 / 0.618 / 0.66, `price` measured back from the swing's end, `distancePct`). null without a major swing |
| `week52` | `high`, `highDate`, `low`, `lowDate` of the last 252 bars, `distanceFromHighPct`, `distanceFromLowPct`, `complete` (252 bars available) |
| `allTimeHigh` | `price`, `date`, `drawdownPct` (close vs it), `since` (first stored bar: "all-time" means since then) |
| `base` | `detected`; when true `startDate`, `endDate`, `days`, `high`, `low`, `depthPct`, `atrPctStart`, `atrPctEnd`: the longest window of 60-120 bars ending at the last bar with (high - low) / low ≤ 25 % and the mean ATR% of its last third ≤ 0.9 x that of its first third |

## relativeStrength (Murphy, Druckenmiller)

| Field | Meaning |
|---|---|
| `benchmark` | IHSG |
| `ratio` | close / IHSG close on the last common day (null when IHSG is not loaded) |
| `ratioChange50Pct` | least-squares slope of the ratio over the last 50 common days x 49 / its mean: how much the ratio rose or fell over the window |
| `return6mPct`, `return12mPct` | close / close 126 (252) bars ago - 1 |
| `percentile6m`, `percentile12m` | percentile rank among the stored active listings of the exchange (`universe6m`, `universe12m` = how many had the history); not the whole exchange |
| `sector` | `name`, `members`, `medianReturn6mPct`, `medianReturn12mPct`, `vsSector6mPct`, `vsSector12mPct` (the listing minus its sector's median); null comparisons with fewer than 3 stored listings in the sector |

## marketRegime (PTJ, Druckenmiller)

| Field | Meaning |
|---|---|
| `index`, `indexDate`, `indexClose` | IHSG and its latest stored day |
| `indexSma200`, `indexAboveSma200`, `indexVsSma200Pct`, `indexSma200Slope20dPct` | IHSG against its 200-day SMA |
| `breadthAbove200Pct`, `breadthCounted`, `breadthUniverse` | share of the stored active listings (with 200 bars and a bar in the last 10 days) closing above their own SMA 200 |

## events (Gill, Druckenmiller)

| Field | Meaning |
|---|---|
| `upcoming[]` | events after the as-of date within `upcomingDays` (60): `date`, `type`, `value`, `description`, `source` |
| `last12Months[]` | events of the last 365 days |
| `lastEarnings` | the last `EARNINGS` event: `date`, `gapPct` (first session on or after it: open / previous close - 1), `followThrough5dPct` (close 5 sessions later / that session's close - 1). null: no earnings dates are stored yet |

Event types: `DIVIDEND` (value = cash per share), `STOCK_SPLIT` / `REVERSE_SPLIT` (value = new shares per old share),
`EARNINGS`, `RUPS`, `RUPSLB`, `RIGHTS_ISSUE`, `PRIVATE_PLACEMENT`. Sources today: Yahoo Finance dividends and splits,
and the splits recorded from the filings (`corporate_action`).

## risk (risk overlay)

| Field | Meaning |
|---|---|
| `avgValueTraded20` | 20-day average value traded (liquidity) |
| `araCount60`, `arbCount60`, `lastAra`, `lastArb` | auto-rejection hits detected from the bars: a close at the day's high (low) within one tick of the upper (lower) limit in force that day (`neracalab.technical.auto-rejection`, by date and price band) |
| `gapDays60`, `gapFrequency60Pct` | sessions of the last 60 opening above the previous high or below the previous low |
| `zeroVolumeDays20` | sessions without volume of the last 20 |
| `missingSessions60` | IDX sessions (IHSG days) within the listing's last 60 bars without a bar of it |
| `sessionsAfterLastBar` | IHSG sessions after the listing's last bar (its stored prices are older than the index) |
| `possiblySuspended` | 3 or more consecutive missing sessions within the last 60 bars, or no volume on the last 3 bars |
| `specialMonitoringBoard`, `suspended` | null: no source of the special monitoring board (Papan Pemantauan Khusus) or of suspensions yet |

## Example (MOLI, arrays shortened)

```json
{
  "schemaVersion": 3, "exchange": "IDX", "ticker": "MOLI", "companyName": "Madusari Murni Indah",
  "sector": "Basic Materials", "currency": "IDR", "asOfDate": "2026-10-14",
  "history": { "tradingDays": 1858, "firstDate": "2018-08-31", "lastDate": "2026-10-14", "limited": false, "minHistoryDays": 250 },
  "price": { "open": 400.0, "high": 406.0, "low": 392.0, "close": 398.0, "changePct": -0.5, "volume": 1910000.0,
             "valueTraded": 760180000, "tickSize": 2.0, "lotSize": 100 },
  "trend": { "sma50": 332.52, "sma200": 280.005, "sma200Slope20dPct": 2.3055,
             "sma50Vs200Cross": { "state": "GOLDEN", "lastCrossDate": "2026-09-15", "barsSinceCross": 21 },
             "adx14": 45.6467, "plusDi14": 22.0517, "minusDi14": 15.1406 },
  "momentum": { "rsi14": 57.3677, "macd": { "line": 19.5459, "signal": 23.8607, "histogram": -4.3147 },
                "rocPct": { "d21": 22.0859, "d63": 60.4839, "d126": 61.7886, "d252": 80.9091 }, "momentum12_1Pct": 48.1818 },
  "structure": {
    "zones": [ { "type": "SUPPORT", "low": 342.0, "high": 348.0, "mid": 346.0, "touches": 4, "lastTouch": "2026-09-29" } ],
    "retracement": { "direction": "UP", "swingStart": { "date": "2026-06-04", "price": 186.0 },
                     "swingEnd": { "date": "2026-10-05", "price": 442.0 },
                     "levels": [ { "ratio": 0.33, "price": 358.0 }, { "ratio": 0.382, "price": 344.0 } ] },
    "week52": { "high": 442.0, "low": 186.0, "complete": true },
    "allTimeHigh": { "price": 1160.0, "date": "2018-09-03", "drawdownPct": -65.6897, "since": "2018-08-31" },
    "base": { "detected": false }
  },
  "risk": { "araCount60": 0, "arbCount60": 0, "missingSessions60": 0, "possiblySuspended": false,
            "specialMonitoringBoard": null, "suspended": null },
  "notes": [ "No earnings dates stored (the filings do not carry their publication date yet): events.lastEarnings is null", "..." ]
}
```

# Neraca Lab - IDX company profiles, dividends and the Fundamental (Score) (v1)

Two tabs on the company page (`/companies/{exchange}/{ticker}`) and the ingestion behind them:

- **Profile** (after Overview): the IDX company profile (listing date = IPO date, listing board, IDX-IC sector,
  main business activity, share registrar) and the dividends: every cash dividend with its yield, the trailing
  dividend yield and payout ratio, and the payout ratio of every fiscal year.
- **Fundamental (Score)** (after Filings; named Quant Score before 2026-11-11, still `?tab=quant` and
  `/scorecards`): the six fundamental agents' scorecards (Buffett, Munger, Lynch, Fisher, Keith Gill, Risk) from preset
  rules on the stored data. No model call, no cost, nothing stored. The **Technical (Score)** tab beside it is in
  [TECHNICAL_DOCS.md](TECHNICAL_DOCS.md) section 10.
- **Ingestion › Screening Data IDX › IDX company profiles**: the card that loads the IDX profiles.

## 1. IDX company profiles

### 1.1 Why an upload, not a fetch

idx.co.id answers automated clients with a Cloudflare challenge (HTTP 403 "Just a moment...", checked 2026-11-08
with a browser User-Agent), so the backend cannot fetch it, and Neraca Lab does not try to get around that. The data is
fetched **in the user's own browser session** by the collector script and uploaded. This is the same pattern as the
KSEI ownership lists and the manual macro series.

```
www.idx.co.id tab (DevTools console)  <- "Copy collector script" on the card
  GET /primary/ListedCompany/GetCompanyProfiles?emitenType=s&start=0&length=9999      (one request, every company)
  GET /primary/ListedCompany/GetCompanyProfilesDetail?KodeEmiten=XXXX                  (one per company, 400 ms apart)
  -> saves company-profiles-<yyyy-mm-dd>.json  {"profiles": <list>, "details": {"AALI": <detail>, ...}, "failed": [...]}
POST /api/v1/companies/profiles/upload  (multipart file; Upload profiles on the card)
  -> IdxProfileParser -> CompanyProfileService -> result: updated / unchanged / unknown tickers / conflicts / checks
```

The script lives in `frontend/src/lib/company-profile.ts` (`IDX_PROFILE_COLLECTOR`); the card copies it to the
clipboard and also shows it. Set `DETAILS = false` in it for the list only (a few seconds; no dividends). With details
it takes about 8 minutes for ~950 companies.

### 1.2 What is read (`IdxProfileParser`)

Accepted files: the collector's file, the bare list response (`{"data": [...]}` or an array), or one detail response
(`{"Profiles": [...], "Dividen": [...]}`). Field names are matched without regard to case, blanks and underscores, and
a few alternative names are accepted. A value that cannot be read is left out with a warning, never guessed.

| IDX field | Stored | Notes |
|---|---|---|
| `KodeEmiten` | match to `company.ticker` | unknown tickers are listed, never created |
| `TanggalPencatatan` | `company.ipo_date` | ISO date or date-time, `/Date(ms)/` (Jakarta day), `d/M/yyyy`, `9 Dec 1997` / `9 Desember 1997` |
| `PapanPencatatan` | `company.listing_board` | `MAIN` (Utama), `DEVELOPMENT` (Pengembangan), `ACCELERATION` (Akselerasi), `NEW_ECONOMY` (Ekonomi Baru), `WATCHLIST` (Pemantauan Khusus); another value upper case |
| `Sektor`, `SubSektor`, `Industri`, `SubIndustri` | `idx_sector`, `idx_sub_sector`, `idx_industry`, `idx_sub_industry` | IDX-IC as IDX names it |
| `KegiatanUsahaUtama` | `business_activity` | |
| `BAE` | `share_registrar` | |
| `NamaEmiten` | `idx_name` | to check against `legal_name` |
| detail `Dividen[]` | `company_dividend` (source `idx`) | cash rows only (`CashDividenPerSaham` > 0): `TahunBuku`, `Jenis`, per share + currency, `TanggalCum`, `TanggalExRegulerDanNegosiasi`, `TanggalDPS`, `TanggalPembayaran`, `CashDividenTotal` + currency; a repeated row once |

Numbers may be JSON numbers or text in either notation (`1.234,5` / `1,234.5` / `538,08` / `Rp 25`).

**Field names not yet confirmed.** They follow IDX's public site; no real response was available when this was built
(Claude in Chrome was not connected). The parser is lenient, and the upload result lists every row it could not read.
Run the collector once, upload, and check the result's warnings.

### 1.3 What an upload writes (`CompanyProfileService.upload`, one transaction)

- `ipo_date`: set when empty. A different stored date is a **conflict**: kept, and listed in the result.
- Board, IDX-IC columns, business activity, registrar, IDX name: written. A value the file lacks keeps the stored one.
- Companies with a detail response: their IDX dividends are **replaced** (the detail holds the whole history).
- **Checks** (written anyway, listed for information): a price stored before the listing date (a relisting, a ticker
  reused, or a wrong date), or Yahoo's `first_trade_date` more than a week before it.
- The same file again (same SHA-256): nothing is written and the earlier result is returned (`duplicate: true`).
- Not written: `corporate_action` IPO rows (no code reads them; `company.ipo_date` is the field), and
  `stock_listing.board` (the screening's watchlist filter reads it; filling it would change screenings, so that is left
  to a separate decision).

### 1.4 Tables (`V1.0.52__schema_company_profile.sql`)

- `company`: `listing_board`, `idx_sector`, `idx_sub_sector`, `idx_industry`, `idx_sub_industry`,
  `business_activity`, `share_registrar`, `idx_name`, `profile_updated_at`.
- `company_profile_upload`: one row per file (`exchange`, `file_sha256` unique), its counts and the full result as JSON.
- `company_dividend`: IDX's cash dividends. They are kept apart from `corporate_event` (Yahoo's dividends, which are
  the chart markers), so a dividend both sources know is not drawn twice.

## 2. Dividends on the Profile tab (`DividendHistory`)

Sources: IDX (`company_dividend`, once uploaded) and Yahoo (`corporate_event` DIVIDEND, refreshed nightly). The same
dividend in both (ex dates at most 3 days apart) is one row, `IDX · Yahoo`.

| Value | Definition |
|---|---|
| Yield of a dividend | per share / the **cum-date close** (the last trading day with the right to the dividend; for Yahoo, the last close before the ex date), both on the share basis of that date |
| Dividends, last 12 months | dividends with an ex date in the 12 months to the last close, per share on today's basis |
| Trailing dividend yield | that sum / the last close |
| Payout ratio, last 12 months | that sum / EPS TTM of the latest valuation snapshot (the owners' profit per share) |
| Cash payout ratio (per fiscal year) | dividends paid in the year's cash flow statement / profit to the owners of the year; may include dividends a subsidiary paid to its minority shareholders (TLKM FY2025: 159%, against a per-share payout of 127.5%) |
| Declared payout (IDX, per fiscal year) | the dividends declared from the year's profit (`TahunBuku`): their total / the year's profit to the owners; without totals, per share / basic EPS (same currency) |

**Share basis.** Yahoo stores prices and dividends split-adjusted (today's basis); IDX announces amounts on the basis of
their date. Each row is shown on the basis of its own date: Yahoo's amount and the close are multiplied by the splits
after the date (Yahoo's `STOCK_SPLIT` / `REVERSE_SPLIT` events, new:old shares). This way a yield does not change
after a later split, and an IDX amount can be set against the close directly.

**Currencies.** A company that reports in USD (AADI, INDY ...) has its closes stored in USD. They are converted back
into rupiah at the rate the price ingestion used (the last `fx_rate_daily` day before the trading day, at most 7 days
before), so yields are rupiah over rupiah. Payout ratios are in the reporting currency. Without a rate, the value is
unknown, never guessed. Yahoo's own `fundamental_snapshot.dividend_yield` is not shown: it divides a rupiah dividend by
a USD quote for USD reporters (AADI: 0.0003% instead of ~9.9%).

## 3. Quant Score (`ScorecardService`, `CombinedProfile`)

`GET /api/v1/companies/{exchange}/{ticker}/scorecards` (permission COMPANIES): every agent's scorecard of
`QuantScorer` (bounds and weights in [SCREENING_DOCS.md](SCREENING_DOCS.md) section 4 and
`instruction/development_1/quantScorerFundamenta.md`), unchanged. Only the metrics are combined:

| Metrics | First | Then |
|---|---|---|
| ROE, ROA, margins, interest coverage (`financial_metric` of the latest FY) | filings | Yahoo |
| debt / equity, current ratio, equity / assets (latest balance sheet, any period) | filings | Yahoo |
| ROE average, profitable years, revenue / earnings CAGR, margin trend, capex / revenue, FCF / net income, Altman Z, latest growth (up to 4 consecutive FY; a missing year counts in the CAGR) | filings | Yahoo |
| P/E, P/B, EV / EBITDA, FCF yield, EPS (latest `valuation_snapshot`) | valuation | Yahoo |
| PEG | P/E above / (earnings CAGR above x 100) | Yahoo's PEG |
| price, traded value, 52-week drawdown and range | Yahoo screening snapshot | stored daily prices |
| beta, insider ownership | Yahoo | none |

The formulas are `StockProfile`'s (the same CAGR, margin trend and Altman Z). Financial or not: Yahoo's sector of the
snapshot, as the screening uses it; without a snapshot, the company's sector or the IDX-IC sector Financials
(Keuangan). Overall = `QuantScorer.overall` of the agents with at least `min-fundamentals-coverage` (50%) of their
scorecard known, Risk weighted `risk-weight` (20%). The verdict uses the screening's bands (Strong fit >= 80, Fit >= 65,
Neutral >= 45, Weak >= 30, Reject). Every criterion in the response has its value, the poor / good bounds, the points,
whether it applies, and its source with the basis (`FY2025`, `valuation 2026-11-06`, `2026 H1` ...). The tab also
writes rule-based strengths (>= 80% of a criterion's points), concerns (<= 20%) and data gaps. No AI is involved.

The scores can differ from the quantitative part of Screening › Analysis, which uses Yahoo's snapshot alone; this tab
prefers the filings.

### 3a. The company list's Quant Score column (`QuantScoreBoard`)

`GET /api/v1/companies/quant-scores?exchange=IDX` (permission COMPANIES): `exchange`, `computedAt`, `millis`, `cached`,
`companies`, `scored`, `minCoverage`, `riskWeight` and per company `ticker`, `overall`, `verdict`, `financial`,
`agentsCounted`. Each score comes from `ScorecardService.compute`, the code of the Quant Score tab, on the same data, so
the list and the tab never differ (`QuantScoreBoardTest` compares HRTA, BBCA, GOTO, AADI, TLKM).

- All 938 IDX companies, 8 at a time: about 2 - 3 s (2026-11-09; 10 s before the scorecard queries named the company in
  their reads of `financial_metric` and the statements, whose unique indexes lead with `company_id`).
- Kept in memory per exchange, computed again when `ScorecardRepository.fingerprint()` changes (about 20 ms: periods,
  valuation snapshots, companies, screening snapshot dates, exchange rates) or after
  `neracalab.companies.quant-score-ttl` (default 10 minutes: daily prices and metrics rewritten in place are not in
  the fingerprint). Requests of one exchange wait for one computation. A company whose computation fails has an
  unknown score (logged), never a failed list.

## 4. APIs

| Method | Path | Permission | |
|---|---|---|---|
| POST | `/api/v1/companies/profiles/upload?exchange=IDX` (multipart `file`, at most 50 MB) | INGESTION | 200 result; 422 not a profile file |
| GET | `/api/v1/companies/profiles/status?exchange=IDX` | INGESTION | counts, the last upload, the first 50 companies without a listing date |
| GET | `/api/v1/companies/{exchange}/{ticker}/profile` | COMPANIES | the Profile tab |
| GET | `/api/v1/companies/{exchange}/{ticker}/scorecards` | COMPANIES | the Quant Score tab |
| GET | `/api/v1/companies/quant-scores?exchange=IDX` | COMPANIES | the overall of every company (the company list) |

Frontend route handlers: `POST /api/companies/profiles/upload`, `GET /api/companies/profiles/status`. The company page
loads the profile and the scorecards on the server beside the detail; when either fails, only its tab shows the error.

## 5. Tests

| Test | Covers |
|---|---|
| `IdxProfileParserTest` | list, detail and collector shapes, board codes, date forms, both number notations, repeated / bonus rows, rejection |
| `DividendHistoryTest` | cum-date close, split basis, IDX + Yahoo match, USD reporter conversion, no rate = unknown, cash / declared / trailing payout |
| `CombinedProfileTest` | filings first and Yahoo for the gaps, prices without a snapshot, a missing year in the CAGR, bank criteria, card bounds = `QuantScorer.criteria` |
| `QuantScoreBoardTest` | every IDX company listed, each overall / verdict / financial / agents counted equal to its Quant Score tab, the second request from memory, the API and an unknown exchange (400) |
| `CompanyProfileControllerTest` | against the Docker Postgres, rolled back: upload writes / unknown ticker / conflict / same file / partial file, Profile tab, status, 404, the Quant Score of HRTA (overall = `QuantScorer.overall`), BBCA as a bank, AADI's rupiah yields |

Full suite 2026-11-09 with these and `QuantScoreBoardTest`: 996 tests in 182 classes, all passing (13 min 36 s).

## 6. Refresh

1. Ingestion › Screening Data IDX › IDX company profiles: **Open idx.co.id**, **Copy collector script**, paste it into
   that tab's console, and wait for the download.
2. **Upload profiles** with the saved file, then read the result: unknown tickers, conflicts, checks, warnings.
3. Repeat after new IPOs (for example monthly). The Yahoo dividends update nightly on their own.

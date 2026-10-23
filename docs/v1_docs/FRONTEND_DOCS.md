# Neraca Lab - Frontend (v1)

Next.js 16 web app in `frontend/` that shows the data stored by the backend: the companies of an
exchange, and everything stored for one company. It reads the company APIs
([COMPANY_API_DOCS.md](COMPANY_API_DOCS.md)); data gets in through the upload endpoint
([AI_INGESTION_DOCS.md](AI_INGESTION_DOCS.md)).

## 1. Stack

| Part       | Choice                                                                                                                                                                        |
|------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Framework  | Next.js 16.3 (App Router, Turbopack), React 19.2, TypeScript, created with `npx create-next-app@16.3.8`                                                                       |
| Theme      | [shadcn-fintech](https://github.com/abderrahimghazali/shadcn-fintech) (MIT): `components.json`, `globals.css`, ui components, inset sidebar shell, empty states, theme toggle |
| Components | shadcn/ui `base-nova` style on Base UI (`@base-ui/react`)                                                                                                                     |
| Styling    | Tailwind CSS 4, `tw-animate-css`, Geist fonts, light / dark mode (`next-themes`)                                                                                              |
| Charts     | Recharts 3 through the shadcn `ChartContainer`; the candlestick chart of Technicals: TradingView Lightweight Charts 5.2.1 (Apache-2.0, attribution kept) |
| Client data | TanStack Query 5.104.0 and zod 4.6.5 on the Technicals page (requests through the route handlers, responses validated before charting) |
| Icons      | lucide-react                                                                                                                                                                  |

## 2. Data flow

```text
browser ──> Next.js server (Server Components, Route Handlers /api/...) ──> Neraca Lab backend /api/v1/...
```

- Pages are Server Components that call the backend from `src/lib/api.ts` with
  `cache: "no-store"` (always fresh). The backend URL (`NERACA_API_URL`, default
  `http://localhost:8080`) stays on the server, and the backend needs no CORS configuration.
- Interactive parts (filters, sorting, tabs, charts) are Client Components that receive the
  JSON from the page.
- `getCompanyDetail` is wrapped in React `cache`, so `generateMetadata` and the page share one
  backend call per request.
- Backend errors become an `ApiError` (HTTP status + ProblemDetail `title` / `detail`; status 0 =
  backend unreachable). Pages render them inline with a "Try again" button (`router.refresh()`),
  so the message is visible in production too. A 404 for a company renders the route's
  `not-found.tsx`.

| Backend call                                | Used by                  |
|---------------------------------------------|--------------------------|
| `GET /api/v1/exchanges`                     | exchange filter (page 1) |
| `GET /api/v1/companies?exchange={code}`     | company list (page 1)    |
| `GET /api/v1/companies/{exchange}/{ticker}` | company detail (page 2)  |
| `GET /api/v1/ingestions?type=&limit=10&offset=` | every Ingestion page (page 3): jobs table, one page at a time |
| `GET /api/v1/auth/me`                       | every page: the logged-in user (layout, permission checks) |
| `GET /api/v1/admin/users`, `/admin/roles`, `/admin/permissions` | Admin Center pages |
| `GET /api/v1/prices/ingestions`             | Price Ingestion page: configured price provider |
| `GET /api/v1/rag/status`, `GET /api/v1/rag/documents?source=PDF\|NEWS&limit=10&offset=&tickerPrefix=` | RAG pages: embedding model, news limits, stored documents one page at a time |
| `GET /api/v1/analyses/options`, `GET /api/v1/analyses?limit=10&offset=0`, `GET /api/v1/analyses/{id}` | Analysis pages: companies with their stored data, saved analyses, report |
| `GET /api/v1/screenings/options`, `GET /api/v1/screenings?limit=50&scope=TIER\|SELECTION`, `GET /api/v1/screenings/companies?exchange=IDX` | Screening Stocks and Selected Stocks pages: form options, saved screenings, the companies that can be selected |

The Ingestion pages also call the backend from the browser through Next.js Route Handlers in
`src/app/api/` (same server-side `NERACA_API_URL`; `forward()` in `src/lib/api.ts` passes status and
ProblemDetail through; unreachable backend = 503):

| Route Handler                               | Backend                                                  |
|---------------------------------------------|----------------------------------------------------------|
| `POST /api/financial-statements/upload`     | `POST /api/v1/financial-statements/upload`               |
| `POST /api/prices/ingestions` (JSON body)   | `POST /api/v1/prices/ingestions?exchange=&ticker=&full=` |
| `GET /api/ingestions?type=&status=&limit=`  | `GET /api/v1/ingestions` (table polling)                 |
| `GET /api/ingestions/{id}`                  | `GET /api/v1/ingestions/{id}` (detail sheet)             |
| `POST /api/auth/login`, `POST /api/auth/logout` | `POST /api/v1/auth/login` / `logout`; login sets the httpOnly session cookie, logout removes it |
| `/api/admin/users[/{id}[/avatar]]`, `/api/admin/roles[/{id}]`, `/api/admin/permissions` | `/api/v1/admin/...` (method and body passed on by `forwardRequest()`) |
| `/api/profile`, `/api/profile/avatar`       | `/api/v1/profile`, `/api/v1/profile/avatar`              |
| `/api/screenings`, `/api/screenings/{id}`, `/api/screenings/{id}/pdf` | `/api/v1/screenings[...]` (list with `limit` and `scope` / start a tier or selected stocks, report, PDF download) |
| `/api/analyses`, `/api/analyses/{id}`, `/api/analyses/{id}/pdf` | `/api/v1/analyses[...]` (page of analyses / start, report, PDF download) |
| `/api/fundamentals/ingestions`              | `POST /api/v1/fundamentals/ingestions?exchange=&full=` (screening data ETL) |
| `POST /api/rag/pdf` (multipart: file, exchange, ticker) | `POST /api/v1/rag/pdf` |
| `POST /api/rag/news` (JSON `{exchange, ticker, from, to}`) | `POST /api/v1/rag/news?exchange=&ticker=&from=&to=` |
| `GET /api/rag/documents`, `GET /api/rag/search?q=&ticker=&source=&limit=` | `GET /api/v1/rag/documents`, `/api/v1/rag/search` (search test on the RAG pages) |
| `GET /api/ingestions/{id}/file`             | `GET /api/v1/ingestions/{id}/file` (download, streamed with name / type / size / checksum headers by `forwardFile()`) |

### Login and permissions

- `POST /api/auth/login` logs in at the backend and stores the session token in the httpOnly cookie
  `neraca_session` (SameSite=Lax, Secure over HTTPS, expiring with the session); JavaScript never
  sees it. Every server-side backend call (`src/lib/api.ts`) sends it as `Authorization: Bearer`.
- `src/proxy.ts` redirects every page without the cookie to `/login?next=<page>` (API routes are
  excluded: the backend answers 401 there, and a proxy would limit request bodies to 10 MB).
- The dashboard layout loads the user (`GET /api/v1/auth/me`); a missing or expired session goes to
  `/login?expired=1`. A 401 of a client-side call does the same (`src/lib/client-api.ts`).
- The sidebar shows only the pages the user's permissions allow; each page checks its permission
  again and shows "No access" otherwise (the backend refuses the APIs with 403 anyway).
- `/` opens the first allowed page: Companies, Screening, Ingestion (IDX XBRL), User Management, else the profile.
  Rules: [AUTH_DOCS.md](AUTH_DOCS.md).

## 3. Pages

### 3.1 Company list - `/companies?exchange=IDX`

`/` redirects here for users with the `COMPANIES` permission; `exchange` defaults to `IDX` and is case-insensitive.

| Element         | Content                                                                                                                              |
|-----------------|--------------------------------------------------------------------------------------------------------------------------------------|
| Summary tiles   | number of companies, with financial statements, with price history, latest reported period                                           |
| Exchange filter | the exchanges from `GET /api/v1/exchanges`; changing it navigates to `?exchange=` (shareable URL)                                    |
| Search          | ticker, name, legal name, sector, industry (client side)                                                                             |
| Sector filter   | the sectors of the listed companies                                                                                                  |
| Table           | company, sector / industry, periods, latest period, latest price date, currency; sortable columns; a row (or "Details") opens page 2 |
| Empty states    | "no matching results" with "Clear filters", or "no companies yet" for an empty exchange                                              |
| Errors          | unsupported exchange (400), backend unreachable                                                                                      |

### 3.2 Company detail - `/companies/{exchange}/{ticker}`

Exchange and ticker are case-insensitive. The header shows name, `exchange: ticker`, legal name,
sector, industry, currency, fiscal year end, last close and market cap. The selected tab is kept
in the URL (`?tab=income`, ...) without a server round trip, so every tab can be linked.

| Tab              | Content                                                                                                                                                                                                                                                                                                   |
|------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Overview         | KPI tiles (revenue, net income to parent, total assets, equity, free cash flow, P/E, P/B, EV / operating profit) with the period they come from; revenue / gross profit / net income chart with a period-type filter (defaults to full years); data coverage; valuation multiples chart (P/E, P/B, EV/OP) |
| Income statement | line items x periods                                                                                                                                                                                                                                                                                      |
| Balance sheet    | line items x periods                                                                                                                                                                                                                                                                                      |
| Cash flow        | line items x periods                                                                                                                                                                                                                                                                                      |
| Segments         | revenue by segment for a selected period with share of total and a check against reported revenue; all segments                                                                                                                                                                                           |
| Metrics          | fundamental metrics grouped by category x periods (ratios in %, multiples in x, days)                                                                                                                                                                                                                     |
| Valuation        | every valuation snapshot: price, market cap, EV, TTM revenue / EPS, P/E, P/B, P/S, EV/EBITDA, EV/Sales, EV/OP, FCF and earnings yield                                                                                                                                                                     |
| Market & shares  | latest OHLCV price, market value, share counts per date, corporate actions                                                                                                                                                                                                                                |
| Filings          | every reporting period: dates, audit status, which statements exist, segment and metric counts, source filing                                                                                                                                                                                             |

Statement tables:

- Period columns most recent first, with end date and audit status; line items that no shown
  period reports are hidden.
- Period-type filter (All / FY / Q1 / H1 / 9M ...) and amount scale (billions / millions / full);
  EPS and share counts are never scaled.
- Negative amounts in parentheses and red (database sign convention: cash outflows negative).
- A statement that is not stored for a period (e.g. no balance sheet for a 2024 interim
  comparative) is left out of that table instead of showing zeros.

Unknown company: not-found page. Invalid ticker or unsupported exchange: the backend's 400 message.

### 3.2a Technicals - `/companies/technicals` (`COMPANIES`)

The Companies entry of the sidebar is a dropdown: **Fundamentals** (`/companies` and the company pages) and
**Technicals**. The chart page is a client component (`components/technical/technicals-page-client.tsx`); the chart
(`price-chart.tsx`) is a small wrapper around Lightweight Charts loaded with `next/dynamic` (`ssr: false`), rebuilt when
its data, indicators or the theme change, removed on unmount. The browser computes no indicator: every series comes
from `/api/technical/{exchange}/{ticker}/{ohlcv|indicators|levels|snapshot|events|summary}`, `/api/technical/market`,
`/api/technical/tickers` and `/api/technical/settings` (route handlers forwarding to `/api/v1/technical/**`).

| Element | Content |
|---|---|
| Controls | exchange or market (`GET /api/v1/technical/markets`: IDX, Forex, Commodities), ticker search (code or name, or IHSG / LQ45 for the indexes; with a market its currencies / commodities; arrows / Enter / Escape), Daily / Weekly / Monthly (a monthly series - coal, LNG, nickel, tin, cobalt, rubber, pulp, fertilizers - is shown monthly only), 3M .. Max, Split-adjusted / Total return (not for a currency or commodity), presets (Murphy, Keith Gill, Paul Tudor Jones, Druckenmiller: they toggle indicators), indicator checkboxes (side column; a sheet on a phone; for IHSG, a currency or commodity relative strength, the comparison line, events and ARA / ARB are disabled - for LQ45 only events and ARA / ARB: it is compared with IHSG; for a monthly series the levels too), PNG export |
| Preset summaries | below the chart (`preset-summaries.tsx`): a card per preset, the selected one first - stance, buy / sell / stop with distance and basis (with the decimals of the instrument: `priceDecimals`), reward to risk, thesis, the evidence on request, "Show on chart"; caveats and the disclaimer. A monthly series shows a note instead (no daily bars) |
| Main chart | candles (palm oil in USD and in ringgit and the monthly series: a plain line of the close), moving averages, Bollinger, channels, ATR bands, IHSG rebased; zones and the base as shaded bands (series primitive); retracement, 52-week and all-time-high lines; event and ARA / ARB markers; lower panes for volume, RSI, MACD, Stochastic, OBV, ADX, ATR %, RS vs IHSG, 12-1 month momentum |
| Panels | key levels (CSV; a currency or commodity at its own tick), snapshot "data, not signals" (CSV), volume by price (hidden without volume), IHSG regime. No macro panel: technical only (the macro view is the Macro page, 3.2b) |
| Settings | saved per user (`PUT /api/technical/settings`, debounced), localStorage as fallback, and in the URL; the URL wins |
| Other | limited-history warning, disclaimer "For information only. Not investment advice.", TradingView notice in the footer and on `/credits`; usable at phone width; colors from the theme's CSS variables |

Details: [TECHNICAL_DOCS.md](TECHNICAL_DOCS.md).

### 3.2b Macro - `/macro?country=ID&index=IHSG&range=1Y` (`COMPANIES`)

A sidebar dropdown between Companies and Screening: **Outlook** (this page) and **AI Analysis** (3.2c). The server
page loads the outlook
(`GET /api/v1/macro/outlook`); the client component (`components/macro/macro-page-client.tsx`) reloads it through
`/api/macro/outlook` when the country, index or range changes and keeps the choice in the URL (`router.replace`). An
unknown country or index in the URL shows Indonesia / IHSG.

| Element | Content |
|---|---|
| Controls | country (Indonesia; United States and China disabled, "coming later"), index (IHSG, LQ45), 6M .. MAX, the as-of date |
| Index | a plain line chart (Recharts), no indicators; close, 1M / 3M / YTD / 1Y, 52-week range; the investability score and the action badge |
| Macro panel | "Macro panel (liquidity and risk regimes, rates, dollar)": both regimes with every vote and their state 3 months before; a chart per series grouped Indonesia / Global liquidity and risk, each with its last value, a Supportive / Headwind / Neutral badge, the summary and the preset that reads it |
| Preset summaries | Paul Tudor Jones, Druckenmiller, George Soros and Ray Dalio (two per row on a wide screen): stance, label, score, thesis, buy when / sell when, the evidence on request |
| Commodities and currencies | exports, imports, currencies (the nine pairs) and havens / references: last, 1M / 3M / 1Y, 52-week range, a 1Y sparkline, a Supportive / Headwind / Neutral badge with what it means for the country; the terms of trade on top, then the rupiah against its Asian peers (weaker than / in line with / firmer than the region, with its summary) |
| Conclusion | the score (0 - 100) on a bar with the 40 / 60 marks, verdict and action, the summary, the buy / accumulate / sell conditions with met ones ticked (the group that applies now highlighted), what would change the call, the six parts of the score (weight, reading, points, why) |
| Right column | sticky on a wide screen (`xl`, 340 px): the AI panel (`components/macro/macro-ai-panel.tsx`: the latest AI macro analysis of the index - investability, verdict, action, the agents' stances, the thesis, the rules' score - "Run AI analysis", a link to the report; polls every 3 s while one is active) and the country's indexes (IHSG, LQ45) |
| Other | notes (series without data, an index not stored yet) and the disclaimer |

Details: [MACRO_DOCS.md](MACRO_DOCS.md), section 6a.

### 3.2c Macro › AI Analysis - `/macro/analysis`, `/macro/analysis/[id]` (`COMPANIES`)

| Page | Content |
|---|---|
| `/macro/analysis` | `macro-analysis-form.tsx`: country (Indonesia; the others "coming later"), index, the four agents, the models, the budget and what the database holds; "Run analysis" opens the report. `macro-analyses-table.tsx`: the analyses of every country and index, most recent first (country · index, status, investability, action, conviction, cost, requested), paged, polled while one is active |
| `/macro/analysis/[id]` | `macro-analysis-report.tsx`: progress while it runs (polls every 3 s), then the tiles (investability, action, rules score, AI cost, duration), the executive summary (thesis, bull / bear case, risks, monitor, sector tilts, data gaps, adjustment), one card per agent (prior, AI, final score, stance, thesis, supports, risks, buy / sell when, reflection), the research brief (evidence refs, tool results, ReAct steps), the rules the agents read, the commodities and currencies (with the rupiah against its Asian peers), notes, model calls and the PDF download |

Route handlers: `/api/macro/analyses` (GET list, POST), `/api/macro/analyses/[id]`, `/api/macro/analyses/[id]/pdf`.
Details: [MACRO_ANALYSIS_DOCS.md](MACRO_ANALYSIS_DOCS.md).

### 3.3 Ingestion - `/ingestion/xbrl`, `/ingestion/prices`, `/ingestion/screening-data`, `/ingestion/rag-pdf`, `/ingestion/rag-news`, `/ingestion/macro` (`INGESTION`)

Sidebar entry below Screening, a dropdown like the Admin Center with six pages (`/ingestion` opens the
first; in the collapsed sidebar the icon opens it). Pages, sidebar entries and breadcrumbs come from
`src/lib/ingestion-sections.ts`. Everything runs in the background
([INGESTION_JOBS_DOCS.md](INGESTION_JOBS_DOCS.md)).

| Page                         | Card                                                                                  |
|------------------------------|---------------------------------------------------------------------------------------|
| IDX XBRL (`/ingestion/xbrl`) | Financial statement upload                                                            |
| Price Ingestion (`/ingestion/prices`) | Left: price ingestion of one company, or with the market **Forex** / **Commodities** of one currency / commodity (the second dropdown becomes Currency / Commodity; `/api/markets/{market}/ingestions`). Right: "Update all companies to today" (`price-update-card.tsx`: companies up to date / behind / without prices against the last completed trading day, the IHSG and LQ45 indexes with them (each with its stored date); with a market "Update all currencies / commodities to today": each instrument with its last date, `/api/markets/{market}/updates`) and the IHSG and LQ45 indexes (`index-price-card.tsx`: the stored range of each, one fetch for both, full history, a link to each chart). Exchanges, stored companies, `GET /api/v1/prices/status` per exchange, `GET /api/v1/markets` and the price provider are loaded for this page only |
| Screening Data IDX (`/ingestion/screening-data`) | Screening data (stored listings, active ETL run)                          |
| PDF Documents (RAG) (`/ingestion/rag-pdf`) | PDF document upload, then the stored PDF documents ([RAG_DOCS.md](RAG_DOCS.md)) |
| News (RAG) (`/ingestion/rag-news`) | News ingestion (IDX companies, date range), then the stored news articles      |
| Macro (`/ingestion/macro`) | `macro-card.tsx`: "Update all macro data to today" (counts per state), then one table per region (Indonesia, United States, Global and China): series with code, frequency, unit and the presets using it; source (link) and its publication lag; latest value and date; state (up to date, behind and when due, not fetched, failing and why, CSV upload); Fetch, Full, CSV upload, Chart (a sheet with the history and the latest observations with their release dates). `GET /api/v1/macro/series`; route handlers `/api/macro/ingestions`, `/updates`, `/series/{code}`, `/series/{code}/upload` ([MACRO_DOCS.md](MACRO_DOCS.md)) |

Every page shows its card, then the summary tiles and the jobs table. The table opens on the page's
own job type (IDX XBRL: Financial statements, Price Ingestion: Prices, Screening Data IDX: Screening
data, PDF Documents (RAG): PDF (RAG), News (RAG): News (RAG); `jobType` in `lib/ingestion-sections.ts`);
the tabs show the other types or "All". Server side: `components/ingestion/ingestion-page.tsx`
(permission check, the page's data plus the first page of its jobs, error state); client side:
`ingestion-page-client.tsx`.

The jobs table has a "Cost" column: the USD cost and the number of model calls of a job (`cost` of
`GET /api/v1/ingestions`; financial statement uploads, screenings and analyses), `*` when a call had to be
priced from the list prices, a dash for a job without model calls. The job details show "Model cost"
(`components/ingestion/job-usage.tsx`): the calls per stage and model with prompt, cached and completion
tokens and the cost, and their total - for an upload the standard extraction (plan, tool turns, review;
DeepSeek) and the full-text extraction (Sonnet) side by side
([AI_INGESTION_DOCS.md](AI_INGESTION_DOCS.md), section 7).

| Element                    | Content                                                                                                                         |
|----------------------------|---------------------------------------------------------------------------------------------------------------------------------|
| Financial statement upload | description of the accepted format (IDX XBRL `.xlsx` only, 50 MB, stored once per checksum); drop zone / file picker; client-side check of extension and size; result notice (queued, stored file reused, already running) |
| Price ingestion            | exchange and ticker dropdowns (companies stored per exchange), "re-fetch the full history" option, latest stored price date     |
| PDF document upload        | exchange and company dropdowns (`company-picker.tsx`; a file name ending with a stored ticker selects it), `.pdf` drop zone, client-side check of extension and size, result notice |
| News ingestion             | exchange (IDX) and company dropdowns, presets This month / Last 7 days / Last 30 days / Previous month / Custom range, From / To date inputs (Jakarta time; today comes from the server), live check of the range (`src/lib/date-range.ts`: start after end, future, over 366 days) |
| Stored documents (RAG)     | badge with stored documents and chunks, ticker filter (tickers starting with it, applied once typing pauses), search box (5 closest chunks with distance, pages or date, link to the article), table: company, document / article (link), pages or published date, chunks, stored; pages as in the jobs table (10 rows, or 5 / 20 / 50; first / previous / next / last); reloaded when a job finishes |
| Summary tiles              | in progress, done, incomplete, failed (from `counts`)                                                                           |
| Jobs table                 | type tabs (all / financial statements / prices / screening data / screenings / PDF (RAG) / News (RAG) / analyses / macro / macro analyses, scrollable on small screens), status filter, job, status badge, progress (`stage` + `message`), requested (time and "by <username>", "Scheduled run" without a user), duration, download icon on upload rows; pages (`components/table-pagination.tsx`): "Showing 11–20 of 57 jobs", rows per page 5 / 10 / 20 / 50 (default 10), "Page 2 of 6", first / previous / next / last; changing the type, status or rows per page, or submitting a job, goes back to page 1; the current page is refreshed every 2 s while a job is active, every 10 s otherwise |
| Detail sheet               | file (size, SHA-256, new / reused) with a "Download file" button, started by (name and username, deleted user, or scheduled run), timings, attempts, result summary (verification and agent metrics incl. model retries when there were any, or price days, valuation and the currency conversion of a listing quoted in another currency, or RAG pages / chunks / model, or news range, per-source counts and articles not stored), raw JSON |

Download (`DownloadFileButton`): the file is fetched first and then saved under the name of that
upload, so a failure (e.g. backend unreachable) shows a message instead of a broken download.

When a job seen in progress finishes, the page re-renders its server data (`router.refresh()`), so
a new company appears in the ticker list, the latest price date is current and new RAG documents are listed.

### 3.3a Screening - `/screening`, `/screening/{id}`, `/screening/selected`, `/screening/selected/{id}` (`SCREENING`)

Below Companies in the sidebar, a dropdown like Ingestion and the Admin Center with three pages
(`lib/screening-sections.ts`): **Screening Stocks** (`/screening`, this section), **Selected Stocks**
(`/screening/selected`, below) and **Analysis** (`/screening/analysis`, section 3.3b). The sidebar
highlights the sub-page with the longest matching URL, so `/screening/analysis/{id}` is Analysis, not
Screening Stocks; the breadcrumb reads "Screening / Screening Stocks", "Screening / Selected Stocks" or
"Screening / Analysis" (and "/ Report" on a report).

The Screening Stocks form chooses the stock exchange (IDX), the market cap (large,
mid, small), the top N (1-50) and the investor agents (multi-select checkboxes: Buffett, Munger,
Lynch, Fisher, Keith Gill, Risk); **Start screening** queues the run and opens its report. The
saved screenings are listed below (refreshed every 3 s while one runs). The report page polls while
the run is active (stage, cost) and then shows the executive summary, the final ranking (score per
agent, conviction, news sentiment; a row opens the stock's details: thesis, red flags, news brief
and the research agent's ReAct steps, headlines, metrics, every agent's reasoning, reflection and
scorecard), the rest of the shortlist, the Stage 1 funnel, notes and Reflexion lessons, token usage,
and **Download PDF**. Details: [SCREENING_DOCS.md](SCREENING_DOCS.md).

**Selected Stocks** (`/screening/selected`, report `/screening/selected/{id}`) screens stocks chosen from
the companies table instead of a market-cap tier, with the same steps and the same report. The form
(`components/screening/selection-form.tsx`) lists the exchange's companies (`GET /api/v1/screenings/companies`)
with a search (ticker, name, sector), a checkbox per stock (up to 100), the stored market cap and data date
(or "No market data stored"), the selected stocks as removable chips, the top N (at most the number
selected) and the investor agents. A chosen stock that fails a tradability check (liquidity, price, trading,
watchlist) is analysed anyway and shows the reason among its red flags. Each page lists only its own runs (`scope=TIER` / `scope=SELECTION`);
a report opened under the other page's URL (e.g. from the Ingestion jobs table) is redirected to its own
(`components/screening/report-page.tsx`, shared by both report routes).

The Ingestion page "Screening Data IDX" has the "Screening data" card (runs the ETL); its jobs table opens
on the ETL runs (tab "Screening data"), and every Ingestion page lists them and the screenings under the
tabs "Screening data" and "Screenings".

### 3.3b Analysis - `/screening/analysis`, `/screening/analysis/{id}` (`SCREENING`)

An in-depth AI analysis of one stock ([ANALYSIS_DOCS.md](ANALYSIS_DOCS.md)). The form
(`components/analysis/analysis-form.tsx`) chooses the stock (ticker dropdown over the companies table)
and shows what the database holds for it: reporting periods (latest), Yahoo Finance market data (date),
PDF documents and news articles, each missing one with the Ingestion page that adds it; then the
investor agents (all six checked by default). **Start analysis** queues it and opens its report (a
stock already being analysed opens that analysis). The saved analyses are listed below
(`analyses-table.tsx`: stock, status and stage, overall score and verdict, conviction, cost and tokens,
requested; 10 per page, 5 / 10 / 20 / 50, refreshed every 3 s while one runs).

The report (`analysis-report.tsx`) polls every 3 s while the analysis runs, then shows: status and
stage, tiles (overall score with verdict and quantitative overall, AI cost against the budget, tokens,
duration), the executive summary (conviction, thesis, bull and bear case, key risks, what to monitor,
data gaps, the synthesis adjustment and its reason), every investor agent (`components/screening/
agent-card.tsx`, shared with the screening), the research brief (business, moat, management, growth,
catalysts and risks, news, evidence with refs; the excerpts read with their pages or dates and the
ReAct steps in collapsible lists), key figures of the stored periods (`lib/analysis.ts`; rows without any
value are hidden, e.g. gross margin of a bank) with the latest valuation, the data the agents used,
notes and Reflexion lessons, token usage (`components/screening/usage-table.tsx`, shared) and
**Download PDF** (`download-pdf-button.tsx`, shared).

The Ingestion jobs table lists analyses under the tab "Analyses"; their job detail links to the report.

### 3.4 Login - `/login`

Username and password (show / hide), the "session ended" note after an expiry, the error of the
backend ("Invalid username or password", deactivated account). After the login the browser opens
`next` (only same-site pages) or the first allowed page; a logged-in user opening `/login` goes there
directly.

### 3.5 Admin Center - `/admin/users`, `/admin/roles` (`ADMIN`)

Sidebar group "Admin Center" below Ingestion, a dropdown with **User Management** and **Role
Management** (`/admin` opens User Management).

| Page            | Content                                                                                                                                  |
|-----------------|------------------------------------------------------------------------------------------------------------------------------------------|
| User Management | tiles (users, active, deactivated, administrators), search, status filter, table (avatar, name, username, email, roles, status, updated); a row opens the sheet to view and edit; add user; delete with confirmation (disabled for the root user and yourself, with the reason) |
| User sheet      | username (create only), email, password (create) / new password (edit, optional), full name, phone, date of birth, address, roles (checkboxes with their permissions; the Administrator role is locked for the root user), active (locked for the root user and yourself); field errors of the backend under each field |
| Role Management | one tile per permission (how many roles have it), table (name, built-in badge, description, permissions, users, updated), add role, delete with confirmation (disabled for the built-in role and roles assigned to users) |
| Role sheet      | name, description, the three permissions as checkboxes with their descriptions (at least one); the built-in role allows only the description |

After a change the page data is reloaded from the server (`router.refresh()`).

### 3.6 Profile - `/profile` (any logged-in user)

Opened from the avatar in the sidebar footer (the log-out button sits next to it). Picture
(upload PNG / JPEG / WebP / GIF up to 2 MB, remove; initials when there is none), roles and
permissions, username, email and full name read-only, address, phone and date of birth editable.

## 4. Formatting

`src/lib/format.ts` uses a fixed `en-US` locale so server and client render identical text (no
hydration mismatches). Missing values show `—`. Compact amounts use `K / M / B / T`
(`IDR 33.81T`); dates are `30 Jun 2026` (parsed from ISO strings, no time-zone shifts). Share prices and
per-share amounts (`formatPrice`: header, market panel, valuations, EPS and share rows of the
statements) keep 2 decimals, 6 below 1, so prices converted into a reporting currency stay readable
(INDY in USD: `0.143175`, not `0.14`). A converted listing (CUAN, DSSA, INDY) shows its IDX quote as the price: the
header's last close, the Latest price card (open .. close in IDR, the USD close and the rate), the Market value card
and a "Quoted price" column of the valuations table.

## 5. Structure

```text
frontend/
├── components.json                        shadcn config (base-nova, from shadcn-fintech)
├── .env.example                           NERACA_API_URL template (copy to .env.local)
└── src/
    ├── app/
    │   ├── layout.tsx                     fonts, ThemeProvider, TooltipProvider, metadata
    │   ├── page.tsx                       redirect to the first page the user may open
    │   ├── not-found.tsx                  404 page
    │   ├── (dashboard)/
    │   │   ├── layout.tsx                 sidebar, breadcrumb, theme toggle
    │   │   ├── error.tsx                  error boundary for unexpected errors
    │   │   ├── companies/page.tsx         page 1 (+ loading.tsx)
    │   │   ├── companies/[exchange]/[ticker]/page.tsx   page 2 (+ loading.tsx, not-found.tsx)
    │   │   ├── ingestion/xbrl, prices, screening-data, rag-pdf, rag-news/page.tsx   page 3 (+ loading.tsx; ingestion/page.tsx redirects)
    │   │   ├── screening/page.tsx, screening/[id]/page.tsx   Screening Stocks and its reports
    │   │   ├── screening/selected/page.tsx, screening/selected/[id]/page.tsx   Selected Stocks and its reports
    │   │   ├── screening/analysis/page.tsx, screening/analysis/[id]/page.tsx   Analysis and its reports
    │   │   ├── admin/users/page.tsx, admin/roles/page.tsx   Admin Center (admin/page.tsx redirects)
    │   │   └── profile/page.tsx           own profile
    │   ├── login/page.tsx                 login (outside the dashboard layout)
    │   └── api/                           Route Handlers: auth, admin, profile and ingestion APIs (section 2)
    ├── components/
    │   ├── ui/                            shadcn-fintech ui components
    │   ├── companies/                     company list client component
    │   ├── company/                       detail view, header, overview, statement tables, panels
    │   ├── ingestion/                     page (server) and page client, upload / price / screening data / RAG PDF / RAG news cards, company picker, RAG documents card, jobs table, job detail sheet, download button
    │   ├── screening/                     screening form, runs table, report, ranking, candidate sheet, agent card, usage table, PDF button
    │   ├── analysis/                      analysis form, analyses table, analysis report
    │   ├── admin/                         users / roles pages, user and role sheets, delete confirmation
    │   ├── auth/, profile/                login form, profile page
    │   └── app-sidebar, dynamic-breadcrumb, empty-state, stat-tile, api-error-state, ...
    ├── hooks/use-mobile.ts
    ├── lib/                               api client (server), client-api, permissions, types, formatting, ...
    └── proxy.ts                           redirects pages without a session to /login
```

## 6. Docker

`frontend/Dockerfile` builds the Next.js standalone server (`NEXT_OUTPUT=standalone`, see
`next.config.ts`; local builds keep the normal output) on `node:22-alpine` and runs
`node server.js` on port 3000 as a non-root user, with a health check on `/companies`. The
full-stack `docker-compose.yaml` in the repository root starts it with
`NERACA_API_URL=http://backend:8080` after the backend is healthy. Start and stop everything with
the scripts in `scripts/` ([DOCKER_DOCS.md](DOCKER_DOCS.md)).

## 7. Development

```bash
cd frontend
npm install
cp .env.example .env.local    # only if the backend is not on http://localhost:8080
npm run dev                   # http://localhost:3000
npm run lint && npm run build # checks before committing
```

The dev server needs a backend: the full stack from the start scripts (its backend is on
port 8080), or `cd backend && docker compose up -d --build`. While the full stack runs, its
frontend container already uses port 3000; stop it first or run the dev server on another port
(`npm run dev -- -p 3001`).

Checks done for v1: `tsc`, ESLint, `next build`, the Docker image build, and every route and tab
rendered against the backend with the HRTA seed data and an uploaded INDF filing (data, empty
states, 400 / 404), both with `next dev` / `next start` and in the Docker stack.

Notes:

- With the `loading.tsx` skeletons the response is streamed, so a missing company returns HTTP
  200 with `<meta name="robots" content="noindex">` and the not-found UI (documented Next.js
  behaviour for streamed responses).
- `AGENTS.md` / `CLAUDE.md` are generated by create-next-app and re-added by `next dev`; they point
  coding agents to the bundled Next.js 16 docs in `node_modules/next/dist/docs/`.

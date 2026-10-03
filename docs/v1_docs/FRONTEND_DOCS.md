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
| Charts     | Recharts 3 through the shadcn `ChartContainer`                                                                                                                                |
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
| `GET /api/v1/ingestions?limit=100`          | ingestion page (page 3)  |
| `GET /api/v1/prices/ingestions`             | ingestion page: configured price provider |

The ingestion page also calls the backend from the browser through Next.js Route Handlers in
`src/app/api/` (same server-side `NERACA_API_URL`; `forward()` in `src/lib/api.ts` passes status and
ProblemDetail through; unreachable backend = 503):

| Route Handler                               | Backend                                                  |
|---------------------------------------------|----------------------------------------------------------|
| `POST /api/financial-statements/upload`     | `POST /api/v1/financial-statements/upload`               |
| `POST /api/prices/ingestions` (JSON body)   | `POST /api/v1/prices/ingestions?exchange=&ticker=&full=` |
| `GET /api/ingestions?type=&status=&limit=`  | `GET /api/v1/ingestions` (table polling)                 |
| `GET /api/ingestions/{id}`                  | `GET /api/v1/ingestions/{id}` (detail sheet)             |
| `GET /api/ingestions/{id}/file`             | `GET /api/v1/ingestions/{id}/file` (download, streamed with name / type / size / checksum headers by `forwardFile()`) |

## 3. Pages

### 3.1 Company list - `/companies?exchange=IDX`

`/` redirects here; `exchange` defaults to `IDX` and is case-insensitive.

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

### 3.3 Ingestion - `/ingestion`

Sidebar entry below Companies. Everything runs in the background
([INGESTION_JOBS_DOCS.md](INGESTION_JOBS_DOCS.md)).

| Element                    | Content                                                                                                                         |
|----------------------------|---------------------------------------------------------------------------------------------------------------------------------|
| Financial statement upload | description of the accepted format (IDX XBRL `.xlsx` only, 20 MB, stored once per checksum); drop zone / file picker; client-side check of extension and size; result notice (queued, stored file reused, already running) |
| Price ingestion            | exchange and ticker dropdowns (companies stored per exchange), "re-fetch the full history" option, latest stored price date     |
| Summary tiles              | in progress, done, incomplete, failed (from `counts`)                                                                           |
| Jobs table                 | type tabs (all / financial statements / prices), status filter, job, status badge, progress (`stage` + `message`), requested, duration, download icon on upload rows; refreshed every 2 s while a job is active, every 10 s otherwise |
| Detail sheet               | file (size, SHA-256, new / reused) with a "Download file" button, timings, attempts, result summary (verification and agent metrics, or price days and valuation), raw JSON |

Download (`DownloadFileButton`): the file is fetched first and then saved under the name of that
upload, so a failure (e.g. backend unreachable) shows a message instead of a broken download.

When a job seen in progress finishes, the page re-renders its server data (`router.refresh()`), so
a new company appears in the ticker list and the latest price date is current.

## 4. Formatting

`src/lib/format.ts` uses a fixed `en-US` locale so server and client render identical text (no
hydration mismatches). Missing values show `—`. Compact amounts use `K / M / B / T`
(`IDR 33.81T`); dates are `30 Jun 2026` (parsed from ISO strings, no time-zone shifts).

## 5. Structure

```text
frontend/
├── components.json                        shadcn config (base-nova, from shadcn-fintech)
├── .env.example                           NERACA_API_URL template (copy to .env.local)
└── src/
    ├── app/
    │   ├── layout.tsx                     fonts, ThemeProvider, TooltipProvider, metadata
    │   ├── page.tsx                       redirect to /companies
    │   ├── not-found.tsx                  404 page
    │   ├── (dashboard)/
    │   │   ├── layout.tsx                 sidebar, breadcrumb, theme toggle
    │   │   ├── error.tsx                  error boundary for unexpected errors
    │   │   ├── companies/page.tsx         page 1 (+ loading.tsx)
    │   │   ├── companies/[exchange]/[ticker]/page.tsx   page 2 (+ loading.tsx, not-found.tsx)
    │   │   └── ingestion/page.tsx         page 3 (+ loading.tsx)
    │   └── api/                           Route Handlers proxying the ingestion APIs (section 2)
    ├── components/
    │   ├── ui/                            shadcn-fintech ui components
    │   ├── companies/                     company list client component
    │   ├── company/                       detail view, header, overview, statement tables, panels
    │   ├── ingestion/                     upload card, price card, jobs table, job detail sheet, download button
    │   └── app-sidebar, dynamic-breadcrumb, empty-state, stat-tile, api-error-state, ...
    ├── hooks/use-mobile.ts
    └── lib/                               api client, types, formatting, statements, links, tabs, ingestion
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

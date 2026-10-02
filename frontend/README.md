# Neraca Lab - Frontend

Next.js 16 (App Router, React 19, TypeScript) with the
[shadcn-fintech](https://github.com/abderrahimghazali/shadcn-fintech) theme: shadcn/ui
`base-nova` components on Base UI, Tailwind CSS 4, Recharts, lucide icons, light / dark mode.
Created with `npx create-next-app@16.3.8`.

| Page                             | Content                                                                                                                                                |
|----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| `/companies?exchange=IDX`        | companies of an exchange: summary tiles, exchange filter, search, sector filter, sortable table                                                        |
| `/companies/{exchange}/{ticker}` | everything stored for a company, in tabs: overview, income statement, balance sheet, cash flow, segments, metrics, valuation, market & shares, filings |

Full description: [`docs/v1_docs/FRONTEND_DOCS.md`](../docs/v1_docs/FRONTEND_DOCS.md).

## Run

Requires Node.js 20.9+ and the backend (default `http://localhost:8080`).

```bash
cd frontend
npm install
cp .env.example .env.local      # optional: NERACA_API_URL if the backend is not on localhost:8080
npm run dev                     # http://localhost:3000
```

| Script          | Purpose                          |
|-----------------|----------------------------------|
| `npm run dev`   | development server (Turbopack)   |
| `npm run build` | production build                 |
| `npm start`     | serve the production build       |
| `npm run lint`  | ESLint (Next.js core web vitals) |

`NERACA_API_URL` is read by the Next.js server only: pages fetch the backend in Server
Components, so the browser never calls the backend and the backend needs no CORS setup.

## Docker

`Dockerfile` builds the standalone Next.js server (`node server.js`, port 3000). It is started
together with the backend, postgres and redis by the scripts in `../scripts/`
(`start-windows.bat`, `start-mac.sh`, `start-linux.sh`); see the root README.

## Structure

```text
src/
├── app/
│   ├── layout.tsx                         fonts, theme provider (next-themes), tooltips
│   ├── page.tsx                           redirects to /companies
│   └── (dashboard)/                       sidebar shell (shadcn-fintech inset sidebar)
│       ├── companies/page.tsx             page 1: company list
│       └── companies/[exchange]/[ticker]/ page 2: company detail (+ loading, not-found)
├── components/
│   ├── ui/                                shadcn-fintech ui components (base-nova)
│   ├── companies/                         list page client component
│   └── company/                           detail page: header, overview, statement tables, panels
└── lib/
    ├── api.ts                             server-side backend client (ApiError, no-store fetch)
    ├── types.ts                           API response types
    ├── format.ts                          number / date formatting (fixed en-US locale)
    ├── statements.ts                      statement line items and period types
    └── links.ts, tabs.ts                  URLs and detail tabs
```

## Credits

Theme, ui components, sidebar shell, empty-state illustrations and theme toggle are adapted from
shadcn-fintech by Abderrahim Ghazali (MIT): see `THIRD_PARTY_LICENSE_shadcn-fintech.txt`.

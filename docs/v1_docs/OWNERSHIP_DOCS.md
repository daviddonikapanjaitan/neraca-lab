# Ownership (>=5%): KSEI's daily lists of the shareholders of more than 5%

Every trading day KSEI publishes on idx.co.id the list of the investors holding more than 5% of a listed company,
by investor (SID): **Kepemilikan Efek Diatas 5% Berdasarkan SID (Publik)**, an Excel workbook named like
`peng-2026-10-27-00104-lima-persen.xlsx` (announcement 00104 of 27 October 2026). Neraca Lab reads these lists into
the database (**Ingestion > Excel Documents (RAG)**), shows them on **Companies > Ownership (>=5%)** in the manner of
Companies > Registration (who bought or sold, day by day, by ticker and by investor), and writes what they say about
each stored company into the RAG vector store for the AI analysis (**Screening > Analysis**).

The lists of 1 - 27 October 2026 are kept locally (`data/` is not in the repository) in
`data/IDX_OWNERSHIP_FIVE_PERCENTAGE/`.

## 1. Flow

```text
Ingestion > Excel Documents (RAG): Exchange "IDX - Indonesia Stock Exchange", Document type "Ownership (>=5%)",
one or more peng-...-lima-persen.xlsx (uploaded oldest first, one job each)
  -> POST /api/v1/rag/xlsx (exchange=IDX, documentType=OWNERSHIP_5_PERCENT)
     the workbook is read at once: not a workbook / not the list -> 400 / 422, nothing stored
  -> RAG_XLSX job (OwnershipQueue: ONE worker, the lists are stored one after the other):
     1. OwnershipParser reads the list (two dates, every investor and its accounts)
     2. ownership_report + ownership_holder + ownership_account (the day's list replaces a stored one of the same day)
     3. for every company of the list stored in `company`: its ownership document is written again from EVERY stored
        day (OwnershipDigest.text) -> chunks -> embeddings -> rag_document (source XLSX) + rag_chunk
  -> Companies > Ownership (>=5%) (GET /api/v1/ownership...)
  -> Screening > Analysis: `majorShareholders` in the dossier, the research agent's searchOwnership tool (refs O1, ...)
```

The job's stage reads "List of 2026-10-27 stored: 1,921 investors of 843 companies; <n> buys, <n> sells, 3 crossed
5%, 3 fell below 5%; <n> ownership documents (<n> chunks) in the vector store" (without the list of the day before:
"(the list of 2026-10-26 is not stored: no exits)"); its result is the `OwnershipResult` (dates,
announcement, investors, accounts, aliases, buys, sells, unchanged, crossed, fell below, documents and chunks written,
companies not stored, warnings, notes). The job detail links to Companies > Ownership (>=5%) for that day.

## 2. Reading a list (`OwnershipParser`)

- **Header**: the row with "Kode Efek" (within the first 30 rows of any sheet). Columns are found by their header, not
  by position: No, Kode Efek, Nama Emiten, Nama Pemegang Rekening Efek (the account's broker or custodian), Nama
  Pemegang Saham (the investor), Nama Rekening Efek (the account), Kebangsaan, Domisili, Status (Lokal/Asing) and two
  blocks "Kepemilikan Per 26-OCT-2026" / "Kepemilikan Per 27-OCT-2026", each with "Jumlah Saham" (per account),
  "Saham Gabungan Per Investor" and "Persentase Kepemilikan Per Investor (%)" in the row below. English and Indonesian
  months are read (OCT / OKT, MAY / MEI, AUG / AGU / AGT, DEC / DES, ...). Addresses ("Data tidak ditampilkan") and the
  column "Perubahan" are not read.
- **Title** (above the header): must name 5% ("KEPEMILIKAN EFEK DIATAS 5% ... per tanggal 27 Oct 2026"); a list of 1%
  is refused ("choose Ownership (>1%), a later feature"); its date must be the second date (else a warning). The first
  date must be before the second.
- **Rows**: an investor starts at a row with its number (No); the rows below it with the same ticker and no number
  are its other accounts (the first account is on the investor's row). Merged cells (No, name, totals) are read from
  their first row. The legend below the table (Keterangan, * Font: ...) is skipped.
- **Numbers** - the share counts are text ("1,197,023,942"), except some cells that were turned into numbers on the
  way to the workbook, with the thousands separator read as a decimal point:

  | In the sheet | Means | Rule |
  |--------------|-------|------|
  | `"1,197,023,942"` (text) | 1,197,023,942 | exact (also `1.197.023.942`) |
  | `441.4` (number with a fraction) | 441,400 | always thousands |
  | `200` (whole number below 1,000) | 200 or 200,000 | decided by the investor's combined shares: every combination of its accounts is tried and the one adding up to the total is taken (ABMM's Lo Kheng Hong: 200 = 200,000; GPRA's Abadimukti Gunalestari: 200 and 600 as written, 120 = 120,000); when the total itself is such a number, by the percentage against the shares listed implied by the company's largest other holder (the 5.00% holder of 500 = 500,000) |
  | `-` | no holding that day | the investor was not in the list (crossed 5% since) or the account was opened / closed |

  What nothing decides is read as written, with a **note** (MAMIP: three holders of 500, 863 and 482, nothing to tell
  them from thousands). Accounts that add up to no total are a **warning** (the job is then `INCOMPLETE`). On the
  seven October lists every investor's accounts add up to its total: no warning.
- **The same investor under another name** (an alias): KSEI lists an investor once per name its accounts are
  registered under, with the same accounts each time (SIAP: "EVIO SECURITIES, PT", "PT. EVIO SECURITIES" and
  "PT.EVIO SECURITIES", ten identical accounts; about 60 per list, mostly "PT X" / "X, PT" and PT ASABRI). An
  investor of the same company with the same totals and accounts as one listed before is stored with `alias_of` = the
  first one's number: counted once everywhere, shown as "Also listed as ...".
- **Investor key**: the name upper case, letters and digits only, without "PT" and "Tbk" (`holder_key`: "PT. EVIO
  SECURITIES" -> "EVIO SECURITIES"); the same investor across days and companies.

## 3. Movements

Per investor and day (`ownership_holder.movement`, generated): **BUY** (more combined shares than the day before),
**SELL**, **UNCHANGED**, **ENTERED** (not in the list the day before: crossed 5%), **EXITED**.

KSEI does **not** list an investor that fell below 5%: it is simply missing from the next list (TRUK 27 October 2026:
Suwito is gone, JPMorgan Chase Bank NA Singapore Branch enters with the same 21,750,000 shares, 5.00%; BAJA 21 - 23
October: Ibnu Susanto enters with 11.27%, then is replaced by PT Sarana Steel with 10.04%). The view
`v_ownership_movement` therefore adds, for every list whose day before is stored, each investor of the day before
that is not in it (`left_list`, movement EXITED, its last shares as "before"). A list whose day before is not stored
cannot tell who fell below 5%: its "fell below 5%" is unknown (shown as "?", never as 0), and the page names the list
to upload. Upload consecutive days.

`account_moves`: the accounts whose shares changed. With UNCHANGED, shares moved between the investor's own accounts
(ADRO 27 October: Adaro Strategic Investments, 383,000,000 from its HSBC account to its Trimegah account).

A percentage may change without a trade (the shares listed changed: BRNA 40.40% -> 39.13% with the same shares): the
change in percentage points is shown beside the change in shares.

## 4. Storing (`OwnershipRepository`)

- `ownership_report`: one per exchange, document type and date; uploading the list of a day again replaces it with
  its investors and accounts (outcome `REPLACED`).
- `ownership_holder`: each investor, its numbers of both dates (`change_shares`, `movement` generated), nationality,
  domicile, status L / A, accounts, account moves, alias.
- `ownership_account`: each securities account (participant = broker or custodian, account name, shares of both dates).
- No foreign key to `company`: the lists cover every listed company (843), most of which are not stored; the ticker
  joins them.
- The RAG documents are linked to their company (`rag_document.company_id`, source type `XLSX`, `source_key`
  `OWNERSHIP_5_PERCENT`, one per company, `published_at` = the latest list); deleting the company deletes its document.

## 5. In the RAG vector store and the AI analysis

- **One document per stored company**, written again from every stored day at each upload (not one per day: seven
  near-identical days would crowd the search results). Its text (`OwnershipDigest.text`, English): the latest day's
  investors (name, aliases, local / foreign, domicile, shares, percentage, accounts with their brokers and custodians,
  change since the first stored day), the totals (local, foreign), every dated movement (bought / sold, crossed 5%,
  fell below 5%), the days with moves between accounts, the former holders. Each chunk is embedded with "<ticker>
  <name> - shareholders of more than 5% (KSEI), as of <date>" in front of it; the stored text is the chunk alone.
- The companies of a list that are not stored get no document; a company stored later gets it with the next upload.
- **Screening > Analysis** (`AnalysisService`):
  - `majorShareholders` in the dossier of every investor agent and in the synthesis input (no model, from the tables:
    `OwnershipDigest`): the reading, totals, holders (ownership as a fraction of the shares listed), at most 40 dated
    movements (15 for the synthesis), former holders. Saved in the report context; the report page and its PDF show
    it as "Shareholders of more than 5%". Without any list naming the company: a data note.
  - the research agent's `searchOwnership(query)` tool searches the company's ownership document (refs O1, ...).
  - the analysis form shows "7 daily lists of the shareholders of more than 5% (latest 27 Oct 2026)".

## 6. API

Upload and lists (permission `INGESTION`):

| Method | Path | Answer |
|--------|------|--------|
| `POST` | `/api/v1/rag/xlsx` (multipart `file`, `exchange` = IDX, `documentType` = `OWNERSHIP_5_PERCENT`) | 202 new job, 200 the same file already queued / running; 400 not `.xlsx` / no ZIP header / type missing, unknown or not available yet (`OWNERSHIP_1_PERCENT`) / unknown exchange; 422 not the list (`Not a readable ownership list`); 413 too large |
| `GET` | `/api/v1/rag/xlsx/status` | the exchanges ("IDX - Indonesia Stock Exchange") with their document types (`available`), lists stored, latest date, ownership documents in the vector store, queue |
| `GET` | `/api/v1/rag/xlsx/reports[?limit&offset]` | the stored lists, latest first: dates, file, announcement, investors, companies, accounts, aliases, buys, sells, unchanged, entered, exited (null: the day before is not stored), warnings, notes |

Companies > Ownership (>=5%) (permission `COMPANIES`):

| Method | Path | Answer |
|--------|------|--------|
| `GET` | `/api/v1/ownership[?tickers=ADRO,BBCA&holder=lo kheng hong&transaction=ALL\|BUY\|SELL\|CHANGED\|ENTERED\|EXITED\|CROSSED\|ACTIVITY&status=L\|A&from=2026-10-19&to=2026-10-27&aliases=false&sort=DATE\|CHANGE_DESC\|CHANGE_ASC\|POINTS_DESC\|POINTS_ASC&limit=100&offset=0]` | one page of investors: `{ total, limit, offset, rows }`; `sort` DATE (the default: latest day first, then ticker and list order), CHANGE_DESC / CHANGE_ASC (by the change in shares: the largest buy first / the largest sell first), POINTS_DESC / POINTS_ASC (by the change in percentage points); rows without a change (crossed 5% or fell below it) last either way, equal changes by day; a row has the dates, company, investor, aliases, status, shares and percentages before and after, `change`, `percentChange` (pp), `movement`, accounts, `accountMoves`, `leftList`. 400: unknown transaction or status, a date that is not `yyyy-mm-dd`, first date after the last, unknown sort, bad ticker, more than 50 tickers, a name over 100 characters |
| `GET` | `/api/v1/ownership/options` | the latest list (as in reports), every date, every company (holders on its latest day, first / last date, days, `stored`), the filter and sort options, the latest day's 8 largest buys and sells (percentage points, then shares) and every investor that crossed 5% or fell below it |
| `GET` | `/api/v1/ownership/highlights?date=2026-10-26` | the list of that day (as in reports) with its 8 largest buys and sells and every investor that crossed 5% or fell below it, as `options` gives them for the latest day; 400 not a date, 404 no list stored for the day |
| `GET` | `/api/v1/ownership/holders?ticker=ADRO[&from&to]` | one company: `digest` (as the AI analysis reads it) and `days`: every investor of every day (aliases and those that left included) with its accounts |
| `GET` | `/api/v1/ownership/entities?q=lo kheng hong[&limit=10]` | the investors whose key contains the words (at least 3 letters or digits), most companies first, each with every company on the latest day it is listed there and its change since the first stored list |
| `GET` | `/api/v1/ownership/reports` | as `/api/v1/rag/xlsx/reports` |

```bash
curl -H "$AUTH" -F file=@data/IDX_OWNERSHIP_FIVE_PERCENTAGE/peng-2026-10-27-00104-lima-persen.xlsx -F exchange=IDX \
     -F documentType=OWNERSHIP_5_PERCENT http://localhost:8080/api/v1/rag/xlsx
curl -H "$AUTH" "http://localhost:8080/api/v1/ownership?tickers=TRUK&transaction=CROSSED"
curl -H "$AUTH" "http://localhost:8080/api/v1/ownership/entities?q=lo%20kheng%20hong"
```

## 7. Pages

**Ingestion > Excel Documents (RAG)** (`/ingestion/rag-excel`, between PDF Documents (RAG) and News (RAG)):

- **Excel document upload**: Exchange (the exchanges of the backend: "IDX - Indonesia Stock Exchange"); once chosen,
  **Document type**: Ownership (>=5%), and Ownership (>1%) shown as a later feature (disabled); once Ownership (>=5%)
  is chosen, the drop zone: one or more `.xlsx` (up to 31), sorted by the date in their names and uploaded oldest
  first, each with its state (waiting, uploading, queued, error).
- **Stored ownership lists (>=5%)**: every list, latest first (10 per page: 5 / 10 / 20 / 50): date and the day it is
  compared with (link to Companies > Ownership (>=5%) for the day), file, announcement, warnings / notes, investors
  (+ aliases), companies, accounts, buys, sells, crossed 5%, fell below 5% ("?" when the day before is not stored,
  with a note naming the list to upload).
- **Stored ownership documents**: the companies' documents in the vector store with the search test
  (`RagDocumentsCard`, source XLSX); then the jobs table on `RAG_XLSX` ("Excel (RAG)" tab).

**Companies > Ownership (>=5%)** (`/companies/ownership`, filters in the URL):

- Tiles: the latest list (lists stored, first and last day), investors of more than 5% (companies, accounts), buys /
  sells, crossed / fell below 5% of the latest day.
- **Highlights** of a day (the latest at first): the day's counts, largest buys, largest sells, crossed above 5%, fell
  below 5%. Another stored day is chosen in its dropdown (or with the previous / next day buttons, "Latest" back to
  the latest; `/api/ownership/highlights`); "Every movement of <day>" lists the day's movements in the table. A ticker
  filters the page to the company, a name shows the investor.
- **Filters**: tickers (suggested from the stored lists), investor (part of the name), transaction (All, Buy, Sell,
  Buy or sell, Crossed above 5%, Fell below 5%, Crossed 5% either way, Any movement), investor type (local, foreign),
  From / To with presets Latest day, Last 5 lists, Every day, "also the same investor under another name", and the
  **Order**: latest day first, change (largest buy first / largest sell first), change in percentage points (largest
  rise / fall first). Clicking the Change or % owned header orders by it: largest first, then smallest first, then
  the latest day again (in the URL: `sort=CHANGE_DESC`).
- **Apply**, **Reset** and every click that filters the page (a preset, a ticker, a name, a column header) reload it
  without going back to its top (`router.push(..., { scroll: false })`): the filters and the list stay in view.
- With **one ticker**: the company's shareholders of more than 5%: the reading, tiles (held together, foreign / local,
  buys and sells, moves between accounts), a bar of each holder's share of the listed shares with the rest (holders
  below 5% and the public), each investor a row and each day a column (shares, percentage, change or "Crossed 5%" /
  "Below 5%"); a click opens its accounts (broker / custodian, account, shares of both days, change, opened / closed);
  the former holders.
- With an **investor's name**: every matching investor with every company it holds (shares, percentage, latest
  movement, "last listed" when it is no longer in the latest list, change since the first list).
- **The table** (50 rows per page: 25 / 100 / 200): date, ticker, investor (local / foreign, domicile, alias, "moved
  between accounts", also listed as ...), Buy / Sell / No change / Crossed 5% / Below 5%, shares before and after,
  change, % before -> after with the change in percentage points, accounts.

## 8. Database

`V1.0.33__schema_ownership.sql`: `ownership_report`, `ownership_holder`, `ownership_account`, the view
`v_ownership_movement`, `rag_document.source_type` `XLSX` (`ck_rag_document_source_type`). The job type `RAG_XLSX` is
listed by `V1.0.10` and `V1.0.14` (`ck_ingestion_job_type`, and `ck_ingestion_job_file`: a RAG_XLSX job has its file).
Column-level reference: `DB_SCHEMA_DOCS.md`.

## 9. Code and tests

Backend `ownership/`: `OwnershipParser`, `OwnershipRepository`, `OwnershipDigest`, `OwnershipIngestionService`,
`OwnershipQueue`, `controller/OwnershipController`; `rag/ExcelDocumentType`, `rag/controller/RagExcelController`;
analysis: `ContextTools.searchOwnership`, `AnalysisService.majorShareholders`, `AnalysisPdfRenderer`.
Frontend: `lib/ownership.ts`, `components/ingestion/rag-excel-card.tsx`, `components/ingestion/ownership-lists-card.tsx`,
`components/ownership/` (`ownership-page-client.tsx`, `company-ownership.tsx`, `entity-holdings.tsx`,
`ownership-badges.tsx`), `app/(dashboard)/ingestion/rag-excel`, `app/(dashboard)/companies/ownership`, route handlers
`app/api/rag/xlsx`, `app/api/rag/xlsx/reports`, `app/api/ownership` (`holders`, `entities`).

| Test | Covers |
|------|--------|
| `OwnershipParserTest` | the lists of 19 and 27 October as published (dates, investors, accounts, thousands written as decimals, whole numbers decided by the totals, local / foreign, aliases of SIAP, investors crossing 5%, a closed account, moves between accounts), every stored list read without warning; sheets built cell by cell: Indonesian months, a number decided by the percentage, accounts that do not add up, refusals (not a workbook, no header, the 1% list, a value that is not a number); investor keys |
| `OwnershipRepositoryTest` | two days of a company of its own (ZZOW, January 2001): buys, sells, an alias, a move between accounts, an investor crossing 5% and one leaving the list, filters, a day replaced, one company with its accounts, the digest and its text, the stored lists, an investor across companies; the order by change and by percentage points either way, rows without a change last; the
list of one day; every October 2026 list in the folder (TRUK's and BAJA's exits, the unknown exits of the first list,
Lo Kheng Hong's companies) (real database, rolled back) |
| `OwnershipControllerTest` | two lists through the upload and the worker into the tables and the vector store (stub embeddings), read back by every endpoint (the order by change, the highlights of another day, a day not stored: 404); refusals
(not `.xlsx`, no ZIP, the 1% type, no type, not the list, bad filters, unknown sort, a bad date) (removed afterwards) |
| `IngestionJobTypeConstraintTest` | `V1.0.10` and `V1.0.14` list `RAG_XLSX` |

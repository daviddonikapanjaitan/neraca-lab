# Ownership (>=1%): KSEI's monthly lists of the shareholders of more than 1%

KSEI publishes, once a month on idx.co.id, the shareholders of more than 1% of every listed company at the month end
(`peng-2026-09-00024-satu-persen.xlsx`, older names `peng-02-00011-satu-persen.xlsx`). Neraca Lab reads them on
**Ingestion › Excel Documents (RAG)** (exchange IDX, document type **Ownership (>=1%)**), stores every holding,
compares each month with the stored month before (who bought, who sold, who is new above 1%, who is no longer, which
names changed), shows it on **Companies › Ownership (>=1%)** and writes what it says about each stored company into the
RAG vector store for **Screening › Analysis**. The daily lists of the holders of more than 5% are a separate document
type with their own page: [OWNERSHIP_DOCS.md](OWNERSHIP_DOCS.md).

## 1. The lists

One sheet: a disclaimer in Indonesian and English (and `[PUBLIC]` in the newer lists), then a header row and one row
per holding:

| Column | Content |
|---|---|
| `DATE` | the month's last trading day (2026-02-27, 2026-03-31, ... 2026-09-30) |
| `SHARE_CODE`, `ISSUER_NAME` | the company |
| `INVESTOR_NAME` | the holder, as registered |
| `INVESTOR_TYPE` (to March 2026) / `INVESTOR_CLASSIFICATION` (from April 2026) | a code (CP, ID, MF, IB, IS, SC, PF, FD, OT) or a classification ("Corporate", "Mutual Funds", "Sole proprietorship", "PermanentEstablishment", ...) |
| `LOCAL_FOREIGN` | `L`, `A` or `F` (padded with blanks in February 2026); empty for a special scrip account |
| `NATIONALITY`, `DOMICILE` | individuals' nationality; domicile |
| `HOLDINGS_SCRIPLESS`, `HOLDINGS_SCRIP`, `TOTAL_HOLDING_SHARES` | shares in KSEI's accounts, as certificates, and together |
| `PERCENTAGE` | of the shares listed |

What the eight lists of February - September 2026 (`data/IDX_OWNERSHIP_ONE_PERCENTAGE/`, about 7,150 - 7,260 rows of
955 - 961 companies each) hold, and how `OnePercentParser` reads it:

- **The header moves** (row 5, 6 or 7) and the columns differ (12 to 15; April has a column without a header holding
  `#DIV/0!`): the header is found in the first 30 rows by `SHARE_CODE`, `INVESTOR_NAME`, `TOTAL_HOLDING_SHARES` and
  `PERCENTAGE`, every column by its name. Rows without a ticker and an investor are skipped (April has 11), with a note.
- **Types**: the codes and the classifications become one label (`investor_type`: CP Corporate, ID Individual, MF
  Mutual Funds, IB Financial Institution, IS Insurance, SC Securities Company, PF Pension Funds, FD Foundation, OT
  Others; "Commanditaire Vennootschap (CV) or Limited partnership " Limited Partnership (CV), "PermanentEstablishment"
  Permanent Establishment, ...) and a group (`type_group`): INDIVIDUAL, CORPORATE, FUND (mutual, exchange traded,
  hedge, pension funds, venture capital, private equity), FINANCIAL (banks, private and trustee banks, insurers,
  securities companies, brokers, investment managers and advisors, ...), GOVERNMENT (government, state-owned
  enterprises, sovereign wealth funds) or OTHER. An unknown classification keeps its text, group OTHER.
- **Local or foreign**: `L` local, `A` or `F` foreign (stored `L` / `A` as in the 5% lists); empty: unknown.
- **The same investor twice**: KSEI counts an investor by SID, and an investor without an SID by account, so a company
  may list the same investor twice (RALS, April 2026: PT RAMAYANA MAKMURSENTOSA as a scrip account of 3,965,000,000
  shares and as an SID of 203,513,800) or under two spellings ("PT. TIARA INTIMAHKOTA" and "TIARA INTIMAHKOTA"). Rows
  of a company whose names are the same once normalized are one investor (`source_rows`, `other_names`), their shares
  and percentages added, with a note.
- **A total that does not add up**: MAYA, February 2026 (scripless 0 + scrip 6,323,076,332, total 6,023,326,332,
  24.16%) and SMRA, March 2026 (3,814,000,015 + 2,123,804,901, total 5,909,304,916, 35.80%). The one whose share of
  the shares listed (the company's other rows give them: shares / percentage, the median) is closer to the printed
  percentage is kept: MAYA's sum, SMRA's total (its scripless shares then read as total - scrip = 3,785,500,015, as in
  February). A note says which.
- **Percentages** arrive as floating point (15.370000000000001): read to four decimals.

Not KSEI's list (no such header, or no holding): 422, nothing stored. A 5% list uploaded as Ownership (>=1%) and a 1%
list uploaded as Ownership (>=5%) are both refused.

## 2. The name of an investor

`OnePercentParser.holderKey`: upper case, letters and digits, without the words PT, TBK, PERSERO, PTE, LTD, LIMITED,
PRIVATE, INC and the titles DRS, DR, IR, H, HJ, which KSEI writes one month and not the next: "PT. Beyond Media",
"BEYOND MEDIA, PT" and "PT BEYOND MEDIA" are `BEYOND MEDIA`; "IR. T. PERMADI RACHMAT" (August) and "T PERMADI RACHMAT"
(September) are `T PERMADI RACHMAT`; "UOB KAY HIAN PRIVATE LIMITED" and "UOB KAY HIAN PTE LTD" are `UOB KAY HIAN`;
"LO KHENG  HONG. DRS" and "DRS LO KHENG HONG" are `LO KHENG HONG`. The key also finds an investor across companies
(the investor search of the page).

## 3. Month to month

The lists print no change and no SID, so each list is compared with the **stored list before it** (the latest stored
date before it; when a month is missing, the one before that). `OnePercentMatcher`, company by company:

1. **SAME**: the same key (the first of the month before with it).
2. **RENAMED**, by spelling: the keys at least 90% alike (1 - edit distance / longer length), the same digits, both at
   least six characters, the most alike pairs first, a pair as alike as another pair of one of its investors left as
   ambiguous. Shares changed or not ("DANA PENSIUN PERKEBUNAN" / "DANA PENSUN PERKEBUNAN").
3. **The same block**: the exact same number of shares, the only investor left with it on both sides: RENAMED when the
   names are alike (70%, or one's words all in the other's: "Eris (A)" / "ERIS"), else **MOVED**: the same block of
   shares under another name ("BANK PAN INDONESIA TBK, PT" in March, "Panin Bank Tbk, PT" in April, 388,000,000
   shares): a new name or a transfer between related holders, not a trade.

Funds of one manager differ by a word ("REKSA DANA SUCORINVEST MAXI FUND" / "... EQUITY FUND", "REKSA DANA PROTEKSI 1"
/ "... 2"): not alike enough, and not matched unless they hold the very same shares.

Then, for each investor: the investor of the month before (`previous_holder_id`, `previous_name`, `match_kind`), its
shares and percentage then, the change (`change_shares`) and the movement (`movement`, generated): **BUY**, **SELL**,
**UNCHANGED**, **ENTERED** (matched with none of the month before: new above 1%); NULL when no list before it is
stored. An investor of the month before that none of the month is (`continued` false) is **EXITED** in
`v_ownership_one_movement`: no longer above 1% (sold, or diluted below it).

**Out of order**: storing a list compares it with the stored list before it **and compares the stored list after it
with it again**, in one transaction; replacing a list does the same; `OnePercentRepository.delete(date)` compares the
list after it with the one before it. So months can be uploaded in any order and the comparisons are always between
neighbouring stored months.

## 4. Storage (`V1.0.34__schema_ownership_one_percent.sql`)

| Table / view | Content |
|---|---|
| `ownership_one_report` | one list per exchange, type (`OWNERSHIP_1_PERCENT`) and date (unique): `previous_date` (the stored list it is compared with, NULL when none), announcement number (from the file name), file, issuers, holders, `source_rows`, warnings, notes, job |
| `ownership_one_holder` | an investor of a company in a list: name, key, `other_names`, `source_rows`, type and group, `investor_status` (L / A), nationality, domicile, scripless / scrip / total shares, percentage; the comparison (`previous_date`, `previous_holder_id` -> the row of the month before, `ON DELETE SET NULL`, `previous_name`, `match_kind` SAME / RENAMED / MOVED, `shares_previous`, `percent_previous`, generated `change_shares` and `movement`) and `continued` (an investor of the next stored list is it) |
| `v_ownership_one_movement` | every investor of every list (`left_list` false), and each investor of the list before that is not continued: EXITED, its shares then as `shares_previous` |
| `rag_document` | source `XLSX`, `source_key` `OWNERSHIP_1_PERCENT`: one document per stored company |

## 5. The vector store and the AI analysis

After each list, every company of it that is stored in `company` gets its document written again from every stored
month (`OnePercentDigest.text`): the reading, the holders of 1% or more at the latest month end (type, local / foreign,
domicile, shares, percentage, shares held as scrip, its name the month before when it changed, bought / sold since its
first stored month), the totals (together, local, foreign, the three largest), the ownership by type, every monthly
movement (newest first, at most 40: buys, sells, new above 1%, no longer above 1%, renamed, moved) and the former
holders. Title "TICKER shareholders of more than 1% (first to latest date, N months)". A company that is not stored
has no document but is on the page.

Screening › Analysis:

- the dossier of every agent: `shareholdersAbove1Percent` (`OnePercentDigest`: the reading, totals, `byType`, holders,
  transactions, former holders); the synthesis gets the reading, totals, types, the 15 largest holders and the 15
  latest movements; the PDF report and the report page have a section "Shareholders of more than 1%";
- the research agent's tool `searchShareholdersAbove1Percent(query)` (refs **M1**, M2, ...), offered only when the
  company's document is stored; the 5% tool `searchOwnership` (refs O1) reads the 5% document only
  (`RagRepository.search` by `source_key`);
- the company picker shows how many monthly lists name the company (`onePercentMonths`, `latestOnePercentDate`).

## 6. Companies › Ownership (>=1%) (`/companies/ownership-one-percent`)

- **Tiles**: the latest list, its investors and companies, its buys / sells, new / no longer above 1%, names changed.
- **Highlights of a month**: the latest at first, any stored month from its dropdown or the previous / next buttons:
  the largest buys and sells (by percentage points, then shares), new above 1%, no longer above 1%, renamed / blocks
  moved (with the name the month before), and the investors listed for the most companies that month; "Every movement
  of <month>" lists them in the table.
- **Filters** (in the URL): tickers, part of an investor's name, transaction (all, buy, sell, buy or sell, new above
  1%, no longer above 1%, either, renamed or block moved, any movement), local / foreign, investor type (the groups),
  order (latest month first, or by the change in shares or percentage points, either way; also by clicking the Change
  or % owned header), dates (Latest month, Last 3 months, Every month). Apply and Reset keep the page where it is.
- **One ticker**: the reading; tiles (held together and the three largest, foreign / local, buys and sells, names
  changed); a bar by investor and a bar by type of investor; each investor a row followed through its matches (a
  renamed or moved holding stays on one row), at most **7 months** side by side (the latest by default, a month range
  of its own, arrows to older or newer months), a click opens its months (listed as, scripless, scrip, total,
  percentage, change); the former holders.
- **An investor's name**: every investor whose key contains it, with every company it holds (latest month, change
  since its first stored month).
- **The table**: month (and the month it is compared with), ticker, investor (type, local / foreign, renamed / block
  moved with the name before, the other names of its rows), transaction, shares before and after, change, % owned,
  scrip shares; 50 rows per page.

## 7. API

| Endpoint | Permission | Content |
|---|---|---|
| `POST /api/v1/rag/xlsx` (`file`, `exchange=IDX`, `documentType=OWNERSHIP_1_PERCENT`) | INGESTION | read at once (422 when it is not the list), queued: 202 / 200 with the `RAG_XLSX` job |
| `GET /api/v1/rag/xlsx/status` | INGESTION | the exchanges and document types (both available), `stored` per type (lists, latest date, documents) |
| `GET /api/v1/rag/xlsx/reports?documentType=OWNERSHIP_1_PERCENT` | INGESTION | the stored monthly lists with their buys, sells, new, no longer, renamed, moved |
| `GET /api/v1/ownership/one-percent` | COMPANIES | `tickers, holder, transaction, status, group, from, to, sort, limit, offset` |
| `GET /api/v1/ownership/one-percent/options` | COMPANIES | the latest list and its highlights, dates, companies, the filter and sort options |
| `GET /api/v1/ownership/one-percent/highlights?date=` | COMPANIES | a month's highlights (404 when not stored) |
| `GET /api/v1/ownership/one-percent/holders?ticker=[&from&to]` | COMPANIES | one company's rows of every month and its digest |
| `GET /api/v1/ownership/one-percent/entities?q=` | COMPANIES | the investors matching the name with their companies |
| `GET /api/v1/ownership/one-percent/reports` | COMPANIES | the stored lists |

The job (`RAG_XLSX`, the same one-worker queue as the 5% lists, `OwnershipQueue`) ends SUCCEEDED, INCOMPLETE (rows not
read, or the vector store not written) or FAILED (not the list). Its result (`OnePercentResult`): the date, the list
it was compared with and the one compared with it again, investors, companies, rows, the movements, the documents
written, warnings and notes; Ingestion › Excel Documents (RAG) shows the stored monthly lists in their own card.

```bash
curl -H "$AUTH" -F file=@data/IDX_OWNERSHIP_ONE_PERCENTAGE/peng-2026-09-00024-satu-persen.xlsx -F exchange=IDX \
     -F documentType=OWNERSHIP_1_PERCENT http://localhost:8080/api/v1/rag/xlsx
curl -H "$AUTH" "http://localhost:8080/api/v1/ownership/one-percent?tickers=AADI&transaction=CHANGED&sort=CHANGE_DESC"
```

## 8. Tests

| Test | What |
|---|---|
| `OnePercentParserTest` | the older layout (type codes, padded `L` / `A`), the newer one (classifications), an investor listed twice, a total decided by its sum and one by itself, a special scrip account without a type, a junk row; names normalized; not a workbook, no holding; the eight published lists when present (dates, rows, MAYA, SMRA, RALS, ZYRX's scrip shares) |
| `OnePercentMatcherTest` | the same name, a name spelled otherwise, a moved block, "ERIS (A)", funds of one manager and numbered names not matched, an ambiguous block, other companies |
| `OnePercentRepositoryTest` | three months of companies of its own stored out of order (February first), a buy, a sell, no longer above 1%, new above 1%, moved, renamed; reports, filters, order, the digest (an investor followed through its new name), entities, the investors in the most companies, a list replaced and one removed; the published lists stored and rolled back (AADI's T PERMADI RACHMAT, AMAG's Panin Bank) |
| `OnePercentControllerTest` | two months uploaded through the worker (February first) into the tables and the vector store, the API read back, what is refused; removed afterwards through `delete`, so a stored list after them is compared again as before |

The tests share the database with the stored lists: while their 2001 lists are stored, the earliest stored list of a later year is compared with them (so the test companies' investors are no longer above 1% there), which is why their reads end with 2001; the repository tests roll back, the controller test removes its lists with `delete`, and the later list is compared again with the one before it (none) as it was.

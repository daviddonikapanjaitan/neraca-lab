# Registration: directors' and commissioners' shares (monthly registration reports)

Every listed company files a **monthly registration report of securities holders** (Laporan Bulanan Registrasi
Pemegang Efek, LBRE) on idx.co.id. Among its tables is the shares held by each director and commissioner at the end
of the previous month and at the end of this month (Pemegang Saham Direksi dan Komisaris). Neraca Lab reads that table
into the database and shows it on **Companies > Registration**, in the manner of openinsider.com: who bought or sold,
by ticker, by title, month by month.

The reports of April - September 2026 are kept locally (`data/` is not in the repository, see `.gitignore`)
in `data/IDX_OWNERSHIP/<TICKER>/`: HRTA
(`registrasi_hrta_<bulan>.pdf`; June is a correction), INDF, GGRM and TRUK (`<Bulan>.pdf`).

## 1. Flow

```text
Ingestion > PDF Documents (RAG): Exchange IDX, Company HRTA, Document type Registration, registrasi_hrta_juni.pdf
  -> POST /api/v1/rag/pdf (documentType=REGISTRATION)
     the report is read at once: not a registration report, or another company's (Kode Emiten) -> 422, nothing stored
  -> RAG_PDF job:
     1. RegistrationParser reads the report (cover page and the directors' and commissioners' table)
     2. the PDF text -> chunks -> embeddings -> rag_document (document_type REGISTRATION) + rag_chunk   (RAG_DOCS.md)
     3. registration_report + registration_insider, linked to that rag_document
  -> Companies > Registration (GET /api/v1/registrations)
```

The job's stage reads "Stored 13 chunks of 20 pages for HRTA; registration 2026-06 (correction): 4 directors and
commissioners, 0 changes"; its result is the `PdfResult` with `documentType` and `registration` (period, letter,
correction, filing time, insiders, changes, outcome, warnings). The job detail links to Companies > Registration.

The same PDF is in the vector store like any other company PDF, so its text can also be retrieved for AI answers.
The registration rows are not read from the chunks: the chunks are plain text in which the lines of a table's cells
are mixed, so the table is read from the PDF by the position of its text (section 2).

## 2. Reading a report (`RegistrationParser`, `PdfWords`)

- **Cover** (the plain text): Kode Emiten (the company; it must be the company chosen), Nama Perusahaan, Nomor Surat,
  Perihal (must be "Laporan Bulanan Registrasi Pemegang Efek"; "(KOREKSI)" or "Mengoreksi surat ..." = a correction),
  the month ("... pada akhir bulan Juni - 2026"; also over two lines, also the English "ends in Agustus - 2026"),
  Biro Administrasi Efek, the filing time ("Tanggal dan Waktu 10-08-2026 15:14", Jakarta time) and the shares listed
  at the end of the previous month and of this one ("Jumlah saham tercatat di Bursa per akhir bulan").
- **The table** (by position: PDFBox gives every piece of text with its x / y on the page as displayed, the reports'
  pages are rotated 90 degrees):
  - the header is the line with "Nama", "Alamat" and "Jabatan" (or "Name", "Address", "Position"); the header texts
    are centred over their columns, so they are grouped into exactly **9 columns** (name, address, position, shares
    and percentage of the previous month, of this month, controller, affiliate of the controller) and each column is
    checked by its words. Any other number of columns or an unexpected header stops the reading: nothing is guessed.
  - a word belongs to the column its centre falls in; a row starts at the line holding its percentage (every cell of
    a row is top-aligned) and ends where the next one starts; a name, an address, even a number wrapped in its cell
    ("3.272.048.00" / "0") is joined again.
  - the table ends at the next table's header (the shareholders of 5 % or more), at an empty space of more than 25
    points, or at text across the table's edge; at the bottom of a page it goes on at the top of the next (its header
    printed again or not).
  - only the first table is read: the English half of the report repeats it.
  - a report without the directors' table (DMAS from June 2026 prints only its label) is still read when it has the
    shareholders' table or the summary below, with a warning ("no table of the directors' and commissioners'
    shares"); without any of the three it is refused.
- **The shareholders of 5 % or more** (Pemegang saham 5% (lima persen) atau lebih), read the same way: the header is
  the line with "Nama" and "Alamat" and no "Jabatan", **8 columns** (name, address, shares and percentage of the
  previous month and of this one, controller, affiliate of the controller). The named holders end at the first
  aggregate row: "Pemegang Saham < 5%", "Masyarakat" (the public, HRTA; the cell may break inside the word
  "MASYARAKA" / "T"), "Saham Treasuri", "Total". Their percentages may be of the shares issued, not of those listed
  (BBCA: 54.942 % of 123,275,050,000 issued, 55.50 % of the 122,042,299,500 listed): a percentage up to 1.2 times
  below the share of the listed shares is no warning. A table that cannot be read is a warning and no holders, never
  a refused report.
- **The shares by type of holder** (Jumlah Saham Scrip dan Scripless berdasarkan Tipe dan Klasifikasi Investor; until
  April 2026 "Jumlah Saham Non Warkat berdasarkan Tipe Investor"): the number on the baseline of each label
  "Pengendali", "Afiliasi dari pengendali" (also "... dan pemilik manfaat"), "Direksi dan Dewan Komisaris", "Saham
  Tresuri" (English: Controller, Affiliation from Controller & Beneficial Owner, BOD / BOC, Treasury), up to the KSEI
  classification below it. The values are as filed: CMRY reports 5,957,468,200 under the directors and commissioners
  and 0 under the controller from June 2026, DMAS its commissioner's 44,004,900 under the controller's affiliates.
- **Columns**: a word belongs to the column its centre falls in, except a word starting left of the first number
  column's header, which belongs to the column before it: numbers are right-aligned and never reach further left than
  their header, while an address may reach past the middle between the two (NCKL "Lantai 5" beside
  "50.344.432.00" / "0" would read "50.344.432.0050").
- **Runs** (`PdfWords`): PDFBox gives a line of text as runs separated by gaps; some PDFs write a whole header line as
  one string, the columns placed apart with blanks (NCKL May 2026: "Nama Alamat Jabatan Saham Bulan Saham Bulan
  Pengendali"). A run also ends where the text resumes more than 0.5 points past the end of a blank: after a blank
  between two words the next one starts where the blank ends ("Saham" / "Bulan"), across a column it jumps (1.0 point
  between the two "Saham Bulan", 35 between "Nama" and "Alamat").
- **Values**: shares "2.157.500" (or "2,157,500"), "-" = 0, empty = unknown; percentages "0,05%" / "0.05%"; "X" in
  the controller columns. The role is read from the position: Komisaris / Commissioner -> COMMISSIONER, Direksi /
  Direktur / Director -> DIRECTOR, else OTHER.
- **Checks**: every share count against its percentage and the shares listed that month, within one unit of the
  percentage's last decimal (the reports round, and some cut off: 1,310,552,200 of 4,605,262,400 = 28.4577 % is
  printed 28,45 %). A mismatch is stored with a warning and the job is `INCOMPLETE`; a number that is not a number,
  a row without a name or without any share count stops the job (`FAILED`, "Not a readable registration report").

## 3. Storing (`RegistrationRepository`)

- One report per company and month (`registration_report`, unique on company and month). A report of a month
  already stored **replaces it unless the stored one was filed later**: a correction filed after the report it
  corrects stays whichever is uploaded last (outcome `KEPT_LATER_FILING`); the same file again replaces it.
- `registration_insider`: one row per director or commissioner, with `change_shares = shares_current -
  shares_previous` (generated; NULL when either is missing). Buy = change > 0, sell = change < 0.
- `registration_holder` (`V1.0.32`): one row per shareholder of 5 % or more, the same columns without the position;
  `registration_report` gains `holders` (rows read; NULL = stored before V1.0.32: upload the report again) and the
  shares by type of holder `controller_shares`, `controller_affiliate_shares`, `insider_shares`, `treasury_shares`.
- Addresses are not stored.
- The same PDF uploaded again as a Financial Statement loses its report; deleting the company or the document
  removes the reports (`ON DELETE CASCADE`).

## 4. API (permission `COMPANIES`)

| Method | Path | Answer |
|--------|------|--------|
| `GET` | `/api/v1/registrations[?exchange=IDX&tickers=HRTA,BBRI&transaction=ALL\|BUY\|SELL\|CHANGED&role=DIRECTOR\|COMMISSIONER\|OTHER&from=2026-05&to=2026-08&limit=100&offset=0]` | one page of holdings, latest month first, then ticker and the report's order: `{ total, limit, offset, rows }`; a row has the month, company, name, position, role, shares and percentages before and after, `change`, controller / affiliate marks, correction, filing time, letter and file. 400: unknown transaction or title, a month that is not `yyyy-mm`, first month after the last, bad ticker, more than 50 tickers |
| `GET` | `/api/v1/registrations/companies` | the companies with reports (number, first and last month) and the filter options |
| `GET` | `/api/v1/registrations/reports?exchange=IDX&ticker=HRTA` | the company's reports, latest first (letter, correction, filing time, registrar, shares listed, insiders, `holders`, `ownership` { controller, controllerAffiliates, directorsAndCommissioners, treasury }, warnings, file) |
| `GET` | `/api/v1/registrations/holders?exchange=IDX&ticker=NCKL` | the company's shareholders of 5 % or more, latest month first, then the report's order (name, shares and percentages before and after, `change`, controller / affiliate marks) |

Upload: `POST /api/v1/rag/pdf` with `documentType=REGISTRATION` (RAG_DOCS.md, section 4).

```bash
curl -H "$AUTH" -F file=@data/IDX_OWNERSHIP/HRTA/registrasi_hrta_juni.pdf -F ticker=HRTA -F documentType=REGISTRATION \
     http://localhost:8080/api/v1/rag/pdf
curl -H "$AUTH" "http://localhost:8080/api/v1/registrations?tickers=HRTA&transaction=BUY"
```

## 5. Page (Companies > Registration, `/companies/registration`)

- Filters (kept in the URL): **Tickers** (comma separated; the tickers with reports are listed as buttons),
  **Transaction** (All, Buy, Sell, Buy or sell), **Title** (All titles, Director, Commissioner, Other), **Months**: a
  month range calendar (`month-range-picker.tsx`): a year of months, the first click the first month, the second
  the last (in either order; the same month twice is that month alone), previous / next year, months after the
  current one (Jakarta) not offered, presets Last 3 / 6 / 12 months (ending at the latest month with reports),
  Clear for every month; the months with stored reports are named below the calendar.
- With one ticker: **shareholders of 5 % or more** (the latest report's shares by type of holder: controller,
  affiliates of the controller, directors and commissioners, treasury, each with its share of the listed shares;
  then each holder a row, each month a column: shares, percentage as printed, change; a note names the months stored
  before the holders were read) and **holdings by month**, each director and commissioner a row, each month a column
  (the shares at the end of the month and the change during it).
- The table: month, ticker (to the company page), insider (controller / affiliate badges), title (role and position
  as filed), Buy / Sell / No change / New, shares before and after, change, % owned before -> after, the report's
  letter (Correction badge); 50 rows per page (25 / 100 / 200).
- Not shown yet: the holders of more than 1 % with their broker (to be planned).

## 5a. In the AI stock analysis (Screening > Analysis)

The analysis of a company reads its registration data in two ways ([ANALYSIS_DOCS.md](ANALYSIS_DOCS.md)):

- **The table** (no model): `insiderOwnership` in the dossier of every investor agent and in the synthesis
  input: the latest 12 months, each director's and commissioner's shares and ownership, their buys and sells,
  the totals and a one-line reading; with the latest report's shareholders of 5 % or more (`majorHolders`, also in
  the synthesis input) and its shares by type of holder (`sharesByHolderType`). The report page and the PDF show it
  as "Insider ownership".
- **The RAG chunks** of the REGISTRATION PDFs: the research agent's `searchRegistrations` tool (refs R1, ...) reads
  what the table does not hold: the controller and the holders of 5 % or more, free float, investor types,
  beneficial owner; the brief's `ownership` field sums it up. `searchFilings` reads only the financial statements.

The analysis form shows the reports stored ("4 monthly registration reports (May 2026 - Aug 2026): insider
dealings").

## 6. Results

24 reports stored (2026-10-24), April - September 2026 of HRTA, INDF, GGRM and TRUK: every job `SUCCEEDED`,
11 - 13 chunks each, no warnings; each company's director table checked against its PDF.

| Company | Insiders | Notes |
|---------|----------|-------|
| HRTA | 4 | the same shares in every month; June a correction (KOREKSI) |
| INDF | 3 | Anthoni Salim 1,329,770, Franciscus Welirang 250 (both affiliates of the controller), Taufik Wiraatmadja 50,000; names in mixed case, "0%" holdings |
| GGRM | 2, then 3 | April and May list Juni Setiawati and Susilo Wonowidjo; Slamet Budiono (20,000) is listed from June. In May the table is the last thing on page 1 and page 2 begins with the 5 % table: nothing goes on over the page |
| TRUK | 2 | Budi Gunawan, the controller, and Carolina Kusuma, an affiliate: 21,750,000 each (5 %) |

No director or commissioner of the four bought or sold in April - September 2026.

The first four HRTA reports (2026-10-24): each job 2 - 4 s, 11 - 13 chunks, no warnings. The four directors and
commissioners held the same shares from May to August 2026 (Cuncun Muliawan 2,157,500, Fendy Wijaya 17,567,800,
affiliate of the controller, Sandra Sunanto 3,176,900, Ong Deny 2,200,000): every change 0. The controller's purchase
of June (PT Terang Anugrah, 3,269,608,000 -> 3,272,048,000) is in the table of the shareholders of 5 % or more, read
since 2026-10-26.

On 2026-10-26 the 90 reports in `data/IDX_OWNERSHIP` (16 companies, April - September 2026) were read again with the
shareholders' table and the summary: every one read, none with a warning from them (the directors' counts as
before). NCKL: none of its 8 directors and commissioners holds a share ("Direksi dan Dewan Komisaris 0"); PT Harita
Jayaraya, the controller, holds 50,344,432,000 (79.79 %), Citibank Hong Kong S/A 3,958,795,700 (6.27 % in August,
-4,405,400), the controller's affiliates 551,010,000, treasury 110,733,300. Its May report, refused before (its header
written as one string), and DMAS June - September (no directors' table) are read now.

## 7. Database

`V1.0.29__schema_registration.sql`: `rag_document.document_type` of a company PDF (`FINANCIAL_STATEMENT`, the type
of every company PDF stored before, or `REGISTRATION`; `ck_rag_document_company_type`; `V1.0.28` lists the two types
too, as it runs first on every start), `registration_report`, `registration_insider`.
`V1.0.32__schema_registration_holders.sql`: `registration_holder` and the shares by type of holder on
`registration_report`. Column-level reference:
`DB_SCHEMA_DOCS.md`.

## 8. Code and tests

Backend `registration/`: `PdfWords`, `RegistrationParser`, `InsiderRole`, `RegistrationRepository`,
`controller/RegistrationController`; `rag/CompanyDocumentType`, `RagIngestionService.ingestPdf` / `registrationOf`,
`RagQueue`. Frontend: `components/registration/registration-page-client.tsx`, `lib/registration.ts`,
`app/(dashboard)/companies/registration`, `app/api/registrations`; the Document type dropdown in
`components/ingestion/rag-pdf-card.tsx`.

| Test | Covers |
|------|--------|
| `RegistrationParserTest` | the four HRTA reports as filed (cover, correction, the month over two lines, filing time, the four insiders, the affiliate mark), the English half alone, a financial statement refused; tables built word by word: a table going on over a page with a wrapped name and a wrapped number, a percentage that does not match (warning), missing columns and a bad number refused; the shareholders of 5 % or more and the shares by type of holder, a header written as one string, a report without the directors' table, a holder's percentage of the shares issued (no warning) |
| `RegistrationRepositoryTest` | months, buys, sells, titles, months filter, pages, companies and reports; the later filing of a month stays; an empty cell has no change; the holders of 5 % or more and the shares by type of holder (real database, rolled back) |
| `RegistrationControllerTest` | a report through the upload and the worker into the vector store and Companies > Registration; the filters; the same file again as a financial statement; a mismatch -> `INCOMPLETE`; another company's report, a non-report and an unknown type refused; bad filters (company ZZRG of its own, stub embeddings, removed afterwards) |
| `RagRepositoryTest` | a company PDF's type, the same file again with another type, macro types refused for a company PDF |

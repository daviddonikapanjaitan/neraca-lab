# Neraca Lab - AI Macro Analysis (v1)

Judges whether a **country's stock market** (its main index) is investable now through the eyes of four macro
investors - **Paul Tudor Jones**, **Stanley Druckenmiller**, **George Soros** and **Ray Dalio** - and gives an
**investability score from 0 to 100**. Built like the AI stock screening ([SCREENING_DOCS.md](SCREENING_DOCS.md)):
Spring AI on OpenRouter, DeepSeek V4.1 Flash for the research and the macro agents, Claude Opus 5.5 for the synthesis.
Every analysis is a background job (`ingestion_job` type `MACRO_ANALYSIS`); the report is saved in the database, shown
on **Macro › AI Analysis** and downloadable as PDF.

Indonesia (IHSG, LQ45) is available; the United States and China come later (the API answers 400 for them, as the
Macro page does).

```
[Macro page rules, no AI]  MacroOutlookService.outlook(country, index, 1Y)  (docs: MACRO_DOCS.md section 6a)
  regimes, the reading of every macro series, the four presets, the rules' investability score,
  the country's indexes (IHSG, LQ45), commodities and currency pairs with what each means for the country,
  the rupiah against its Asian peers
      |
[Research agent]  ReAct + tool calling over the STORED history and the country's macro documents (no web)
  history(code, months), correlation(a, b, months)          -> refs T1, T2, ...
  searchDocuments(query): RAG over the macro PDFs published  -> refs D1, D2, ...
    by the as-of date (offered only when there are some)
  -> brief with references
      |
[Macro agents]  Tudor Jones, Druckenmiller, Soros, Dalio: one independent call each (shared, cacheable dossier)
  -> Reflection: the validator checks every answer, a critic reviews the flagged ones
  -> Reflexion: lessons of earlier analyses in the prompts; new issues stored as lessons
      |
[Scores]  agent = 0.6 x its rules preset + 0.4 x its AI judgement
          investability = 0.8 x the agents' mean + 0.2 x the rules score
      |
[Synthesis]  Opus 5.5: executive summary, conviction, bull / bear case, risks, monitor, sector tilts, +-5 points
      |
[Save report to DB] -> [Macro page AI panel, Macro › AI Analysis] -> [PDF]
```

## 1. Using it

- **Macro page** (`/macro`, sidebar › Macro › Outlook): the right column holds the **AI panel** - the latest AI analysis
  of the chosen index (investability, verdict, action, each agent's stance and the thesis, beside the rules' score), a
  button **Run AI analysis** and a link to the full report. It polls every 3 s while an analysis is queued or running.
- **Macro › AI Analysis** (`/macro/analysis`): the form (country, index, agents; the models, the budget and what the
  database holds: macro series with data, commodities, currency pairs) and the list of analyses (most recent first,
  paged; polled while one is active).
- **Report** (`/macro/analysis/{id}`): status and progress while it runs, then
  - the tiles: investability, action, the rules score, cost, duration;
  - the executive summary, conviction and thesis, bull case, bear case, key risks, what to monitor, **sector tilts**,
    data gaps, the synthesis adjustment and its reason;
  - one card per agent: final score and stance, the prior (its preset) and the AI score, thesis, supports, risks,
    buy when / sell when, the reflection review;
  - the research brief (regime, liquidity, inflation and rates, external, growth, risks, catalysts, evidence with its
    refs), the tool results read and the ReAct steps;
  - the rules the agents read (the Macro page's score, regimes, indexes, presets) and the commodities and currencies;
  - the model calls with tokens and cost, and the PDF download.
- **Ingestion › Jobs** has a **Macro analyses** tab; a job's detail sheet links to its report.

Permission **COMPANIES** (as the Macro page).

## 2. API (permission COMPANIES)

| Endpoint | Does |
|---|---|
| `GET /api/v1/macro/analyses/options` | The countries and their indexes, the agents (code, label, focus), the budget, the research / agent / synthesis models and `data` (`macroSeries` of `macroSeriesTotal`, `commodities`, `currencyPairs` with stored prices, `macroDocuments`: macro documents stored per country code). |
| `POST /api/v1/macro/analyses` `{"country":"ID","index":"IHSG","agents":[...]}` | Queues an analysis: **202** with the new one, **200** with the index's analysis already queued or running (not queued twice). `country` defaults to ID, `index` to IHSG, `agents` to all four (`TUDOR_JONES`, `DRUCKENMILLER`, `SOROS`, `DALIO`; a preset id such as `ptj`, a label or a surname is accepted). 400: a country not available yet, an unknown country, index or agent, an empty agent list. |
| `GET /api/v1/macro/analyses?country&index&limit=10&offset=0` | One page, most recent first (`total`, `limit` 1 - 100, `offset`, `analyses`). |
| `GET /api/v1/macro/analyses/{id}` | The report: `run` (status, stage, scores, verdict, action, conviction, cost, tokens, times), `synthesisAdjustment`, `context`, `research`, `synthesis`, `notes`, `agents`, `usage`. 404: unknown. |
| `GET /api/v1/macro/analyses/{id}/pdf` | The report as PDF (`macro-analysis-ID-IHSG-<date>.pdf`); 409 while it runs. |

Statuses are those of the job: QUEUED, RUNNING, SUCCEEDED, **INCOMPLETE** (a step was degraded: the research, an agent
without an answer, the budget reached, the synthesis written without the model; the message says which) or FAILED.

## 3. Data the agents read

All from the database, as of today (Jakarta) and point in time (a macro observation counts only once released):

- **The Macro page's rules** (`MacroOutlook`): the liquidity and risk regimes with their votes and their state three
  months before, the reading of every macro series (BI rate, CPI, SBN 10Y, USD/IDR, reserves, M2, foreign flows,
  Fed funds, US 10Y, the curve, US net liquidity, the high-yield spread, DXY, VIX, US CPI, China PMI, ...), the four
  presets (ptj, druckenmiller, soros with its reflexive `loop`, dalio with its `quadrant`) and the rules' conclusion.
  The chart points are left out.
- **Indexes**: the chosen one and the country's others (IHSG, LQ45): close, 1M / 3M / 1Y, against the 200-day average.
- **Commodities and currency pairs** (`MarketBoard`, Ingestion › Commodities and Forex): every active instrument with
  its 1M / 3M / 1Y change and **what it means for Indonesia** - an export (coal, nickel, palm oil, LNG, rubber, ...)
  rising 5 % or more in 3 months is supportive, an import (crude oil, wheat, soybean meal, ...) rising 5 % or more a
  headwind, USD/IDR up 2 % or more a headwind (down 1 % supportive); a stale price (10 days daily, 75 days monthly) is
  neutral. The **terms of trade** sum the votes: FAVOURABLE at +2, UNFAVOURABLE at -2. Since 2026-10-23 the nine
  currency pairs: USD/IDR, USD/JPY, the rupiah's Asian peers (USD/CNY, USD/MYR, USD/PHP, USD/INR, USD/KRW), EUR/USD
  (the dollar: a rise 2 % or more supportive) and AUD/JPY (risk appetite: a fall 5 % or more a headwind); a weaker yuan
  (USD/CNY up 2 % or more) is a headwind.
- **The rupiah against its Asian peers** (`markets.regionalCurrencies`, `MarketBoard.regional`): USD/IDR's 3-month
  change beside the median of the five peers - **weaker than the region** by 1.5 points or more (a pressure of
  Indonesia's own, a headwind), **firmer** (supportive) or **in line** (the dollar moves all of Asia); unknown with
  fewer than 3 current peers ([MACRO_DOCS.md](MACRO_DOCS.md) section 6a). The synthesis gets it beside the terms of
  trade, the report and its PDF show its summary. The prompts tell the research agent to read the rupiah against its
  peers (weaker than the region: the country's own pressure; weaker with it: the dollar's), EUR/USD as the dollar
  and AUD/JPY as risk appetite, and the agents that `regionalCurrencies` and the pair codes are signals they may cite.

The research agent's tools (`MacroTools`) read the stored histories:

| Tool | Returns |
|---|---|
| `history(code, months)` | Month-end values (a flow such as `ID_FOREIGN_NET_BUY`: the sum of the month) up to `history-months`, the latest value, the window's range and change (percent for prices, points for rates and series with values of zero or less), and the **stored high and low** of everything stored with their dates and since when (not for flows). |
| `correlation(a, b, months)` | Pearson correlation of the monthly changes of two series (e.g. IHSG with USDIDR, COAL, AUDJPY or US_10Y, or USDIDR with USDCNY), with the months used. A code is accepted as the model writes it (`usd/cny` is `USDCNY`). |

Each result has a ref (T1, T2, ...). A repeated call or one past `max-tool-calls` is refused. The prompts say a value is
a **record** only when it is the stored high or low - a window's or a 52-week high is not.

## 3a. Macro documents (RAG)

The country's **macro documents** - PDFs of Bank Indonesia, the Ministry of Finance, the IMF, the World Bank, ...
uploaded on **Ingestion › PDF Documents (RAG)**, right-hand card (`RAG_DOCS.md` section 2a; what to collect:
`instruction/pdf_rag_macro.md`) - give the research agent what the series cannot: the reasons behind a decision,
forward guidance, forecasts, stated risks and policy plans.

| Tool | Returns |
|---|---|
| `searchDocuments(query)` | The `document-results` (4) chunks of the country's macro documents closest to the query (cosine, pgvector), each with a ref (D1, D2, ...), the issuer, title, type, publication date, pages and an excerpt of at most `excerpt-chars` (700). |

- **Point in time**: only the documents published by the as-of date (on or before it, Jakarta time) are listed and
  searched (`MacroDocumentSearch`). A document published later is not read; the report notes how many were left out.
- **Offered only when there are documents**: without any (none stored for the country, or none published by the as-of
  date) the tool is not offered and the report notes that the research read the stored series only.
- The research agent's task lists the documents it can search: the 15 latest, with date, issuer, title and type.
- Its own limit, `max-document-searches` (4), apart from the history and correlation calls. A repeated query is
  refused. The same chunk found again keeps its ref.
- Each query's embedding is metered (stage `RETRIEVAL`, cost estimated at `embedding-price-per-million`).
- The prompts say a figure in a document is as of its date, the stored series win when they disagree, and a document
  of type research is an opinion. The agents read the D refs in the research brief as evidence; evidence citing a ref
  that no search returned is dropped, as for the T refs.
- The report (and its PDF) lists the tool results with their D refs and the macro documents the research could search.

## 4. The agentic patterns

| Pattern | Where |
|---|---|
| **ReAct + tool calling** | `MacroResearchAgent`: "Thought:" lines, tool calls (several per turn), observations, up to `research-iterations` turns, then a JSON brief. |
| **Grounding** | Evidence citing a ref that no tool returned is dropped (noted in the report). |
| **Independent agents, cacheable prefix** | `MacroPanel`: the four agents share the system prompt and the macro dossier; only the last message (persona, rules prior, lessons) differs. |
| **Reflection** | `MacroValidator` checks every answer; a critic call reviews the flagged ones (status REVISED). |
| **Reflexion** | `MacroLessons` (`macro_analysis_lesson`): an issue kind becomes a lesson added to that agent's later prompts (at most 4, the most frequent first). |
| **Deterministic fallback** | Without the synthesis model (budget, error) the summary is built from the agents' rows; an agent without an answer keeps its rules prior. |

Each agent's **persona** follows its investor: Tudor Jones (defence first; nothing below the 200-day average; the
regime: VIX, credit, the curve, the dollar), Druckenmiller (liquidity: central banks, US net liquidity, the dollar,
foreign money, 12 - 24 months ahead), Soros (reflexivity: the loop of foreign money, the rupiah and Bank Indonesia;
boom and bust), Dalio (the growth / inflation quadrant, real rates, credit, terms of trade; never buys stagflation).

**Validator issues**

| Code | When |
|---|---|
| DIVERGENCE | The score is more than 35 points from the agent's rules prior. |
| STANCE | The stance does not match the score band (BUY 65 - 100, ACCUMULATE 50 - 79, WAIT 35 - 64, AVOID 0 - 49; 5 points of tolerance). |
| RULE | The answer breaks the investor's own rule: Tudor Jones buying below the 200-day average; Druckenmiller 70+ while liquidity is TIGHTENING; Soros 70+ while the loop is VICIOUS; Dalio buying in STAGFLATION. |
| UNKNOWN_SIGNAL | It cites signals that are not in the dossier. |
| EMPTY | No score or no thesis. |

## 5. Scores

- **Prior** of an agent = its preset on the Macro page on 0 - 100: `50 + 50 x preset score / its number of signals`
  (none when the preset has too few signals).
- **Agent score** = `prior-weight x prior + (1 - prior-weight) x AI score` (0.6 / 0.4); the AI score alone without a
  prior, the prior alone without an answer (PRIOR_ONLY).
- **Investability** = `(1 - rules-weight) x the agents' mean + rules-weight x the rules score` (0.8 / 0.2), plus the
  synthesis adjustment (at most +-5, usually 0, with a reason), clamped to 0 - 100.
- **Verdict**: 60+ INVESTABLE, 40 - 59 SELECTIVE, below 40 NOT_INVESTABLE (as the rules).
- **Action** (the rules' table): 60+ BUY above the 200-day average, ACCUMULATE below it; 40 - 59 ACCUMULATE above it
  while liquidity is not tightening, else WAIT; below 40 SELL below it, WAIT above it.
- **Conviction** (synthesis; the fallback: HIGH at 20+ points from 50, MEDIUM at 10+, else LOW).

## 6. Cost control

Every call is metered (`UsageMeter`, `llm_usage.macro_analysis_id`). An analysis stays within `budget-usd`: before each
optional call the expected cost is checked, keeping `synthesis-reserve-usd` free for Opus; a skipped agent keeps its
rules prior and the analysis ends INCOMPLETE. Typical run: 9 model calls (8 DeepSeek - research turns, the four agents,
a critic per flagged answer - and 1 Opus synthesis), about 70,000 prompt tokens, **$0.05**, 30 - 50 s.

## 7. Settings (`application.yaml`, `neracalab.macro-analysis`)

| Key | Default | Meaning |
|---|---|---|
| `budget-usd` | 0.20 (`MACRO_ANALYSIS_BUDGET_USD`) | Hard cost cap of one analysis. |
| `synthesis-reserve-usd` | 0.10 | Kept free for the synthesis while the others run. |
| `research-iterations` | 4 | Model turns of the research agent. |
| `max-tool-calls` | 8 | Tool calls of the research agent. |
| `history-months` | 36 | Most months one `history` call returns. |
| `max-document-searches` | 4 | Searches in the country's macro documents (only when some are stored). |
| `document-results` | 4 | Chunks returned per document search. |
| `excerpt-chars` | 700 | Longest excerpt of a chunk returned to the research agent. |
| `embedding-price-per-million` | 0.02 | USD per million tokens of the embedding model (estimated cost of a search). |
| `agent-max-tokens` | 1000 | Output limit of an agent answer or a review. |
| `prior-weight` | 0.6 | Weight of the rules preset in an agent's score. |
| `rules-weight` | 0.2 | Weight of the rules score in the investability. |

The models are those of the screening (`neracalab.screening.llm`: `research-model`, `agent-model`,
`synthesis-model`, `synthesis-effort: low`).

## 8. Tables (`V1.0.27__schema_macro_analysis.sql`)

| Table | Holds |
|---|---|
| `macro_analysis_run` | One analysis (`analysis_id` = the job id): country, index, agents, budget, as-of date, rules / overall score, adjustment, verdict, action, conviction, `context` (what the agents saw), `research`, `synthesis`, `notes`, cost and tokens. |
| `macro_analysis_agent_score` | Each agent's prior, AI and final score, stance, thesis, supports, risks, buy / sell when, reflection, status (ASSESSED, REVISED, PRIOR_ONLY, NO_SCORE). |
| `macro_analysis_lesson` | Reflexion memory of the macro agents (`active = false` drops a lesson). |
| `llm_usage.macro_analysis_id` | The model calls of an analysis. |

`MACRO_ANALYSIS` is listed in `ck_ingestion_job_type` by V1.0.10 and V1.0.14. Details: [DB_SCHEMA_DOCS.md](DB_SCHEMA_DOCS.md) section 4.14.

## 9. Tests

| Test | Covers |
|---|---|
| `MacroAnalysisControllerTest` | The options; an analysis of Indonesia end to end on stored macro data, commodities and indexes with a scripted model (research with tools, four agents assessed or revised, the synthesis, scores, verdict, action, usage rows, the PDF), with two macro documents stored: the one published by the as-of date listed to the research agent and found by its document search (D1, kept as evidence and in the dossier), the one published after it not read and noted; an active analysis of the same index returned, not queued again; invalid requests (400) and agent names by code, preset or surname. |
| `MacroAnalysisRulesTest` | `history` reads only what was published by the as-of date (stored high and low); a flow summed per month with changes in points; correlation and the tool limits; the document search (refs D1, D2 kept for the same chunk, excerpts, a repeated query refused, its own limit) offered only when the country has documents; the report's notes on documents not read; the validator holding each investor to his own rules; answers normalized; the prior from the preset; the blend and the action table; the synthesis bounded to +-5. |
| `MarketBoardTest` | Exports, imports and the rupiah read for Indonesia (tones, terms of trade); stale data neutral, an empty board says so; the yuan, the dollar (EUR/USD) and risk appetite (AUD/JPY) read, a peer neutral on its own and none voting in the terms of trade; the rupiah weaker than, in line with and firmer than its Asian peers, unknown with fewer than 3 peers or without USD/IDR. |
| `AccessControlTest` | The macro analyses need COMPANIES. |

## 10. Results (2026-10-21, live)

| Index | Rules | Investability | Verdict | Action | Conviction | Cost | Calls | Time |
|---|---|---|---|---|---|---|---|---|
| IHSG | 22 | 29.2 | NOT_INVESTABLE | SELL | MEDIUM | $0.051 | 9 | 48 s |
| LQ45 | 22 | 29.2 | NOT_INVESTABLE | SELL | HIGH | $0.051 | 9 | 27 s |
| IHSG | 22 | 28.8 | NOT_INVESTABLE | SELL | HIGH | $0.054 | 9 | 49 s |
| IHSG (2026-10-23, with the nine currency pairs) | 31 | 29.9 | NOT_INVESTABLE | SELL | MEDIUM | $0.058 | 13 | 51 s |

The last run: IHSG 23.7 % below its falling 200-day average, liquidity tightening (-5 of 7), BI hiking to 5.75 %,
foreign equity outflows, the rupiah near its stored record low; coal and LNG supportive. Tudor Jones 31.0 and
Druckenmiller 12.6 AVOID, Soros 46.0 WAIT (foreign SBN inflows, a narrower country premium), Dalio 32.5 AVOID.

The run of 2026-10-23 had the nine currency pairs and the rupiah 1.8 points weaker than its Asian peers' median (USD/IDR
+0.5 % in 3 months against -1.3 %). Three agents named it among their risks (Tudor Jones: "Rupiah 1.8pts weaker than
Asian peers; foreign outflows persist"; Soros, Dalio) and the synthesis's thesis a "regionally weak rupiah". Tudor Jones
32.2, Druckenmiller 14.2 and Dalio 26.8 AVOID, Soros 45.2 WAIT.

## 11. Limits

- Indonesia only; the United States and China need their own series, indexes and presets.
- The agents read macro data that lag the market and are not revised by vintage (MACRO_DOCS section 7).
- No news or web search: the research agent reads only the stored history and the macro documents uploaded for the
  country; a document is only as current as the last upload (`instruction/pdf_rag_macro.md` has the publication
  calendar).
- An analysis is research, not investment advice.

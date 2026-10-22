# Neraca Lab - AI Macro Analysis (v1)

Judges whether a **country's stock market** (its main index) is investable now through the eyes of four macro
investors - **Paul Tudor Jones**, **Stanley Druckenmiller**, **George Soros** and **Ray Dalio** - and gives an
**investability score from 0 to 100**. Built like the AI stock screening ([SCREENING_DOCS.md](SCREENING_DOCS.md)):
Spring AI on OpenRouter, DeepSeek V4 Flash for the research and the macro agents, Claude Opus 5.5 for the synthesis.
Every analysis is a background job (`ingestion_job` type `MACRO_ANALYSIS`); the report is saved in the database, shown
on **Macro › AI Analysis** and downloadable as PDF.

Indonesia (IHSG, LQ45) is available; the United States and China come later (the API answers 400 for them, as the
Macro page does).

```
[Macro page rules, no AI]  MacroOutlookService.outlook(country, index, 1Y)  (docs: MACRO_DOCS.md section 6a)
  regimes, the reading of every macro series, the four presets, the rules' investability score,
  the country's indexes (IHSG, LQ45), commodities and currency pairs with what each means for the country
      |
[Research agent]  ReAct + tool calling over the STORED history (no web)
  history(code, months), correlation(a, b, months)  -> brief with references T1, T2, ...
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
| `GET /api/v1/macro/analyses/options` | The countries and their indexes, the agents (code, label, focus), the budget, the research / agent / synthesis models and `data` (`macroSeries` of `macroSeriesTotal`, `commodities`, `currencyPairs` with stored prices). |
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
  neutral. The **terms of trade** sum the votes: FAVOURABLE at +2, UNFAVOURABLE at -2.

The research agent's tools (`MacroTools`) read the stored histories:

| Tool | Returns |
|---|---|
| `history(code, months)` | Month-end values (a flow such as `ID_FOREIGN_NET_BUY`: the sum of the month) up to `history-months`, the latest value, the window's range and change (percent for prices, points for rates and series with values of zero or less), and the **stored high and low** of everything stored with their dates and since when (not for flows). |
| `correlation(a, b, months)` | Pearson correlation of the monthly changes of two series (e.g. IHSG with USDIDR, COAL or US_10Y), with the months used. |

Each result has a ref (T1, T2, ...). A repeated call or one past `max-tool-calls` is refused. The prompts say a value is
a **record** only when it is the stored high or low - a window's or a 52-week high is not.

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
| `MacroAnalysisControllerTest` | The options; an analysis of Indonesia end to end on stored macro data, commodities and indexes with a scripted model (research with tools, four agents assessed or revised, the synthesis, scores, verdict, action, usage rows, the PDF); an active analysis of the same index returned, not queued again; invalid requests (400) and agent names by code, preset or surname. |
| `MacroAnalysisRulesTest` | `history` reads only what was published by the as-of date (stored high and low); a flow summed per month with changes in points; correlation and the tool limits; the validator holding each investor to his own rules; answers normalized; the prior from the preset; the blend and the action table; the synthesis bounded to +-5. |
| `MarketBoardTest` | Exports, imports and the rupiah read for Indonesia (tones, terms of trade); stale data neutral, an empty board says so. |
| `AccessControlTest` | The macro analyses need COMPANIES. |

## 10. Results (2026-10-21, live)

| Index | Rules | Investability | Verdict | Action | Conviction | Cost | Calls | Time |
|---|---|---|---|---|---|---|---|---|
| IHSG | 22 | 29.2 | NOT_INVESTABLE | SELL | MEDIUM | $0.051 | 9 | 48 s |
| LQ45 | 22 | 29.2 | NOT_INVESTABLE | SELL | HIGH | $0.051 | 9 | 27 s |
| IHSG | 22 | 28.8 | NOT_INVESTABLE | SELL | HIGH | $0.054 | 9 | 49 s |

The last run: IHSG 23.7 % below its falling 200-day average, liquidity tightening (-5 of 7), BI hiking to 5.75 %,
foreign equity outflows, the rupiah near its stored record low; coal and LNG supportive. Tudor Jones 31.0 and
Druckenmiller 12.6 AVOID, Soros 46.0 WAIT (foreign SBN inflows, a narrower country premium), Dalio 32.5 AVOID.

## 11. Limits

- Indonesia only; the United States and China need their own series, indexes and presets.
- The agents read macro data that lag the market and are not revised by vintage (MACRO_DOCS section 7).
- No news or web search: the research agent reads only the stored history.
- An analysis is research, not investment advice.

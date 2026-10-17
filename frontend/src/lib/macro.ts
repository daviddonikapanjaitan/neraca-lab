// The Macro page: GET /api/v1/macro/outlook (MacroOutlook in the backend; fixed rules, no AI model).

export const MACRO_HREF = "/macro"

export type Tone = "POSITIVE" | "NEGATIVE" | "NEUTRAL"
export type MacroStance = "BUY" | "ACCUMULATE" | "WAIT" | "AVOID"
export type MacroAction = "BUY" | "ACCUMULATE" | "WAIT" | "SELL"
export type Verdict = "INVESTABLE" | "SELECTIVE" | "NOT_INVESTABLE"

export const MACRO_RANGES = ["6M", "1Y", "3Y", "5Y", "10Y", "MAX"] as const
export type MacroRange = (typeof MACRO_RANGES)[number]

export interface MacroCountry {
  code: string
  name: string
  available: boolean
  indexes: { code: string; name: string; rows: number; lastDate: string | null }[]
  note: string | null
}

export interface MacroIndex {
  code: string
  name: string
  date: string | null
  close: number | null
  change1mPct: number | null
  change3mPct: number | null
  ytdPct: number | null
  change1yPct: number | null
  high52w: number | null
  low52w: number | null
  fromHighPct: number | null
  sma200: number | null
  vsSma200Pct: number | null
  aboveSma200: boolean | null
  sma200Slope: "RISING" | "FALLING" | "FLAT" | null
  /** [epoch seconds, close] */
  points: [number, number][]
}

export interface MacroPanel {
  code: string
  group: "Indonesia" | "Global"
  title: string
  unit: string
  digits: number
  readBy: string
  /** where the data come from (USD/IDR: the Forex price ingestion, else the ECB rates) */
  source: string | null
  points: [number, number][]
  lastDate: string | null
  lastValue: number | null
  tone: Tone
  summary: string
}

export interface MacroPreset {
  preset: "ptj" | "druckenmiller" | "soros" | "dalio"
  name: string
  style: string
  stance: MacroStance
  stanceLabel: string
  score: number | null
  maxScore: number
  thesis: string
  points: { tone: Tone; text: string }[]
  buyWhen: string[]
  sellWhen: string[]
}

export interface Condition {
  text: string
  met: boolean
}

export interface ScorePart {
  key: string
  name: string
  weight: number
  /** -1 .. +1 */
  value: number | null
  /** points added to (or taken from) 50 */
  points: number | null
  text: string
}

export interface Conclusion {
  score: number | null
  verdict: Verdict | null
  verdictLabel: string
  action: MacroAction
  actionLabel: string
  reason: string
  summary: string
  components: ScorePart[]
  buyWhen: Condition[]
  accumulateWhen: Condition[]
  sellWhen: Condition[]
  watch: string[]
}

/** A macro regime: its state, score and the signals that voted */
export interface MacroRegime {
  state: string | null
  score: number | null
  signals: number
  components: { signal: string; vote: number; text: string }[]
  note?: string
}

export type MacroRegimeThen = MacroRegime & { stateThen: string | null; scoreThen: number | null }

export interface MacroOutlook {
  country: { code: string; name: string }
  range: MacroRange
  countries: MacroCountry[]
  asOfDate: string
  index: MacroIndex
  regimes: { liquidity: MacroRegimeThen; risk: MacroRegimeThen }
  panels: MacroPanel[]
  presets: MacroPreset[]
  conclusion: Conclusion
  notes: string[]
  rulesVersion: string
  disclaimer: string
}

/** The route handler of the outlook (the browser's refetch on a change of country, index or range). */
export function macroOutlookUrl(country: string, index: string, range: string): string {
  const query = new URLSearchParams({ country, index, range })
  return `/api/macro/outlook?${query}`
}

export function isMacroRange(value: string | undefined): value is MacroRange {
  return !!value && (MACRO_RANGES as readonly string[]).includes(value)
}

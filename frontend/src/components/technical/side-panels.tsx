"use client"

import { Bar, BarChart, CartesianGrid, Line, LineChart, XAxis, YAxis } from "recharts"
import { DownloadIcon } from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { ChartContainer, ChartTooltip, ChartTooltipContent, type ChartConfig } from "@/components/ui/chart"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { EMPTY, formatCompact, formatDate, formatNumber, formatPercent, formatPrice } from "@/lib/format"
import { decimalsOf, downloadCsv, flatten, formatLevel, type Levels, type MacroRegime, type Market, type Snapshot } from "@/lib/technical"
import { cn } from "@/lib/utils"

function signed(value: number | null | undefined, digits = 2): string {
  if (value === null || value === undefined) return EMPTY
  return `${value > 0 ? "+" : ""}${formatNumber(value, digits)}%`
}

// ------------------------------------------------------------------ key levels

export function KeyLevelsCard({ levels }: { levels: Levels }) {
  // an IDX listing: valid IDX prices; a currency pair or commodity: its own tick size (USD/JPY 0.001)
  const idx = levels.exchange === "IDX"
  const decimals = decimalsOf(levels.tickSize)
  const price = (v: number) => (idx ? formatPrice(v) : formatLevel(v, decimals))
  const exportCsv = () =>
    downloadCsv(
      `${levels.ticker}-key-levels-${levels.lastDate}.csv`,
      ["level", "price", "zone_low", "zone_high", "distance_pct", "detail"],
      levels.keyLevels.map((l) => [l.label, l.price, l.low, l.high, l.distancePct, l.detail])
    )
  return (
    <Card>
      <CardHeader>
        <CardTitle>Key levels</CardTitle>
        <CardDescription>
          Close {price(levels.close)} on {formatDate(levels.lastDate)} ·{" "}
          {idx ? "all levels are valid IDX prices" : "levels rounded to the tick"} (tick{" "}
          {formatNumber(levels.tickSize, decimals)})
        </CardDescription>
        <CardAction>
          <Button variant="outline" size="sm" onClick={exportCsv}>
            <DownloadIcon /> CSV
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="px-0">
        <div className="max-h-[420px] overflow-y-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="pl-6">Level</TableHead>
                <TableHead className="text-right">Price</TableHead>
                <TableHead className="pr-6 text-right">From close</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {levels.keyLevels.map((l, i) => (
                <TableRow key={`${l.label}-${i}`}>
                  <TableCell className="pl-6 whitespace-normal">
                    <div className="font-medium">{l.label}</div>
                    <div className="text-xs text-muted-foreground">
                      {l.low !== null && l.high !== null ? `${price(l.low)} – ${price(l.high)} · ` : ""}
                      {l.detail}
                    </div>
                  </TableCell>
                  <TableCell className="text-right font-mono tabular-nums">{price(l.price)}</TableCell>
                  <TableCell
                    className={cn(
                      "pr-6 text-right font-mono tabular-nums",
                      (l.distancePct ?? 0) > 0 ? "text-emerald-600 dark:text-emerald-400" : (l.distancePct ?? 0) < 0 ? "text-rose-600 dark:text-rose-400" : ""
                    )}
                  >
                    {signed(l.distancePct)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </CardContent>
    </Card>
  )
}

// ------------------------------------------------------------------ volume profile

const profileConfig = {
  volume: { label: "Volume", theme: { light: "oklch(0.556 0 0)", dark: "oklch(0.708 0 0)" } },
} satisfies ChartConfig

export function VolumeProfileCard({ levels }: { levels: Levels }) {
  const vp = levels.volumeProfile
  const top = new Set(vp.topNodes.map((n) => n.price))
  // highest price on top, like the price axis
  const data = [...vp.buckets].reverse().map((b) => ({
    price: b.price,
    range: `${formatPrice(b.low)} – ${formatPrice(b.high)}`,
    volume: b.volume,
    top: top.has(b.price),
  }))
  return (
    <Card>
      <CardHeader>
        <CardTitle>Volume by price</CardTitle>
        <CardDescription>
          Last {vp.days} trading days{vp.from ? ` (${formatDate(vp.from)} – ${formatDate(vp.to)})` : ""} · top nodes{" "}
          {vp.topNodes.map((n) => formatPrice(n.price)).join(", ")}
        </CardDescription>
      </CardHeader>
      <CardContent>
        {data.length === 0 ? (
          <EmptyState variant="chart" title="No volume" description="No bars in the profile window." />
        ) : (
          <ChartContainer config={profileConfig} className="aspect-auto h-[360px] w-full">
            <BarChart data={data} layout="vertical" margin={{ left: 4, right: 8 }}>
              <CartesianGrid horizontal={false} strokeDasharray="3 3" />
              <XAxis type="number" tickLine={false} axisLine={false} tick={{ fontSize: 10 }} tickFormatter={(v: number) => formatCompact(v)} />
              <YAxis type="category" dataKey="price" tickLine={false} axisLine={false} width={56} interval={1}
                tick={{ fontSize: 10 }} tickFormatter={(v: number) => formatPrice(v)} />
              <ChartTooltip
                content={<ChartTooltipContent hideIndicator labelFormatter={(_, payload) => payload?.[0]?.payload?.range ?? ""}
                  formatter={(value) => <span className="font-mono tabular-nums">{formatCompact(Number(value))} shares</span>} />}
              />
              <Bar dataKey="volume" fill="var(--color-volume)" radius={[0, 3, 3, 0]}
                shape={(props: { x?: number; y?: number; width?: number; height?: number; payload?: { top: boolean } }) => (
                  <rect x={props.x} y={props.y} width={Math.max(0, props.width ?? 0)} height={Math.max(0, props.height ?? 0)} rx={2}
                    fill={props.payload?.top ? "oklch(0.623 0.214 259.815)" : "var(--color-volume)"} />
                )}
              />
            </BarChart>
          </ChartContainer>
        )}
      </CardContent>
    </Card>
  )
}

// ------------------------------------------------------------------ snapshot

type Group = Record<string, unknown>

function n(group: Group | undefined, key: string): number | null {
  const v = group?.[key]
  return typeof v === "number" ? v : null
}

function Row({ label, value, hint }: { label: string; value: React.ReactNode; hint?: string }) {
  return (
    <div className="flex items-baseline justify-between gap-3 py-1 text-sm">
      <span className="text-muted-foreground" title={hint}>{label}</span>
      <span className="text-right font-mono tabular-nums">{value}</span>
    </div>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="space-y-0.5">
      <p className="pt-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">{title}</p>
      <div className="divide-y">{children}</div>
    </div>
  )
}

export function SnapshotCard({ snapshot }: { snapshot: Snapshot }) {
  const trend = snapshot.trend as Group
  const cross = trend.sma50Vs200Cross as Group | undefined
  const momentum = snapshot.momentum as Group
  const macd = momentum.macd as Group | undefined
  const stoch = momentum.stochastic as Group | undefined
  const roc = momentum.rocPct as Group | undefined
  const vol = snapshot.volatility as Group
  const bb = vol.bollinger20_2 as Group | undefined
  const volume = snapshot.volume as Group
  const spikes = volume.volumeSpikes60 as Group | undefined
  const rs = snapshot.relativeStrength as Group
  const sector = rs.sector as Group | undefined
  const regime = snapshot.marketRegime as Group
  const macro = snapshot.macro as Group
  const risk = snapshot.risk as Group
  const usd = macro.usdIdr as Group | undefined
  const bi = macro.biRate as Group | undefined
  const sbn = macro.sbn10y as Group | undefined
  const regimes = (macro.regimes ?? {}) as Record<string, MacroRegime | undefined>
  const exportCsv = () =>
    downloadCsv(`${snapshot.ticker}-snapshot-${snapshot.asOfDate}.csv`, ["field", "value"], flatten(snapshot).map(([k, v]) => [k, v]))
  const pct = (v: number | null) => (v === null ? EMPTY : formatPercent(v / 100))
  return (
    <Card>
      <CardHeader>
        <CardTitle>Snapshot</CardTitle>
        <CardDescription>
          Indicator values on {formatDate(snapshot.asOfDate)} · data, not signals · schema v{snapshot.schemaVersion}
        </CardDescription>
        <CardAction>
          <Button variant="outline" size="sm" onClick={exportCsv}>
            <DownloadIcon /> CSV
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="space-y-1">
        {snapshot.history.limited && (
          <Badge variant="destructive">Limited history: {snapshot.history.tradingDays} trading days</Badge>
        )}
        <Section title="Trend">
          <Row label="Close vs SMA 50 / 200" value={`${signed(n(trend.priceVsMaPct as Group, "sma50"), 1)} / ${signed(n(trend.priceVsMaPct as Group, "sma200"), 1)}`} />
          <Row label="SMA 50 vs 200" value={cross?.state ? `${cross.state === "GOLDEN" ? "Above" : "Below"}${cross.barsSinceCross !== null && cross.barsSinceCross !== undefined ? ` · crossed ${cross.barsSinceCross} bars ago` : ""}` : EMPTY} />
          <Row label="SMA 200 slope (20 days)" value={signed(n(trend, "sma200Slope20dPct"))} />
          <Row label="ADX / +DI / −DI" value={`${formatNumber(n(trend, "adx14"), 1)} / ${formatNumber(n(trend, "plusDi14"), 1)} / ${formatNumber(n(trend, "minusDi14"), 1)}`} />
        </Section>
        <Section title="Momentum">
          <Row label="RSI 14" value={formatNumber(n(momentum, "rsi14"), 1)} />
          <Row label="MACD / signal / hist" value={`${formatNumber(n(macd, "line"), 2)} / ${formatNumber(n(macd, "signal"), 2)} / ${formatNumber(n(macd, "histogram"), 2)}`} />
          <Row label="Stochastic %K / %D" value={`${formatNumber(n(stoch, "k"), 1)} / ${formatNumber(n(stoch, "d"), 1)}`} />
          <Row label="Change 1M / 3M / 6M / 12M" value={`${signed(n(roc, "d21"), 1)} ${signed(n(roc, "d63"), 1)} ${signed(n(roc, "d126"), 1)} ${signed(n(roc, "d252"), 1)}`} />
          <Row label="12-1 month momentum" value={signed(n(momentum, "momentum12_1Pct"), 1)} />
        </Section>
        <Section title="Volatility">
          <Row label="ATR 14 / ATR %" value={`${formatNumber(n(vol, "atr14"), 1)} / ${formatNumber(n(vol, "atrPct"), 2)}%`} />
          <Row label="ATR % percentile (1Y)" value={formatNumber(n(vol, "atrPctPercentile252"), 0)} />
          <Row label="Bollinger width" value={`${formatNumber(n(bb, "bandwidthPct"), 2)}%`} />
        </Section>
        <Section title="Volume">
          <Row label="Avg volume 20 / 50" value={`${formatCompact(n(volume, "avgVolume20"))} / ${formatCompact(n(volume, "avgVolume50"))}`} />
          <Row label="Avg value traded 20" value={formatCompact(n(volume, "avgValue20"), snapshot.currency)} />
          <Row label="Up / down volume (50)" value={formatNumber(n(volume, "upDownVolumeRatio50"), 2)} />
          <Row label="Volume spikes up / down (60)" value={spikes ? `${spikes.upDays ?? EMPTY} / ${spikes.downDays ?? EMPTY}` : EMPTY} />
        </Section>
        <Section title="Relative strength">
          <Row label="Ratio change vs IHSG (50 days)" value={signed(n(rs, "ratioChange50Pct"), 1)} />
          <Row label="Return 6M / 12M" value={`${signed(n(rs, "return6mPct"), 1)} / ${signed(n(rs, "return12mPct"), 1)}`} />
          <Row label="Percentile 6M / 12M" hint="among the stored listings of the exchange" value={`${formatNumber(n(rs, "percentile6m"), 0)} / ${formatNumber(n(rs, "percentile12m"), 0)} of ${rs.universe12m ?? EMPTY}`} />
          <Row label="vs sector median 6M" value={sector?.vsSector6mPct !== undefined && sector?.vsSector6mPct !== null ? signed(sector.vsSector6mPct as number, 1) : EMPTY} />
        </Section>
        <Section title="Market regime">
          <Row label="IHSG vs SMA 200" value={regime.indexVsSma200Pct === null || regime.indexVsSma200Pct === undefined ? EMPTY : `${signed(regime.indexVsSma200Pct as number, 1)} (${regime.indexAboveSma200 ? "above" : "below"})`} />
          <Row label="Breadth above SMA 200" hint={String(regime.breadthNote ?? "")} value={`${pct(n(regime, "breadthAbove200Pct"))} of ${regime.breadthCounted ?? EMPTY}`} />
        </Section>
        <Section title="Macro">
          <Row label="BI rate (6M change)" value={bi?.value === null || bi?.value === undefined ? "no data" : `${formatNumber(bi.value as number, 2)}% (${signed(bi.changePoints as number | null, 2).replace("%", " pts")})`} />
          <Row label="SBN 10Y (3M change)" value={sbn?.value === null || sbn?.value === undefined ? "no data" : `${formatNumber(sbn.value as number, 2)}% (${signed(sbn.changePoints as number | null, 2).replace("%", " pts")})`} />
          <Row label="USD/IDR (3M change)" value={usd?.value === null || usd?.value === undefined ? "no data" : `${formatNumber(usd.value as number, 0)} (${signed(usd.changePct as number | null, 1)})`} />
          <Row label="Liquidity regime" hint="Druckenmiller: the Fed and BI, US net liquidity, credit, the dollar, the rupiah, foreign flows" value={regimeText(regimes.liquidity)} />
          <Row label="Risk regime" hint="Tudor Jones: VIX, credit, the US curve, the dollar, US inflation, SBN yields" value={regimeText(regimes.risk)} />
        </Section>
        <Section title="Risk flags">
          <Row label="ARA / ARB days (60)" value={`${risk.araCount60 ?? EMPTY} / ${risk.arbCount60 ?? EMPTY}`} />
          <Row label="Gap days (60)" value={`${risk.gapDays60 ?? EMPTY} (${formatNumber(n(risk, "gapFrequency60Pct"), 0)}%)`} />
          <Row
            label="Missing sessions (last 60 bars)"
            hint="IDX sessions without a bar of this listing; 3 in a row, or no volume on the last 3 bars, may be a suspension"
            value={`${risk.missingSessions60 ?? EMPTY}${risk.possiblySuspended ? " · check for a suspension" : ""}`}
          />
          <Row
            label="Index sessions after the last price"
            hint="the stored prices are older than the index: run the price ingestion"
            value={String(risk.sessionsAfterLastBar ?? EMPTY)}
          />
          <Row label="Special monitoring / suspended" value="no source" />
        </Section>
        {snapshot.notes.length > 0 && (
          <ul className="list-disc space-y-1 pt-3 pl-4 text-xs text-muted-foreground">
            {snapshot.notes.map((note) => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}

// ------------------------------------------------------------------ market panels

const regimeConfig = {
  close: { label: "IHSG", theme: { light: "oklch(0.371 0 0)", dark: "oklch(0.87 0 0)" } },
  sma200: { label: "SMA 200", color: "oklch(0.637 0.237 25.331)" },
} satisfies ChartConfig

const macroConfig = {
  value: { label: "Value", theme: { light: "oklch(0.371 0 0)", dark: "oklch(0.87 0 0)" } },
} satisfies ChartConfig

function dateOf(t: number) {
  return new Date(t * 1000).toISOString().slice(0, 10)
}

export function RegimeCard({ market }: { market: Market }) {
  const r = market.regime
  const sma = new Map((r.sma200Series ?? []).map(([t, v]) => [t, v]))
  const data = (r.closes ?? []).map(([t, v]) => ({ date: dateOf(t), close: v, sma200: sma.get(t) ?? null }))
  return (
    <Card>
      <CardHeader>
        <CardTitle>IHSG regime</CardTitle>
        <CardDescription>
          {r.close !== undefined
            ? `${formatNumber(r.close, 2)} on ${formatDate(r.date)} · ${r.aboveSma200 ? "above" : "below"} its SMA 200 (${signed(r.vsSma200Pct, 1)}) · breadth ${market.breadth.above200Pct === null ? EMPTY : formatNumber(market.breadth.above200Pct, 0) + "%"} of ${market.breadth.counted} stored listings above their SMA 200`
            : "IHSG not loaded yet (run the technical refresh)"}
        </CardDescription>
      </CardHeader>
      <CardContent>
        {data.length === 0 ? (
          <EmptyState variant="chart" title="No index data" description="The nightly technical refresh loads IHSG from Yahoo Finance." />
        ) : (
          <ChartContainer config={regimeConfig} className="aspect-auto h-[200px] w-full">
            <LineChart data={data} margin={{ left: 4, right: 4 }}>
              <CartesianGrid vertical={false} strokeDasharray="3 3" />
              <XAxis dataKey="date" tickLine={false} axisLine={false} minTickGap={40} tick={{ fontSize: 10 }} tickFormatter={(v: string) => formatDate(v)} />
              <YAxis tickLine={false} axisLine={false} width={48} domain={["auto", "auto"]} tick={{ fontSize: 10 }} tickFormatter={(v: number) => formatNumber(v, 0)} />
              <ChartTooltip content={<ChartTooltipContent labelFormatter={(v) => formatDate(String(v))} />} />
              <Line dataKey="close" stroke="var(--color-close)" dot={false} strokeWidth={1.5} isAnimationActive={false} />
              <Line dataKey="sma200" stroke="var(--color-sma200)" dot={false} strokeWidth={1.5} strokeDasharray="4 3" isAnimationActive={false} connectNulls />
            </LineChart>
          </ChartContainer>
        )}
      </CardContent>
    </Card>
  )
}

/** "Easing (+3 of 6 signals)", "Unknown: fewer than 3 signals" */
function regimeText(regime: MacroRegime | undefined): string {
  if (!regime) return "no data"
  if (!regime.state) return `unknown (${regime.signals} of at least 3 signals)`
  const score = regime.score ?? 0
  return `${regime.state.replace("_", " ").toLowerCase()} (${score > 0 ? "+" : ""}${formatNumber(score, 1)} from ${regime.signals} signals)`
}

/** The series of the macro panel, in this order (each shown when stored) */
const MACRO_PANEL: { code: string; label: string; digits: number }[] = [
  { code: "BI_RATE", label: "BI rate (%)", digits: 2 },
  { code: "SBN_10Y", label: "SBN 10Y yield (%)", digits: 2 },
  { code: "USD_IDR", label: "USD/IDR", digits: 0 },
  { code: "ID_CPI_YOY", label: "Indonesia CPI (YoY %)", digits: 2 },
  { code: "US_FED_FUNDS", label: "Fed funds (%)", digits: 2 },
  { code: "US_10Y", label: "US 10Y yield (%)", digits: 2 },
  { code: "DXY", label: "Dollar index (DXY)", digits: 2 },
  { code: "US_HY_SPREAD", label: "US high-yield spread (%)", digits: 2 },
  { code: "VIX", label: "VIX", digits: 2 },
]

export function MacroCard({ market }: { market: Market }) {
  const regimes = (market.macroSignals?.regimes ?? {}) as Record<string, MacroRegime | undefined>
  const panels = MACRO_PANEL.filter((p) => (market.macro[p.code]?.points.length ?? 0) > 0)
  return (
    <Card>
      <CardHeader>
        <CardTitle>Macro</CardTitle>
        <CardDescription>
          Last year · regimes of the Druckenmiller and Tudor Jones presets as of {formatDate(market.asOfDate)} · data from
          Ingestion › Macro
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <RegimeBox title="Liquidity (Druckenmiller)" regime={regimes.liquidity} good="EASING" bad="TIGHTENING" />
          <RegimeBox title="Risk (Tudor Jones)" regime={regimes.risk} good="RISK_ON" bad="RISK_OFF" />
        </div>
        {panels.length === 0 ? (
          <EmptyState variant="chart" title="No macro data" description="Run the macro ingestion on Ingestion › Macro." />
        ) : (
          <div className="grid gap-4 sm:grid-cols-3">
            {panels.map(({ code, label, digits }) => {
              const series = market.macro[code]
              const data = (series?.points ?? []).map(([t, v]) => ({ date: dateOf(t), value: v }))
              const last = data[data.length - 1]
              return (
                <div key={code} className="min-w-0 space-y-1">
                  <p className="text-sm font-medium">{label}</p>
                  <p className="text-xs text-muted-foreground">
                    {last ? `${formatNumber(last.value, digits)} on ${formatDate(last.date)}` : "No data"}
                  </p>
                  {data.length > 1 ? (
                    <ChartContainer config={macroConfig} className="aspect-auto h-[110px] w-full">
                      <LineChart data={data} margin={{ left: 0, right: 0, top: 4, bottom: 0 }}>
                        <XAxis dataKey="date" hide />
                        <YAxis hide domain={["auto", "auto"]} />
                        <ChartTooltip content={<ChartTooltipContent labelFormatter={(v) => formatDate(String(v))} />} />
                        <Line dataKey="value" stroke="var(--color-value)" dot={false} strokeWidth={1.5} isAnimationActive={false} />
                      </LineChart>
                    </ChartContainer>
                  ) : (
                    <div className="flex h-[110px] items-center justify-center rounded-md border border-dashed text-xs text-muted-foreground">
                      {series?.source ?? "no source"}
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

/** A regime with its state and the signals that voted for and against it. */
function RegimeBox({ title, regime, good, bad }: { title: string; regime: MacroRegime | undefined; good: string; bad: string }) {
  const state = regime?.state ?? null
  return (
    <div className="space-y-1.5 rounded-lg border p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm font-medium">{title}</p>
        <Badge
          variant="outline"
          className={cn(
            state === good && "border-emerald-600/40 text-emerald-700 dark:text-emerald-400",
            state === bad && "border-rose-600/40 text-rose-700 dark:text-rose-400"
          )}
        >
          {regimeText(regime)}
        </Badge>
      </div>
      {regime && regime.components.length > 0 ? (
        <ul className="space-y-0.5 text-xs text-muted-foreground">
          {regime.components.map((c) => (
            <li key={c.signal} className={cn(c.vote > 0 && "text-emerald-700 dark:text-emerald-400", c.vote < 0 && "text-rose-700 dark:text-rose-400")}>
              {c.vote > 0 ? "+ " : c.vote < 0 ? "− " : "· "}
              {c.text}
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-xs text-muted-foreground">{regime?.note ?? "No macro signals yet: run the macro ingestion."}</p>
      )}
    </div>
  )
}

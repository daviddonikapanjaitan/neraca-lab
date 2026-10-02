"use client"

// Illustrated empty state, adapted from shadcn-fintech (MIT, see THIRD_PARTY_LICENSE_shadcn-fintech.txt).
import { motion } from "motion/react"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"

type EmptyStateVariant = "chart" | "search" | "filter" | "generic"

type EmptyStateProps = {
  variant?: EmptyStateVariant
  title?: string
  description?: string
  actionLabel?: string
  onAction?: () => void
  className?: string
}

// ── SVG Illustrations ────────────────────────────────────────────────────────

function ChartIllustration() {
  return (
    <svg width="80" height="80" viewBox="0 0 80 80" fill="none">
      {/* Axes */}
      <motion.path
        d="M 15 65 L 15 15 M 15 65 L 70 65"
        className="stroke-border"
        strokeWidth="1.5"
        strokeLinecap="round"
        initial={{ pathLength: 0 }}
        animate={{ pathLength: 1 }}
        transition={{ duration: 0.6 }}
      />
      {/* Bars */}
      {[
        { x: 22, h: 30, color: "fill-primary/20" },
        { x: 33, h: 42, color: "fill-primary/30" },
        { x: 44, h: 25, color: "fill-primary/20" },
        { x: 55, h: 48, color: "fill-primary/40" },
      ].map((bar, i) => (
        <motion.rect
          key={bar.x}
          x={bar.x} y={65 - bar.h} width="8" height={bar.h} rx="2"
          className={bar.color}
          initial={{ height: 0, y: 65 }}
          animate={{ height: bar.h, y: 65 - bar.h }}
          transition={{ delay: 0.5 + i * 0.15, duration: 0.5, ease: "easeOut" }}
        />
      ))}
      {/* Trend line */}
      <motion.path
        d="M 26 45 L 37 30 L 48 48 L 59 22"
        className="stroke-primary/50"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
        initial={{ pathLength: 0 }}
        animate={{ pathLength: 1 }}
        transition={{ delay: 1, duration: 0.8 }}
      />
      <motion.circle cx="59" cy="22" r="3" className="fill-primary/40" initial={{ scale: 0 }} animate={{ scale: [0, 1.3, 1] }} transition={{ delay: 1.6 }} />
    </svg>
  )
}

function SearchIllustration() {
  return (
    <svg width="80" height="80" viewBox="0 0 80 80" fill="none">
      <motion.circle
        cx="36" cy="36" r="20"
        className="fill-muted/40 stroke-border"
        strokeWidth="1.5"
        initial={{ scale: 0.8, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        transition={{ duration: 0.4 }}
      />
      <motion.line
        x1="50" y1="50" x2="66" y2="66"
        className="stroke-muted-foreground/40"
        strokeWidth="4"
        strokeLinecap="round"
        initial={{ pathLength: 0 }}
        animate={{ pathLength: 1 }}
        transition={{ delay: 0.3, duration: 0.4 }}
      />
      {/* X marks inside */}
      <motion.path
        d="M 30 30 L 42 42 M 42 30 L 30 42"
        className="stroke-muted-foreground/20"
        strokeWidth="2"
        strokeLinecap="round"
        initial={{ pathLength: 0 }}
        animate={{ pathLength: 1 }}
        transition={{ delay: 0.6, duration: 0.4 }}
      />
      {/* Floating question marks */}
      <motion.text x="14" y="22" className="fill-muted-foreground/15 text-[14px] font-bold" animate={{ y: [22, 18, 22] }} transition={{ duration: 3, repeat: Infinity }}>?</motion.text>
      <motion.text x="60" y="20" className="fill-muted-foreground/15 text-[10px] font-bold" animate={{ y: [20, 16, 20] }} transition={{ duration: 2.5, delay: 0.4, repeat: Infinity }}>?</motion.text>
    </svg>
  )
}

function GenericIllustration() {
  return (
    <svg width="80" height="80" viewBox="0 0 80 80" fill="none">
      {/* Box */}
      <motion.rect
        x="16" y="28" width="48" height="36" rx="4"
        className="fill-muted/40 stroke-border"
        strokeWidth="1.5"
        initial={{ y: 38, opacity: 0 }}
        animate={{ y: 28, opacity: 1 }}
        transition={{ duration: 0.5 }}
      />
      {/* Lid */}
      <motion.path
        d="M 12 28 L 40 16 L 68 28"
        className="stroke-border fill-muted/20"
        strokeWidth="1.5"
        strokeLinejoin="round"
        initial={{ y: 10, opacity: 0 }}
        animate={{ y: 0, opacity: 1 }}
        transition={{ delay: 0.3, duration: 0.4 }}
      />
      {/* Sparkle particles */}
      {[
        { cx: 30, cy: 22, r: 1.5, delay: 0 },
        { cx: 50, cy: 20, r: 1, delay: 0.3 },
        { cx: 58, cy: 26, r: 1.5, delay: 0.6 },
      ].map((p, i) => (
        <motion.circle
          key={i}
          cx={p.cx} cy={p.cy} r={p.r}
          className="fill-primary/30"
          animate={{ y: [0, -6, 0], opacity: [0.3, 0.8, 0.3] }}
          transition={{ duration: 2, delay: p.delay, repeat: Infinity }}
        />
      ))}
    </svg>
  )
}

// ── Variant config ───────────────────────────────────────────────────────────

const variants: Record<EmptyStateVariant, {
  illustration: React.ReactNode
  title: string
  description: string
}> = {
  chart: {
    illustration: <ChartIllustration />,
    title: "No data yet",
    description: "Figures will appear here once they are stored for this company.",
  },
  search: {
    illustration: <SearchIllustration />,
    title: "No results found",
    description: "Try adjusting your search terms or check for typos.",
  },
  filter: {
    illustration: <SearchIllustration />,
    title: "No matching results",
    description: "No items match your current filters. Try adjusting or clearing your filters.",
  },
  generic: {
    illustration: <GenericIllustration />,
    title: "Nothing here yet",
    description: "This section is empty. Content will appear here when data becomes available.",
  },
}

// ── Component ────────────────────────────────────────────────────────────────

export function EmptyState({
  variant = "generic",
  title,
  description,
  actionLabel,
  onAction,
  className,
}: EmptyStateProps) {
  const config = variants[variant]

  return (
    <motion.div
      initial={{ opacity: 0, y: 16 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4, ease: "easeOut" }}
      className={cn(
        "flex flex-col items-center justify-center gap-4 py-16 text-center",
        className
      )}
    >
      {/* Illustration */}
      <motion.div
        initial={{ scale: 0.85 }}
        animate={{ scale: 1 }}
        transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
      >
        {config.illustration}
      </motion.div>

      {/* Text */}
      <div className="max-w-xs space-y-1.5">
        <motion.h3
          className="text-sm font-semibold"
          initial={{ opacity: 0, y: 4 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.2 }}
        >
          {title ?? config.title}
        </motion.h3>
        <motion.p
          className="text-xs leading-relaxed text-muted-foreground"
          initial={{ opacity: 0, y: 4 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.3 }}
        >
          {description ?? config.description}
        </motion.p>
      </div>

      {/* Action */}
      {actionLabel && onAction && (
        <motion.div
          initial={{ opacity: 0, y: 4 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.4 }}
        >
          <Button size="sm" variant="outline" onClick={onAction} className="mt-1">
            {actionLabel}
          </Button>
        </motion.div>
      )}
    </motion.div>
  )
}

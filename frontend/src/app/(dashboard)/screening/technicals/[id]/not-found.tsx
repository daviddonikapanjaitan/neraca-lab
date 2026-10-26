import { TechnicalScreeningNotFound } from "@/components/technical-screening/not-found-card"
import { TECHNICALS_ALL_HREF } from "@/lib/screening-sections"

export default function NotFound() {
  return <TechnicalScreeningNotFound backHref={TECHNICALS_ALL_HREF} />
}

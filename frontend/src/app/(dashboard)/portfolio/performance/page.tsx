import type { Metadata } from "next"
import Link from "next/link"

import { AccessDenied } from "@/components/access-denied"
import { EmptyState } from "@/components/empty-state"
import { buttonVariants } from "@/components/ui/button"
import { requireUser } from "@/lib/api"
import { hasPermission, homePath } from "@/lib/permissions"
import { PORTFOLIO_HREF } from "@/lib/portfolio"

export const metadata: Metadata = {
  title: "Portfolio Performance",
}

/** Portfolio > Performance: planned for a later step (the instruction marks it "later"). */
export default async function Page() {
  const user = await requireUser()
  if (!hasPermission(user, "COMPANIES")) {
    return <AccessDenied permission="COMPANIES" homeHref={homePath(user)} />
  }
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-4 p-4 pt-0">
      <EmptyState
        variant="chart"
        title="Performance comes later"
        description="The portfolio's performance over time is planned for a later step. Your positions, metrics and sector allocation are on Position."
      />
      <Link href={PORTFOLIO_HREF} className={buttonVariants({ variant: "outline", size: "sm" })}>
        Open Position
      </Link>
    </div>
  )
}

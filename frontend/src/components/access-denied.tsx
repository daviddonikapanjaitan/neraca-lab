import Link from "next/link"
import { ShieldAlertIcon } from "lucide-react"

import { buttonVariants } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { PERMISSION_LABEL } from "@/lib/permissions"
import type { Permission } from "@/lib/types"

/** A page the logged-in user may not open (missing permission). The backend refuses its APIs too. */
export function AccessDenied({ permission, homeHref }: { permission: Permission; homeHref: string }) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center p-4 pt-0">
      <Card className="mx-auto w-full max-w-xl">
        <CardContent className="flex flex-col items-center gap-4 py-10 text-center">
          <div className="flex size-12 items-center justify-center rounded-2xl bg-destructive/10 text-destructive">
            <ShieldAlertIcon className="size-6" />
          </div>
          <div className="space-y-1.5">
            <h2 className="text-base font-semibold">No access</h2>
            <p className="text-sm text-muted-foreground">
              This page needs the {PERMISSION_LABEL[permission]} permission. Ask an administrator to give one of your
              roles this permission.
            </p>
          </div>
          <Link href={homeHref} className={buttonVariants({ variant: "outline", size: "sm" })}>
            Go to a page you can open
          </Link>
        </CardContent>
      </Card>
    </div>
  )
}

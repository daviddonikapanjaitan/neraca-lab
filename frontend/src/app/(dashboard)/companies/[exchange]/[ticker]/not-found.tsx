import Link from "next/link"
import { SearchXIcon } from "lucide-react"

import { buttonVariants } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"

export default function CompanyNotFound() {
  return (
    <div className="flex flex-1 flex-col items-center justify-center p-4 pt-0">
      <Card className="mx-auto w-full max-w-xl">
        <CardContent className="flex flex-col items-center gap-4 py-10 text-center">
          <div className="flex size-12 items-center justify-center rounded-2xl bg-muted text-muted-foreground">
            <SearchXIcon className="size-6" />
          </div>
          <div className="space-y-1.5">
            <h2 className="text-base font-semibold">Company not found</h2>
            <p className="text-sm text-muted-foreground">
              There is no company with this ticker on this exchange in Neraca Lab.
            </p>
          </div>
          <Link href="/companies" className={buttonVariants({ variant: "outline", size: "sm" })}>
            Back to companies
          </Link>
        </CardContent>
      </Card>
    </div>
  )
}

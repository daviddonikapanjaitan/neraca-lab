import type { Metadata } from "next"
import { redirect } from "next/navigation"
import { LandmarkIcon } from "lucide-react"

import { LoginForm } from "@/components/auth/login-form"
import { getCurrentUser } from "@/lib/api"
import { homePath, safeNextPath } from "@/lib/permissions"
import type { User } from "@/lib/types"

export const metadata: Metadata = {
  title: "Log in",
}

function first(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value
}

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>
}) {
  const params = await searchParams
  const next = safeNextPath(first(params.next))

  let user: User | null = null
  try {
    user = await getCurrentUser()
  } catch {
    // backend unreachable: show the form, the login attempt reports the error
  }
  if (user) {
    redirect(next ?? homePath(user))
  }

  return (
    <div className="flex min-h-svh flex-col items-center justify-center gap-6 bg-muted/40 p-4">
      <div className="flex items-center gap-2.5">
        <div className="flex size-10 items-center justify-center rounded-xl bg-primary text-primary-foreground">
          <LandmarkIcon className="size-5" />
        </div>
        <div className="leading-tight">
          <div className="font-semibold">Neraca Lab</div>
          <div className="text-xs text-muted-foreground">Fundamental Analysis</div>
        </div>
      </div>
      <LoginForm next={next} expired={first(params.expired) === "1"} />
    </div>
  )
}

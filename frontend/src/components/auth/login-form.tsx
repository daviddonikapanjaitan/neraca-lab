"use client"

import { useState } from "react"
import { CircleAlertIcon, EyeIcon, EyeOffIcon, InfoIcon, LoaderIcon, LogInIcon } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { homePath } from "@/lib/permissions"
import type { User } from "@/lib/types"

/** Username / password login. The session token ends up in an httpOnly cookie set by /api/auth/login. */
export function LoginForm({ next, expired }: { next: string | null; expired: boolean }) {
  const [username, setUsername] = useState("")
  const [password, setPassword] = useState("")
  const [showPassword, setShowPassword] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (pending) return
    if (!username.trim() || !password) {
      setError("Enter your username and password.")
      return
    }
    setPending(true)
    setError(null)
    try {
      // fetch directly: a 401 here means a wrong password, not an expired session
      const response = await fetch("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: username.trim(), password }),
        cache: "no-store",
      })
      if (!response.ok) {
        let detail = `Login failed (HTTP ${response.status})`
        try {
          const problem = (await response.json()) as { detail?: string; title?: string }
          detail = problem.detail ?? problem.title ?? detail
        } catch {
          // not JSON: keep the default
        }
        setError(detail)
        setPending(false)
        return
      }
      const { user } = (await response.json()) as { user: User }
      // full navigation: every server component renders again with the new session
      window.location.assign(next ?? homePath(user))
    } catch {
      setError("Cannot reach the Neraca Lab server. Check your connection and try again.")
      setPending(false)
    }
  }

  return (
    <Card className="w-full max-w-sm">
      <CardHeader>
        <CardTitle className="text-lg">Log in</CardTitle>
        <CardDescription>Use your Neraca Lab username and password.</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
          {expired && !error && (
            <p role="status" className="flex items-start gap-2 rounded-lg bg-muted px-3 py-2 text-xs text-muted-foreground">
              <InfoIcon className="mt-px size-3.5 shrink-0" />
              Your session has ended. Log in again to continue.
            </p>
          )}
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="username">Username</Label>
            <Input
              id="username"
              name="username"
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              autoFocus
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              disabled={pending}
              aria-invalid={!!error}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="password">Password</Label>
            <div className="relative">
              <Input
                id="password"
                name="password"
                type={showPassword ? "text" : "password"}
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                disabled={pending}
                aria-invalid={!!error}
                className="pr-9"
              />
              <Button
                type="button"
                variant="ghost"
                size="icon-xs"
                className="absolute top-1/2 right-1.5 -translate-y-1/2"
                aria-label={showPassword ? "Hide password" : "Show password"}
                onClick={() => setShowPassword(!showPassword)}
              >
                {showPassword ? <EyeOffIcon /> : <EyeIcon />}
              </Button>
            </div>
          </div>
          {error && (
            <p role="alert" className="flex items-start gap-2 text-xs text-destructive">
              <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
              <span>{error}</span>
            </p>
          )}
          <Button type="submit" size="lg" disabled={pending}>
            {pending ? <LoaderIcon className="animate-spin" /> : <LogInIcon />}
            {pending ? "Logging in..." : "Log in"}
          </Button>
        </form>
      </CardContent>
    </Card>
  )
}

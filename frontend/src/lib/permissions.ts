// Permission helpers shared by server and client components. The backend enforces every
// permission on its APIs; these only decide what the UI shows.
import type { Permission, User } from "@/lib/types"

export function hasPermission(user: Pick<User, "permissions"> | null | undefined, permission: Permission): boolean {
  return !!user && user.permissions.includes(permission)
}

/** First page the user may open: companies, ingestion, admin center, else the own profile. */
export function homePath(user: Pick<User, "permissions">): string {
  if (hasPermission(user, "COMPANIES")) return "/companies"
  if (hasPermission(user, "INGESTION")) return "/ingestion"
  if (hasPermission(user, "ADMIN")) return "/admin/users"
  return "/profile"
}

/** A same-site path to return to after the login; anything else (other hosts, API routes) is ignored. */
export function safeNextPath(next: string | null | undefined): string | null {
  if (!next || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) return null
  if (next === "/login" || next.startsWith("/login?") || next.startsWith("/api/")) return null
  return next
}

/** "Neraca Lab Administrator" -> "NL", "admin" -> "AD" */
export function initials(user: Pick<User, "fullName" | "username">): string {
  const words = (user.fullName ?? "").trim().split(/\s+/).filter(Boolean)
  if (words.length >= 2) return (words[0][0] + words[1][0]).toUpperCase()
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase()
  return user.username.slice(0, 2).toUpperCase()
}

export const PERMISSION_LABEL: Record<Permission, string> = {
  ADMIN: "Admin",
  INGESTION: "Ingestion",
  COMPANIES: "Companies",
}

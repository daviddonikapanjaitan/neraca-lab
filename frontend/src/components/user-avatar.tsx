"use client"

import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar"
import { initials } from "@/lib/permissions"
import type { User } from "@/lib/types"

/**
 * Profile picture with the initials as fallback (no picture, or while it loads).
 * `source`: "self" = the logged-in user's own picture (/api/profile/avatar), "admin" = any user's
 * picture through user management (/api/admin/users/{id}/avatar).
 */
export function UserAvatar({
  user,
  source,
  size = "default",
  className,
}: {
  user: Pick<User, "id" | "username" | "fullName" | "hasAvatar" | "avatarUpdatedAt">
  source: "self" | "admin"
  size?: "default" | "sm" | "lg"
  className?: string
}) {
  // the version parameter makes the browser load a new picture after every change
  const version = encodeURIComponent(user.avatarUpdatedAt ?? "")
  const src = source === "self" ? `/api/profile/avatar?v=${version}` : `/api/admin/users/${user.id}/avatar?v=${version}`
  return (
    <Avatar size={size} className={className}>
      {user.hasAvatar && <AvatarImage src={src} alt="" />}
      <AvatarFallback className="bg-primary/10 font-medium text-primary">{initials(user)}</AvatarFallback>
    </Avatar>
  )
}

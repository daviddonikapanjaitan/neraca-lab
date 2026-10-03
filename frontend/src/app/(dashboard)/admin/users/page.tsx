import type { Metadata } from "next"

import { AccessDenied } from "@/components/access-denied"
import { UsersPageClient } from "@/components/admin/users-page-client"
import { ApiErrorState } from "@/components/api-error-state"
import { ApiError, getRoles, getUsers, requireUser } from "@/lib/api"
import { hasPermission, homePath } from "@/lib/permissions"
import type { Role, User } from "@/lib/types"

export const metadata: Metadata = {
  title: "User Management",
}

async function load(): Promise<{ users: User[]; roles: Role[]; error?: never } | { users?: never; roles?: never; error: ApiError }> {
  try {
    const [users, roles] = await Promise.all([getUsers(), getRoles()])
    return { users, roles }
  } catch (error) {
    if (error instanceof ApiError) return { error }
    throw error
  }
}

export default async function Page() {
  const user = await requireUser()
  if (!hasPermission(user, "ADMIN")) {
    return <AccessDenied permission="ADMIN" homeHref={homePath(user)} />
  }

  const { users, roles, error } = await load()
  if (error) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 p-4 pt-0">
        <ApiErrorState error={error} backHref={homePath(user)} backLabel="Back" />
      </div>
    )
  }
  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      <UsersPageClient users={users} roles={roles} currentUserId={user.id} />
    </div>
  )
}

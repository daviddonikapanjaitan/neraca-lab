import type { Metadata } from "next"

import { AccessDenied } from "@/components/access-denied"
import { RolesPageClient } from "@/components/admin/roles-page-client"
import { ApiErrorState } from "@/components/api-error-state"
import { ApiError, getPermissions, getRoles, requireUser } from "@/lib/api"
import { hasPermission, homePath } from "@/lib/permissions"
import type { PermissionInfo, Role } from "@/lib/types"

export const metadata: Metadata = {
  title: "Role Management",
}

async function load(): Promise<
  { roles: Role[]; permissions: PermissionInfo[]; error?: never } | { roles?: never; permissions?: never; error: ApiError }
> {
  try {
    const [roles, permissions] = await Promise.all([getRoles(), getPermissions()])
    return { roles, permissions }
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

  const { roles, permissions, error } = await load()
  if (error) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 p-4 pt-0">
        <ApiErrorState error={error} backHref={homePath(user)} backLabel="Back" />
      </div>
    )
  }
  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      <RolesPageClient roles={roles} permissions={permissions} />
    </div>
  )
}

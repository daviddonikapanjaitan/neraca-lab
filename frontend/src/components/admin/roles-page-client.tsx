"use client"

import { useState } from "react"
import { KeyRoundIcon, PencilIcon, PlusIcon, ShieldCheckIcon, Trash2Icon, UsersIcon } from "lucide-react"

import { ConfirmDeleteDialog } from "@/components/admin/confirm-delete-dialog"
import { RoleFormSheet } from "@/components/admin/role-form-sheet"
import { StatTile } from "@/components/stat-tile"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { EMPTY, formatTimestamp } from "@/lib/format"
import { PERMISSION_LABEL } from "@/lib/permissions"
import type { PermissionInfo, Role } from "@/lib/types"

/** Why a role cannot be deleted, or null. */
function deleteBlocker(role: Role): string | null {
  if (role.system) return "The built-in role cannot be deleted"
  if (role.userCount > 0) {
    return `Assigned to ${role.userCount} ${role.userCount === 1 ? "user" : "users"}: remove it from them first`
  }
  return null
}

/** Role Management: list, add, view / edit and delete roles and their permissions. */
export function RolesPageClient({ roles, permissions }: { roles: Role[]; permissions: PermissionInfo[] }) {
  const [editing, setEditing] = useState<Role | "new" | null>(null)
  const [deleting, setDeleting] = useState<Role | null>(null)

  const editedRole = editing && editing !== "new" ? roles.find((r) => r.id === editing.id) ?? editing : null

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h1 className="text-xl font-semibold tracking-tight">Role Management</h1>
          <p className="text-sm text-muted-foreground">
            A role is a named set of permissions. Users get the permissions of all their roles.
          </p>
        </div>
        <Button onClick={() => setEditing("new")}>
          <PlusIcon /> Add role
        </Button>
      </div>

      <div className="grid gap-3 md:grid-cols-3">
        {permissions.map((p) => (
          <StatTile
            key={p.code}
            label={`${p.label} permission`}
            value={`${roles.filter((r) => r.permissions.includes(p.code)).length} ${roles.filter((r) => r.permissions.includes(p.code)).length === 1 ? "role" : "roles"}`}
            hint={p.description}
            icon={p.code === "ADMIN" ? ShieldCheckIcon : KeyRoundIcon}
            tone={p.code === "ADMIN" ? "primary" : "muted"}
          />
        ))}
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Roles</CardTitle>
          <CardDescription>
            {roles.length} {roles.length === 1 ? "role" : "roles"}. Select a role to view or edit.
          </CardDescription>
        </CardHeader>
        <CardContent className="px-0">
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4">Role</TableHead>
                  <TableHead>Permissions</TableHead>
                  <TableHead className="text-right">Users</TableHead>
                  <TableHead className="hidden lg:table-cell">Updated</TableHead>
                  <TableHead className="pr-4 text-right">
                    <span className="sr-only">Actions</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {roles.map((role) => {
                  const blocker = deleteBlocker(role)
                  return (
                    <TableRow key={role.id} className="cursor-pointer" onClick={() => setEditing(role)}>
                      <TableCell className="pl-4">
                        <div className="flex items-center gap-1.5">
                          <span className="font-medium">{role.name}</span>
                          {role.system && <Badge variant="secondary">Built-in</Badge>}
                        </div>
                        <div className="max-w-[360px] truncate text-xs text-muted-foreground">{role.description ?? EMPTY}</div>
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-wrap gap-1">
                          {role.permissions.map((p) => (
                            <Badge key={p} variant={p === "ADMIN" ? "default" : "outline"}>
                              {PERMISSION_LABEL[p]}
                            </Badge>
                          ))}
                        </div>
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        <span className="inline-flex items-center gap-1">
                          <UsersIcon className="size-3.5 text-muted-foreground" />
                          {role.userCount}
                        </span>
                      </TableCell>
                      <TableCell className="hidden text-xs tabular-nums text-muted-foreground lg:table-cell">
                        {formatTimestamp(role.updatedAt)}
                      </TableCell>
                      <TableCell className="pr-4 text-right whitespace-nowrap">
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          aria-label={`Edit ${role.name}`}
                          onClick={(e) => {
                            e.stopPropagation()
                            setEditing(role)
                          }}
                        >
                          <PencilIcon />
                        </Button>
                        <Tooltip>
                          <TooltipTrigger render={<span className="inline-flex" onClick={(e) => e.stopPropagation()} />}>
                            <Button
                              variant="ghost"
                              size="icon-sm"
                              aria-label={`Delete ${role.name}`}
                              disabled={blocker !== null}
                              onClick={() => setDeleting(role)}
                              className="text-destructive hover:text-destructive"
                            >
                              <Trash2Icon />
                            </Button>
                          </TooltipTrigger>
                          <TooltipContent>{blocker ?? `Delete ${role.name}`}</TooltipContent>
                        </Tooltip>
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </div>
        </CardContent>
      </Card>

      <RoleFormSheet
        open={editing !== null}
        role={editing === "new" ? null : editedRole}
        permissions={permissions}
        onClose={() => setEditing(null)}
      />

      <ConfirmDeleteDialog
        open={deleting !== null}
        title={`Delete the role ${deleting?.name ?? ""}?`}
        description="The role and its permissions are deleted. This cannot be undone."
        url={deleting ? `/api/admin/roles/${deleting.id}` : null}
        onClose={() => setDeleting(null)}
      />
    </div>
  )
}

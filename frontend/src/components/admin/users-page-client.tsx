"use client"

import { useMemo, useState } from "react"
import {
  PencilIcon,
  SearchIcon,
  ShieldCheckIcon,
  Trash2Icon,
  UserCheckIcon,
  UserPlusIcon,
  UsersIcon,
  UserXIcon,
} from "lucide-react"

import { ConfirmDeleteDialog } from "@/components/admin/confirm-delete-dialog"
import { UserFormSheet } from "@/components/admin/user-form-sheet"
import { EmptyState } from "@/components/empty-state"
import { StatTile } from "@/components/stat-tile"
import { UserAvatar } from "@/components/user-avatar"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { formatTimestamp } from "@/lib/format"
import type { Role, User } from "@/lib/types"

type StatusFilter = "all" | "active" | "inactive"

const STATUS_ITEMS: { value: StatusFilter; label: string }[] = [
  { value: "all", label: "All users" },
  { value: "active", label: "Active" },
  { value: "inactive", label: "Deactivated" },
]

/** Why a user cannot be deleted by the acting administrator, or null. */
function deleteBlocker(user: User, currentUserId: number): string | null {
  if (user.root) return "The root user cannot be deleted"
  if (user.id === currentUserId) return "You cannot delete your own account"
  return null
}

/** User Management: list, search, add, view / edit and delete users. */
export function UsersPageClient({
  users,
  roles,
  currentUserId,
}: {
  users: User[]
  roles: Role[]
  currentUserId: number
}) {
  const [search, setSearch] = useState("")
  const [status, setStatus] = useState<StatusFilter>("all")
  const [editing, setEditing] = useState<User | "new" | null>(null)
  const [deleting, setDeleting] = useState<User | null>(null)

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    return users.filter((u) => {
      if (status === "active" && !u.active) return false
      if (status === "inactive" && u.active) return false
      if (!q) return true
      return [u.username, u.email, u.fullName, u.phone, ...u.roles.map((r) => r.name)].some((v) => v?.toLowerCase().includes(q))
    })
  }, [users, search, status])

  const stats = useMemo(
    () => ({
      active: users.filter((u) => u.active).length,
      admins: users.filter((u) => u.active && u.permissions.includes("ADMIN")).length,
    }),
    [users]
  )

  // the sheet edits the current version of the user (the list is reloaded after every change)
  const editedUser = editing && editing !== "new" ? users.find((u) => u.id === editing.id) ?? editing : null
  const filtersActive = search.trim() !== "" || status !== "all"

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h1 className="text-xl font-semibold tracking-tight">User Management</h1>
          <p className="text-sm text-muted-foreground">
            Add, view, update and delete users. What a user may open comes from the permissions of their roles.
          </p>
        </div>
        <Button onClick={() => setEditing("new")}>
          <UserPlusIcon /> Add user
        </Button>
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatTile label="Users" value={users.length} icon={UsersIcon} />
        <StatTile label="Active" value={stats.active} icon={UserCheckIcon} tone="positive" />
        <StatTile label="Deactivated" value={users.length - stats.active} icon={UserXIcon} tone="muted" hint="Cannot log in" />
        <StatTile label="Administrators" value={stats.admins} icon={ShieldCheckIcon} tone="muted" hint="Active, with the Admin permission" />
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <div className="relative w-full sm:min-w-[220px] sm:flex-1">
          <SearchIcon className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            placeholder="Search username, name, email, phone or role..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-8"
            aria-label="Search users"
          />
        </div>
        <Select items={STATUS_ITEMS} value={status} onValueChange={(v) => v && setStatus(v as StatusFilter)}>
          <SelectTrigger aria-label="Status" className="min-w-[160px]">
            <SelectValue placeholder="Status" />
          </SelectTrigger>
          <SelectContent>
            {STATUS_ITEMS.map((s) => (
              <SelectItem key={s.value} value={s.value}>
                {s.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Users</CardTitle>
          <CardDescription>
            {filtersActive ? `${filtered.length} of ${users.length} users match the filters` : `${users.length} ${users.length === 1 ? "user" : "users"}`}
            . Select a user to view or edit.
          </CardDescription>
        </CardHeader>
        <CardContent className="px-0">
          {filtered.length === 0 ? (
            <EmptyState
              variant="filter"
              actionLabel="Clear filters"
              onAction={() => {
                setSearch("")
                setStatus("all")
              }}
            />
          ) : (
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="pl-4">User</TableHead>
                    <TableHead className="hidden md:table-cell">Email</TableHead>
                    <TableHead>Roles</TableHead>
                    <TableHead className="hidden sm:table-cell">Status</TableHead>
                    <TableHead className="hidden xl:table-cell">Updated</TableHead>
                    <TableHead className="pr-4 text-right">
                      <span className="sr-only">Actions</span>
                    </TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {filtered.map((u) => {
                    const blocker = deleteBlocker(u, currentUserId)
                    return (
                      <TableRow key={u.id} className="cursor-pointer" onClick={() => setEditing(u)}>
                        <TableCell className="pl-4">
                          <div className="flex items-center gap-2.5">
                            <UserAvatar user={u} source="admin" />
                            <div className="min-w-0">
                              <div className="flex items-center gap-1.5">
                                <span className="font-medium">{u.fullName ?? u.username}</span>
                                {u.root && <Badge variant="secondary">Root</Badge>}
                                {u.id === currentUserId && <Badge variant="outline">You</Badge>}
                              </div>
                              <div className="font-mono text-xs text-muted-foreground">{u.username}</div>
                            </div>
                          </div>
                        </TableCell>
                        <TableCell className="hidden md:table-cell">
                          <span className="block max-w-[240px] truncate">{u.email}</span>
                        </TableCell>
                        <TableCell>
                          <div className="flex max-w-[260px] flex-wrap gap-1">
                            {u.roles.length === 0 ? (
                              <span className="text-xs text-muted-foreground">No role</span>
                            ) : (
                              u.roles.map((r) => (
                                <Badge key={r.id} variant={r.system ? "default" : "secondary"}>
                                  {r.name}
                                </Badge>
                              ))
                            )}
                          </div>
                        </TableCell>
                        <TableCell className="hidden sm:table-cell">
                          {u.active ? (
                            <Badge variant="outline" className="border-transparent bg-emerald-500/10 text-emerald-600 dark:text-emerald-400">
                              Active
                            </Badge>
                          ) : (
                            <Badge variant="outline" className="border-transparent bg-muted text-muted-foreground">
                              Deactivated
                            </Badge>
                          )}
                        </TableCell>
                        <TableCell className="hidden text-xs tabular-nums text-muted-foreground xl:table-cell">
                          {formatTimestamp(u.updatedAt)}
                        </TableCell>
                        <TableCell className="pr-4 text-right whitespace-nowrap">
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            aria-label={`Edit ${u.username}`}
                            onClick={(e) => {
                              e.stopPropagation()
                              setEditing(u)
                            }}
                          >
                            <PencilIcon />
                          </Button>
                          <Tooltip>
                            <TooltipTrigger
                              render={
                                <span
                                  className="inline-flex"
                                  onClick={(e) => e.stopPropagation()}
                                />
                              }
                            >
                              <Button
                                variant="ghost"
                                size="icon-sm"
                                aria-label={`Delete ${u.username}`}
                                disabled={blocker !== null}
                                onClick={() => setDeleting(u)}
                                className="text-destructive hover:text-destructive"
                              >
                                <Trash2Icon />
                              </Button>
                            </TooltipTrigger>
                            <TooltipContent>{blocker ?? `Delete ${u.username}`}</TooltipContent>
                          </Tooltip>
                        </TableCell>
                      </TableRow>
                    )
                  })}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>

      <UserFormSheet
        open={editing !== null}
        user={editing === "new" ? null : editedUser}
        roles={roles}
        currentUserId={currentUserId}
        onClose={() => setEditing(null)}
      />

      <ConfirmDeleteDialog
        open={deleting !== null}
        title={`Delete ${deleting?.username ?? "user"}?`}
        description={
          deleting
            ? `${deleting.fullName ?? deleting.username} (${deleting.email}) is deleted with their sessions and role assignments. This cannot be undone.`
            : ""
        }
        url={deleting ? `/api/admin/users/${deleting.id}` : null}
        onClose={() => setDeleting(null)}
      />
    </div>
  )
}

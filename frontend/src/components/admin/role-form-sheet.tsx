"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { CircleAlertIcon, LoaderIcon, LockIcon, SaveIcon } from "lucide-react"

import { FormField } from "@/components/form-field"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from "@/components/ui/sheet"
import { Textarea } from "@/components/ui/textarea"
import { jsonBody, RequestError, requestJson } from "@/lib/client-api"
import { formatTimestamp } from "@/lib/format"
import type { Permission, PermissionInfo, Role } from "@/lib/types"

/** Add a role (`role` null) or view and edit one, in a side sheet. */
export function RoleFormSheet({
  open,
  role,
  permissions,
  onClose,
}: {
  open: boolean
  role: Role | null
  permissions: PermissionInfo[]
  onClose: () => void
}) {
  return (
    <Sheet open={open} onOpenChange={(next) => !next && onClose()}>
      <SheetContent className="w-full overflow-y-auto data-[side=right]:sm:max-w-md">
        {open && <RoleForm key={role?.id ?? "new"} role={role} permissions={permissions} onClose={onClose} />}
      </SheetContent>
    </Sheet>
  )
}

function RoleForm({ role, permissions, onClose }: { role: Role | null; permissions: PermissionInfo[]; onClose: () => void }) {
  const router = useRouter()
  const creating = role === null
  const system = !!role?.system

  const [name, setName] = useState(role?.name ?? "")
  const [description, setDescription] = useState(role?.description ?? "")
  const [selected, setSelected] = useState<Permission[]>(role?.permissions ?? [])
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  function toggle(code: Permission, checked: boolean) {
    setSelected((current) => (checked ? [...new Set([...current, code])] : current.filter((p) => p !== code)))
  }

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (pending) return
    const errors: Record<string, string> = {}
    if (!name.trim()) errors.name = "must not be blank"
    if (selected.length === 0) errors.permissions = "a role needs at least one permission"
    setFieldErrors(errors)
    setError(null)
    if (Object.keys(errors).length > 0) return

    setPending(true)
    try {
      const body = { name, description: description || null, permissions: selected }
      await requestJson(creating ? "/api/admin/roles" : `/api/admin/roles/${role.id}`, jsonBody(creating ? "POST" : "PUT", body))
      router.refresh()
      onClose()
    } catch (e) {
      if (e instanceof RequestError && Object.keys(e.errors).length > 0) {
        setFieldErrors(e.errors)
        setError("Check the highlighted fields.")
      } else {
        setError(e instanceof Error ? e.message : String(e))
      }
    } finally {
      setPending(false)
    }
  }

  return (
    <form onSubmit={save} className="flex min-h-full flex-col" noValidate>
      <SheetHeader>
        <SheetTitle>{creating ? "Add role" : role.name}</SheetTitle>
        <SheetDescription>
          {creating
            ? "Choose the permissions this role gives its users."
            : `${role.userCount} ${role.userCount === 1 ? "user" : "users"} · updated ${formatTimestamp(role.updatedAt)}`}
        </SheetDescription>
      </SheetHeader>

      <div className="flex flex-col gap-4 px-4">
        {system && (
          <p className="flex items-start gap-2 rounded-lg bg-muted px-3 py-2 text-xs text-muted-foreground">
            <LockIcon className="mt-px size-3.5 shrink-0" />
            The built-in role always has every permission and is kept by the root user. Only its description can be changed.
          </p>
        )}

        <FormField id="role-name" label="Name" required error={fieldErrors.name} hint="Unique, up to 50 characters.">
          <Input id="role-name" value={name} onChange={(e) => setName(e.target.value)} disabled={pending || system}
            aria-invalid={!!fieldErrors.name} autoComplete="off" />
        </FormField>

        <FormField id="role-description" label="Description" error={fieldErrors.description}>
          <Textarea id="role-description" value={description} onChange={(e) => setDescription(e.target.value)} disabled={pending} rows={2} />
        </FormField>

        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-medium">
            Permissions <span className="text-destructive" aria-hidden>*</span>
          </legend>
          {permissions.map((p) => (
            <label key={p.code} className="flex items-start gap-2.5 rounded-lg border p-2.5 text-sm has-data-checked:border-primary/40 has-data-checked:bg-primary/5">
              <Checkbox
                checked={selected.includes(p.code)}
                onCheckedChange={(checked) => toggle(p.code, checked === true)}
                disabled={pending || system}
                className="mt-0.5"
              />
              <span className="flex flex-col gap-0.5">
                <span className="flex items-center gap-1.5 font-medium">
                  {p.label}
                  <Badge variant="outline" className="font-mono">{p.code}</Badge>
                </span>
                <span className="text-xs text-muted-foreground">{p.description}</span>
              </span>
            </label>
          ))}
          {fieldErrors.permissions && (
            <p role="alert" className="text-xs text-destructive">
              {fieldErrors.permissions}
            </p>
          )}
        </fieldset>
      </div>

      <SheetFooter className="mt-4 border-t">
        {error && (
          <p role="alert" className="flex items-start gap-2 text-xs text-destructive">
            <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            <span>{error}</span>
          </p>
        )}
        <div className="flex gap-2">
          <Button type="submit" disabled={pending}>
            {pending ? <LoaderIcon className="animate-spin" /> : <SaveIcon />}
            {pending ? "Saving..." : creating ? "Add role" : "Save changes"}
          </Button>
          <Button type="button" variant="outline" onClick={onClose} disabled={pending}>
            Cancel
          </Button>
        </div>
      </SheetFooter>
    </form>
  )
}

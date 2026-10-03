"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { CircleAlertIcon, LoaderIcon, LockIcon, SaveIcon } from "lucide-react"

import { FormField } from "@/components/form-field"
import { UserAvatar } from "@/components/user-avatar"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { Separator } from "@/components/ui/separator"
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from "@/components/ui/sheet"
import { Textarea } from "@/components/ui/textarea"
import { jsonBody, RequestError, requestJson } from "@/lib/client-api"
import { formatTimestamp } from "@/lib/format"
import { PERMISSION_LABEL } from "@/lib/permissions"
import type { Role, User } from "@/lib/types"

/** Add a user (`user` null) or view and edit one, in a side sheet. */
export function UserFormSheet({
  open,
  user,
  roles,
  currentUserId,
  onClose,
}: {
  open: boolean
  user: User | null
  roles: Role[]
  currentUserId: number
  onClose: () => void
}) {
  return (
    <Sheet open={open} onOpenChange={(next) => !next && onClose()}>
      <SheetContent className="w-full overflow-y-auto data-[side=right]:sm:max-w-lg">
        {open && (
          // keyed: the form starts fresh for every user
          <UserForm key={user?.id ?? "new"} user={user} roles={roles} currentUserId={currentUserId} onClose={onClose} />
        )}
      </SheetContent>
    </Sheet>
  )
}

function UserForm({
  user,
  roles,
  currentUserId,
  onClose,
}: {
  user: User | null
  roles: Role[]
  currentUserId: number
  onClose: () => void
}) {
  const router = useRouter()
  const creating = user === null
  const self = user?.id === currentUserId
  const systemRole = roles.find((r) => r.system)

  const [username, setUsername] = useState(user?.username ?? "")
  const [email, setEmail] = useState(user?.email ?? "")
  const [fullName, setFullName] = useState(user?.fullName ?? "")
  const [phone, setPhone] = useState(user?.phone ?? "")
  const [dob, setDob] = useState(user?.dob ?? "")
  const [address, setAddress] = useState(user?.address ?? "")
  const [password, setPassword] = useState("")
  const [active, setActive] = useState(user?.active ?? true)
  const [roleIds, setRoleIds] = useState<number[]>(user?.roles.map((r) => r.id) ?? [])
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  // the root user always keeps the built-in role; nobody deactivates the root user or themselves
  const lockedRole = user?.root ? systemRole?.id : undefined
  const activeLocked = !!user?.root || self

  function toggleRole(id: number, checked: boolean) {
    setRoleIds((current) => (checked ? [...new Set([...current, id])] : current.filter((r) => r !== id)))
  }

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (pending) return
    const errors: Record<string, string> = {}
    if (creating && !username.trim()) errors.username = "must not be blank"
    if (!email.trim()) errors.email = "must not be blank"
    if (creating && password.length < 8) errors.password = "must be 8-72 characters"
    if (!creating && password.length > 0 && password.length < 8) errors.password = "must be 8-72 characters, or empty to keep it"
    if (roleIds.length === 0) errors.roleIds = "a user needs at least one role"
    setFieldErrors(errors)
    setError(null)
    if (Object.keys(errors).length > 0) return

    const body = {
      email,
      fullName: fullName || null,
      phone: phone || null,
      dob: dob || null,
      address: address || null,
      active,
      roleIds,
      ...(creating ? { username, password } : password ? { password } : {}),
    }
    setPending(true)
    try {
      await requestJson(creating ? "/api/admin/users" : `/api/admin/users/${user.id}`, jsonBody(creating ? "POST" : "PUT", body))
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
        <SheetTitle>{creating ? "Add user" : user.fullName ?? user.username}</SheetTitle>
        <SheetDescription>
          {creating
            ? "The user logs in with the username and password set here."
            : `Created ${formatTimestamp(user.createdAt)} · updated ${formatTimestamp(user.updatedAt)}`}
        </SheetDescription>
      </SheetHeader>

      <div className="flex flex-col gap-4 px-4">
        {!creating && (
          <div className="flex items-center gap-3">
            <UserAvatar user={user} source="admin" size="lg" />
            <div className="flex flex-wrap gap-1.5">
              {user.root && <Badge variant="secondary">Root user</Badge>}
              {self && <Badge variant="outline">You</Badge>}
              {user.permissions.map((p) => (
                <Badge key={p} variant="outline">
                  {PERMISSION_LABEL[p]}
                </Badge>
              ))}
            </div>
          </div>
        )}

        <FormField
          id="user-username"
          label="Username"
          required={creating}
          error={fieldErrors.username}
          hint={creating ? "3-50 letters, digits, '.', '_' or '-'. Stored in lower case, cannot be changed later." : (
            <span className="inline-flex items-center gap-1"><LockIcon className="size-3" /> The username cannot be changed.</span>
          )}
        >
          <Input
            id="user-username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            disabled={!creating || pending}
            autoComplete="off"
            aria-invalid={!!fieldErrors.username}
          />
        </FormField>

        <FormField id="user-email" label="Email" required error={fieldErrors.email} hint="Unique per user.">
          <Input
            id="user-email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={pending}
            autoComplete="off"
            aria-invalid={!!fieldErrors.email}
          />
        </FormField>

        <FormField id="user-password" label={creating ? "Password" : "New password"} required={creating}
          error={fieldErrors.password}
          hint={creating ? "8-72 characters." : "Leave empty to keep the password. A new password logs the user out everywhere."}>
          <Input
            id="user-password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={pending}
            autoComplete="new-password"
            aria-invalid={!!fieldErrors.password}
          />
        </FormField>

        <Separator />

        <FormField id="user-fullname" label="Full name" error={fieldErrors.fullName}>
          <Input id="user-fullname" value={fullName} onChange={(e) => setFullName(e.target.value)} disabled={pending} />
        </FormField>
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField id="user-phone" label="Phone" error={fieldErrors.phone}>
            <Input id="user-phone" type="tel" value={phone} onChange={(e) => setPhone(e.target.value)} disabled={pending}
              aria-invalid={!!fieldErrors.phone} placeholder="+62 812 3456 7890" />
          </FormField>
          <FormField id="user-dob" label="Date of birth" error={fieldErrors.dob}>
            <Input id="user-dob" type="date" value={dob} onChange={(e) => setDob(e.target.value)} disabled={pending}
              aria-invalid={!!fieldErrors.dob} />
          </FormField>
        </div>
        <FormField id="user-address" label="Address" error={fieldErrors.address}>
          <Textarea id="user-address" value={address} onChange={(e) => setAddress(e.target.value)} disabled={pending} rows={3} />
        </FormField>

        <Separator />

        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-medium">
            Roles <span className="text-destructive" aria-hidden>*</span>
          </legend>
          {roles.map((role) => {
            const locked = role.id === lockedRole
            return (
              <label key={role.id} className="flex items-start gap-2.5 rounded-lg border p-2.5 text-sm has-data-checked:border-primary/40 has-data-checked:bg-primary/5">
                <Checkbox
                  checked={roleIds.includes(role.id)}
                  onCheckedChange={(checked) => toggleRole(role.id, checked === true)}
                  disabled={pending || locked}
                  className="mt-0.5"
                />
                <span className="flex min-w-0 flex-col gap-1">
                  <span className="flex flex-wrap items-center gap-1.5 font-medium">
                    {role.name}
                    {role.system && <Badge variant="secondary">Built-in</Badge>}
                    {locked && <span className="text-xs font-normal text-muted-foreground">(required for the root user)</span>}
                  </span>
                  <span className="flex flex-wrap gap-1">
                    {role.permissions.map((p) => (
                      <Badge key={p} variant="outline">
                        {PERMISSION_LABEL[p]}
                      </Badge>
                    ))}
                  </span>
                </span>
              </label>
            )
          })}
          {fieldErrors.roleIds && (
            <p role="alert" className="text-xs text-destructive">
              {fieldErrors.roleIds}
            </p>
          )}
        </fieldset>

        <label className="flex items-start gap-2.5 text-sm">
          <Checkbox checked={active} onCheckedChange={(checked) => setActive(checked === true)} disabled={pending || activeLocked} className="mt-0.5" />
          <span>
            Active
            <span className="block text-xs text-muted-foreground">
              {activeLocked
                ? user?.root
                  ? "The root user is always active."
                  : "You cannot deactivate your own account."
                : "A deactivated user cannot log in; open sessions end at once."}
            </span>
          </span>
        </label>
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
            {pending ? "Saving..." : creating ? "Add user" : "Save changes"}
          </Button>
          <Button type="button" variant="outline" onClick={onClose} disabled={pending}>
            Cancel
          </Button>
        </div>
      </SheetFooter>
    </form>
  )
}

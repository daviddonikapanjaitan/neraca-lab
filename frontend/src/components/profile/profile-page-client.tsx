"use client"

import { useRef, useState } from "react"
import { useRouter } from "next/navigation"
import {
  CircleAlertIcon,
  CircleCheckIcon,
  ImageUpIcon,
  LoaderIcon,
  LockIcon,
  SaveIcon,
  Trash2Icon,
} from "lucide-react"

import { FormField } from "@/components/form-field"
import { UserAvatar } from "@/components/user-avatar"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { jsonBody, RequestError, requestJson } from "@/lib/client-api"
import { formatTimestamp } from "@/lib/format"
import { formatBytes } from "@/lib/ingestion"
import { PERMISSION_LABEL } from "@/lib/permissions"
import type { User } from "@/lib/types"

/** Backend limit of a profile picture. */
const MAX_AVATAR_BYTES = 2 * 1024 * 1024
const AVATAR_TYPES = ["image/png", "image/jpeg", "image/webp", "image/gif"]

type Notice = { tone: "success" | "error"; text: string }

function NoticeLine({ notice }: { notice: Notice | null }) {
  if (!notice) return null
  const error = notice.tone === "error"
  return (
    <p
      role={error ? "alert" : "status"}
      className={error ? "flex items-start gap-2 text-xs text-destructive" : "flex items-start gap-2 text-xs text-emerald-600 dark:text-emerald-400"}
    >
      {error ? <CircleAlertIcon className="mt-px size-3.5 shrink-0" /> : <CircleCheckIcon className="mt-px size-3.5 shrink-0" />}
      <span>{notice.text}</span>
    </p>
  )
}

/** Profile: users change their address, phone, date of birth and picture; username and email are fixed. */
export function ProfilePageClient({ user }: { user: User }) {
  const router = useRouter()
  const fileInput = useRef<HTMLInputElement>(null)
  const [address, setAddress] = useState(user.address ?? "")
  const [phone, setPhone] = useState(user.phone ?? "")
  const [dob, setDob] = useState(user.dob ?? "")
  const [saving, setSaving] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [avatarPending, setAvatarPending] = useState(false)
  const [avatarNotice, setAvatarNotice] = useState<Notice | null>(null)

  const changed = address !== (user.address ?? "") || phone !== (user.phone ?? "") || dob !== (user.dob ?? "")

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (saving) return
    setSaving(true)
    setNotice(null)
    setFieldErrors({})
    try {
      await requestJson("/api/profile", jsonBody("PUT", { address: address || null, phone: phone || null, dob: dob || null }))
      setNotice({ tone: "success", text: "Profile saved." })
      router.refresh()
    } catch (e) {
      if (e instanceof RequestError && Object.keys(e.errors).length > 0) {
        setFieldErrors(e.errors)
        setNotice({ tone: "error", text: "Check the highlighted fields." })
      } else {
        setNotice({ tone: "error", text: e instanceof Error ? e.message : String(e) })
      }
    } finally {
      setSaving(false)
    }
  }

  async function uploadAvatar(file: File | undefined) {
    if (fileInput.current) fileInput.current.value = ""
    if (!file) return
    if (!AVATAR_TYPES.includes(file.type)) {
      setAvatarNotice({ tone: "error", text: "Choose a PNG, JPEG, WebP or GIF picture." })
      return
    }
    if (file.size > MAX_AVATAR_BYTES) {
      setAvatarNotice({ tone: "error", text: `The picture is ${formatBytes(file.size)}; the limit is ${formatBytes(MAX_AVATAR_BYTES)}.` })
      return
    }
    setAvatarPending(true)
    setAvatarNotice(null)
    try {
      const body = new FormData()
      body.append("file", file, file.name)
      await requestJson("/api/profile/avatar", { method: "PUT", body })
      setAvatarNotice({ tone: "success", text: "Picture updated." })
      router.refresh()
    } catch (e) {
      setAvatarNotice({ tone: "error", text: e instanceof Error ? e.message : String(e) })
    } finally {
      setAvatarPending(false)
    }
  }

  async function removeAvatar() {
    setAvatarPending(true)
    setAvatarNotice(null)
    try {
      await requestJson("/api/profile/avatar", { method: "DELETE" })
      setAvatarNotice({ tone: "success", text: "Picture removed." })
      router.refresh()
    } catch (e) {
      setAvatarNotice({ tone: "error", text: e instanceof Error ? e.message : String(e) })
    } finally {
      setAvatarPending(false)
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h1 className="text-xl font-semibold tracking-tight">Profile</h1>
        <p className="text-sm text-muted-foreground">Your account details. You can change your picture, address, phone and date of birth.</p>
      </div>

      <div className="grid items-start gap-4 lg:grid-cols-[320px_1fr]">
        <Card>
          <CardContent className="flex flex-col items-center gap-3 pt-2 text-center">
            <UserAvatar user={user} source="self" className="size-24 text-2xl" />
            <div>
              <div className="font-semibold">{user.fullName ?? user.username}</div>
              <div className="font-mono text-xs text-muted-foreground">{user.username}</div>
            </div>
            <input
              ref={fileInput}
              type="file"
              accept="image/png,image/jpeg,image/webp,image/gif"
              className="sr-only"
              onChange={(e) => uploadAvatar(e.target.files?.[0])}
              aria-label="Choose a profile picture"
            />
            <div className="flex flex-wrap justify-center gap-2">
              <Button size="sm" onClick={() => fileInput.current?.click()} disabled={avatarPending}>
                {avatarPending ? <LoaderIcon className="animate-spin" /> : <ImageUpIcon />}
                {user.hasAvatar ? "Change picture" : "Upload picture"}
              </Button>
              {user.hasAvatar && (
                <Button size="sm" variant="outline" onClick={removeAvatar} disabled={avatarPending}>
                  <Trash2Icon /> Remove
                </Button>
              )}
            </div>
            <p className="text-xs text-muted-foreground">PNG, JPEG, WebP or GIF, up to 2 MB.</p>
            <NoticeLine notice={avatarNotice} />

            <div className="flex w-full flex-col gap-2 border-t pt-3 text-left">
              <div className="text-xs font-medium text-muted-foreground">Roles</div>
              <div className="flex flex-wrap gap-1">
                {user.roles.length === 0 ? (
                  <span className="text-xs text-muted-foreground">No role</span>
                ) : (
                  user.roles.map((r) => (
                    <Badge key={r.id} variant={r.system ? "default" : "secondary"}>
                      {r.name}
                    </Badge>
                  ))
                )}
              </div>
              <div className="text-xs font-medium text-muted-foreground">Permissions</div>
              <div className="flex flex-wrap gap-1">
                {user.permissions.length === 0 ? (
                  <span className="text-xs text-muted-foreground">None</span>
                ) : (
                  user.permissions.map((p) => (
                    <Badge key={p} variant="outline">
                      {PERMISSION_LABEL[p]}
                    </Badge>
                  ))
                )}
              </div>
              <div className="text-xs text-muted-foreground">Member since {formatTimestamp(user.createdAt)}</div>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Details</CardTitle>
            <CardDescription>Username, email and name are managed by an administrator.</CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={save} className="flex flex-col gap-4" noValidate>
              <div className="grid gap-4 sm:grid-cols-2">
                <FormField id="profile-username" label="Username" hint={<Locked />}>
                  <Input id="profile-username" value={user.username} disabled readOnly />
                </FormField>
                <FormField id="profile-email" label="Email" hint={<Locked />}>
                  <Input id="profile-email" value={user.email} disabled readOnly />
                </FormField>
              </div>
              <FormField id="profile-fullname" label="Full name">
                <Input id="profile-fullname" value={user.fullName ?? ""} disabled readOnly />
              </FormField>
              <div className="grid gap-4 sm:grid-cols-2">
                <FormField id="profile-phone" label="Phone" error={fieldErrors.phone}>
                  <Input
                    id="profile-phone"
                    type="tel"
                    value={phone}
                    onChange={(e) => setPhone(e.target.value)}
                    disabled={saving}
                    aria-invalid={!!fieldErrors.phone}
                    placeholder="+62 812 3456 7890"
                  />
                </FormField>
                <FormField id="profile-dob" label="Date of birth" error={fieldErrors.dob}>
                  <Input
                    id="profile-dob"
                    type="date"
                    value={dob}
                    onChange={(e) => setDob(e.target.value)}
                    disabled={saving}
                    aria-invalid={!!fieldErrors.dob}
                  />
                </FormField>
              </div>
              <FormField id="profile-address" label="Address" error={fieldErrors.address}>
                <Textarea id="profile-address" value={address} onChange={(e) => setAddress(e.target.value)} disabled={saving} rows={3} />
              </FormField>
              <NoticeLine notice={notice} />
              <Button type="submit" className="self-start" disabled={saving || !changed}>
                {saving ? <LoaderIcon className="animate-spin" /> : <SaveIcon />}
                {saving ? "Saving..." : "Save profile"}
              </Button>
            </form>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}

function Locked() {
  return (
    <span className="inline-flex items-center gap-1">
      <LockIcon className="size-3" /> Cannot be changed
    </span>
  )
}

import { redirect } from "next/navigation"

/** /admin: the Admin Center opens on User Management. */
export default function AdminCenter() {
  redirect("/admin/users")
}

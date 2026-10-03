import type { Metadata } from "next"

import { ProfilePageClient } from "@/components/profile/profile-page-client"
import { requireUser } from "@/lib/api"

export const metadata: Metadata = {
  title: "Profile",
}

/** The own profile; every logged-in user may open it. */
export default async function Page() {
  const user = await requireUser()
  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      <ProfilePageClient user={user} />
    </div>
  )
}

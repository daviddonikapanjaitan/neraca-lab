import { redirect } from "next/navigation"

import { requireUser } from "@/lib/api"
import { homePath } from "@/lib/permissions"

/** The first page the user may open (companies, ingestion, admin center or the own profile). */
export default async function Home() {
  redirect(homePath(await requireUser()))
}

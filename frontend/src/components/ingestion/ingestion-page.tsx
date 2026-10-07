import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { IngestionPageClient, type IngestionSectionData } from "@/components/ingestion/ingestion-page-client"
import { ApiError, getIngestions, requireUser } from "@/lib/api"
import { JOB_LIMIT } from "@/lib/ingestion"
import { hasPermission, homePath } from "@/lib/permissions"
import type { IngestionJobList, User } from "@/lib/types"

async function load(
  section: () => Promise<IngestionSectionData>
): Promise<{ data: IngestionSectionData; jobs: IngestionJobList; error?: never } | { data?: never; jobs?: never; error: ApiError }> {
  try {
    const [data, jobs] = await Promise.all([section(), getIngestions(JOB_LIMIT)])
    return { data, jobs }
  } catch (error) {
    if (error instanceof ApiError) return { error }
    throw error
  }
}

/**
 * Server side of an Ingestion page (`INGESTION`): loads the page's own data and the jobs list (shown on every
 * Ingestion page), or shows the backend's error.
 */
export async function IngestionPage({ section }: { section: () => Promise<IngestionSectionData> }) {
  const user: User = await requireUser()
  if (!hasPermission(user, "INGESTION")) {
    return <AccessDenied permission="INGESTION" homeHref={homePath(user)} />
  }

  const { data, jobs, error } = await load(section)
  if (error) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 p-4 pt-0">
        <ApiErrorState error={error} backHref={homePath(user)} backLabel="Back" />
      </div>
    )
  }
  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      <IngestionPageClient {...data} jobs={jobs} />
    </div>
  )
}

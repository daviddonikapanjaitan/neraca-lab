import type { Metadata } from "next"
import { notFound } from "next/navigation"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { CompanyDetailView } from "@/components/company/company-detail-view"
import { ApiError, getCompanyDetail, getCompanyProfile, getCompanyScorecards, requireUser } from "@/lib/api"
import type { CompanyProfile } from "@/lib/company-profile"
import type { Scorecards } from "@/lib/scorecards"
import { companiesHref } from "@/lib/links"
import { hasPermission, homePath } from "@/lib/permissions"
import { isDetailTab } from "@/lib/tabs"
import type { CompanyDetail } from "@/lib/types"

type Params = Promise<{ exchange: string; ticker: string }>
type SearchParams = Promise<{ [key: string]: string | string[] | undefined }>

/** Route params may arrive percent-encoded; decode once so the API client encodes exactly once. */
function decode(segment: string): string {
  try {
    return decodeURIComponent(segment)
  } catch {
    return segment
  }
}

async function load(params: Params): Promise<
  { detail: CompanyDetail; error?: never } | { detail?: never; error: ApiError }
> {
  const { exchange, ticker } = await params
  try {
    return { detail: await getCompanyDetail(decode(exchange), decode(ticker)) }
  } catch (error) {
    if (error instanceof ApiError) return { error }
    throw error
  }
}

/** The data of a tab loaded beside the detail: a failure shows on that tab only. */
type TabData<T> = { data: T; error?: never } | { data?: never; error: string }

async function tabData<T>(load: () => Promise<T>): Promise<TabData<T>> {
  try {
    return { data: await load() }
  } catch (error) {
    if (error instanceof ApiError) return { error: error.detail || error.title }
    throw error
  }
}

export async function generateMetadata({ params }: { params: Params }): Promise<Metadata> {
  const { detail } = await load(params)
  if (!detail) {
    const { ticker } = await params
    return { title: decode(ticker).toUpperCase() }
  }
  return {
    title: `${detail.company.ticker} · ${detail.company.companyName}`,
    description: `Financial statements, segments, metrics and valuations of ${detail.company.legalName ?? detail.company.companyName}.`,
  }
}

export default async function Page({ params, searchParams }: { params: Params; searchParams: SearchParams }) {
  const user = await requireUser()
  if (!hasPermission(user, "COMPANIES")) {
    return <AccessDenied permission="COMPANIES" homeHref={homePath(user)} />
  }

  const { detail, error } = await load(params)

  if (error?.status === 404) notFound()

  if (error) {
    const { exchange } = await params
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 p-4 pt-0">
        <ApiErrorState error={error} backHref={error.status === 400 ? "/companies" : companiesHref(decode(exchange).toUpperCase())} />
      </div>
    )
  }

  const { tab: rawTab } = await searchParams
  const tab = Array.isArray(rawTab) ? rawTab[0] : rawTab
  const { exchange, ticker } = detail.company
  const [profile, scorecards] = await Promise.all([
    tabData<CompanyProfile>(() => getCompanyProfile(exchange, ticker)),
    tabData<Scorecards>(() => getCompanyScorecards(exchange, ticker)),
  ])

  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      <CompanyDetailView
        detail={detail}
        profile={profile}
        scorecards={scorecards}
        initialTab={isDetailTab(tab) ? tab : "overview"}
      />
    </div>
  )
}

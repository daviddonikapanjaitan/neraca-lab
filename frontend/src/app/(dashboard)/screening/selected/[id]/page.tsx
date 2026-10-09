import type { Metadata } from "next"

import { reportMetadata, ScreeningReportPage, type ReportParams } from "@/components/screening/report-page"

export async function generateMetadata({ params }: { params: ReportParams }): Promise<Metadata> {
  return reportMetadata(params)
}

/** Screening > Selected Stocks > Report: the report of a screening of selected stocks. */
export default async function Page({ params }: { params: ReportParams }) {
  return <ScreeningReportPage params={params} scope="SELECTION" />
}

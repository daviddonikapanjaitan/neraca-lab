import type { Metadata } from "next"

import {
  TechnicalReportPage,
  technicalReportMetadata,
  type TechnicalReportParams,
} from "@/components/technical-screening/report-page"

export async function generateMetadata({ params }: { params: TechnicalReportParams }): Promise<Metadata> {
  return technicalReportMetadata(params)
}

/** Screening > Technicals (Selected) > Report: the report of a technical screening of selected stocks. */
export default async function Page({ params }: { params: TechnicalReportParams }) {
  return <TechnicalReportPage params={params} scope="SELECTION" />
}

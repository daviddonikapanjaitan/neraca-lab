// The Ingestion pages, shared by the sidebar dropdown, the breadcrumb and the pages themselves.

import type { IngestionJobType } from "@/lib/types"

export type IngestionSection = "xbrl" | "prices" | "screening-data" | "rag-pdf" | "rag-excel" | "rag-news" | "macro"

export interface IngestionSectionInfo {
  section: IngestionSection
  /** sidebar entry and breadcrumb */
  title: string
  /** page heading */
  heading: string
  description: string
  href: string
  /** job type the page's jobs table opens on */
  jobType: IngestionJobType
}

export const INGESTION_SECTIONS: IngestionSectionInfo[] = [
  {
    section: "xbrl",
    title: "IDX XBRL",
    heading: "IDX XBRL Ingestion",
    description:
      "Upload IDX XBRL financial statements (.xlsx); the AI agent stores and verifies every statement.",
    href: "/ingestion/xbrl",
    jobType: "FINANCIAL_STATEMENT",
  },
  {
    section: "prices",
    title: "Price Ingestion",
    heading: "Price Ingestion",
    description:
      "Fetch daily prices of one company, a Forex pair or a commodity, bring every company of an exchange (or every pair / commodity) up to today, and ingest the IHSG index; market and valuation data are recalculated afterwards.",
    href: "/ingestion/prices",
    jobType: "PRICE",
  },
  {
    section: "screening-data",
    title: "Screening Data IDX",
    heading: "Screening Data IDX",
    description:
      "Refresh the market data of every IDX listing that the AI screening starts from, and the daily price history of every listing for the technical screening.",
    href: "/ingestion/screening-data",
    jobType: "FUNDAMENTALS",
  },
  {
    section: "rag-pdf",
    title: "PDF Documents (RAG)",
    heading: "PDF Documents (RAG)",
    description:
      "Upload PDF documents of a company (financial statements, annual reports) or macro documents of a country (central bank, finance ministry, IMF reports) for Macro › AI Analysis; their text is chunked, embedded and stored in the pgvector store for RAG.",
    href: "/ingestion/rag-pdf",
    jobType: "RAG_PDF",
  },
  {
    section: "rag-excel",
    title: "Excel Documents (RAG)",
    heading: "Excel Documents (RAG)",
    description:
      "Upload Excel documents of an exchange: for IDX, KSEI's daily list of the shareholders of more than 5% (Ownership (>=5%)). Every investor, its accounts and its buys and sells are stored for Companies › Ownership (>=5%), and each stored company's ownership is chunked, embedded and stored in the pgvector store for the AI analysis.",
    href: "/ingestion/rag-excel",
    jobType: "RAG_XLSX",
  },
  {
    section: "rag-news",
    title: "News (RAG)",
    heading: "News (RAG)",
    description:
      "Collect the news of a listed IDX company for a date range; every article is chunked, embedded and stored in the pgvector store for RAG.",
    href: "/ingestion/rag-news",
    jobType: "RAG_NEWS",
  },
  {
    section: "macro",
    title: "Macro",
    heading: "Macro Data",
    description:
      "Collect the macro data of the Druckenmiller and Paul Tudor Jones views (rates, inflation, liquidity, credit, the dollar, flows, positioning) from FRED, Bank Indonesia, CNBC, the CFTC and Yahoo Finance; Companies › Technicals turns them into the liquidity and risk regimes.",
    href: "/ingestion/macro",
    jobType: "MACRO",
  },
]

/** /ingestion opens the first page. */
export const INGESTION_HOME = INGESTION_SECTIONS[0].href

export function ingestionSection(section: IngestionSection): IngestionSectionInfo {
  return INGESTION_SECTIONS.find((s) => s.section === section)!
}

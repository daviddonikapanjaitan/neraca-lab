// IDX company profiles: the upload card of Ingestion > Screening Data IDX and the Profile tab of a company
// (backend CompanyProfileController / CompanyProfileViews).

/** What an upload did (POST /api/v1/companies/profiles/upload). */
export type ProfileUploadResult = {
  uploadId: number | null
  fileName: string | null
  /** the same file was uploaded before: nothing was written, the earlier result is shown */
  duplicate: boolean
  uploadedAt: string | null
  uploadedBy: string | null
  profiles: number
  details: number
  updated: number
  unchanged: number
  listingDates: number
  dividends: number
  unknownTickers: string[]
  conflicts: { ticker: string; field: string; stored: string; file: string; note: string }[]
  checks: {
    ticker: string
    listingDate: string
    firstPriceDate: string | null
    yahooFirstTradeDate: string | null
    note: string
  }[]
  warnings: string[]
}

/** GET /api/v1/companies/profiles/status?exchange= */
export type ProfileStatus = {
  exchange: string
  companies: number
  active: number
  withListingDate: number
  withBoard: number
  withIdxSector: number
  withIdxDividends: number
  idxDividends: number
  lastProfileAt: string | null
  lastUpload: ProfileUploadResult | null
  /** first 50 companies without a listing date, active first */
  missing: {
    ticker: string
    companyName: string
    active: boolean
    firstPriceDate: string | null
    yahooFirstTradeDate: string | null
  }[]
  missingTotal: number
}

export type DividendSource = "IDX" | "YAHOO" | "IDX_YAHOO"

export type DividendRow = {
  exDate: string | null
  cumDate: string | null
  recordingDate: string | null
  paymentDate: string | null
  fiscalYear: number | null
  type: string | null
  source: DividendSource
  /** as announced (IDX), or Yahoo's amount on the share basis of its date */
  cashPerShare: number
  currency: string
  /** Yahoo's stored amount (today's share basis) */
  yahooPerShare: number | null
  /** new shares per old share of the splits since the dividend */
  splitFactor: number
  priceDate: string | null
  /** the cum-date close in the listing currency, on the share basis of the dividend's date */
  price: number | null
  perShareListing: number | null
  dividendYield: number | null
  totalAmount: number | null
  totalCurrency: string | null
}

export type DividendYear = {
  fiscalYear: number
  periodEnd: string | null
  netIncomeToParent: number | null
  basicEps: number | null
  dividendsPaid: number | null
  cashPayout: number | null
  declaredPerShare: number | null
  declaredCurrency: string | null
  declaredTotal: number | null
  declaredPayout: number | null
  declaredCount: number
}

/** GET /api/v1/companies/{exchange}/{ticker}/profile */
export type CompanyProfile = {
  exchange: string
  ticker: string
  /** reporting currency (statements, payout ratios) */
  currency: string
  /** currency the shares trade in (prices, dividends per share, yields) */
  listingCurrency: string
  profile: {
    companyName: string
    legalName: string | null
    idxName: string | null
    listingDate: string | null
    yearsListed: number | null
    listingBoard: string | null
    idxSector: string | null
    idxSubSector: string | null
    idxIndustry: string | null
    idxSubIndustry: string | null
    businessActivity: string | null
    shareRegistrar: string | null
    sector: string | null
    industry: string | null
    country: string | null
    fiscalYearEnd: string | null
    firstPriceDate: string | null
    yahooFirstTradeDate: string | null
    profileUpdatedAt: string | null
  }
  summary: {
    trailingPerShare: number | null
    trailingCount: number
    priceDate: string | null
    price: number | null
    trailingYield: number | null
    latestPayout: number | null
    latestPayoutYear: number | null
    averagePayout: number | null
    averagePayoutYears: number
    idxDividends: number
    yahooDividends: number
    /** trailing EPS of the latest valuation, listing currency */
    epsTtm: number | null
    epsDate: string | null
    /** trailingPerShare / epsTtm */
    trailingPayout: number | null
  }
  dividends: DividendRow[]
  years: DividendYear[]
  notes: string[]
}

const BOARDS: Record<string, string> = {
  MAIN: "Main Board (Utama)",
  DEVELOPMENT: "Development Board (Pengembangan)",
  ACCELERATION: "Acceleration Board (Akselerasi)",
  NEW_ECONOMY: "New Economy Board (Ekonomi Baru)",
  WATCHLIST: "Watchlist Board (Pemantauan Khusus)",
}

/** MAIN -> "Main Board (Utama)"; another code as filed. */
export function boardLabel(code: string | null | undefined): string | null {
  if (!code) return null
  return BOARDS[code] ?? code.replace(/_/g, " ")
}

export const DIVIDEND_SOURCE_LABELS: Record<DividendSource, string> = {
  IDX: "IDX",
  YAHOO: "Yahoo",
  IDX_YAHOO: "IDX · Yahoo",
}

export const IDX_HOME = "https://www.idx.co.id/id/perusahaan-tercatat/profil-perusahaan-tercatat/"

/**
 * Collects the IDX company profiles in a browser session on idx.co.id (the backend cannot reach it: Cloudflare) and
 * saves them as one JSON file for the upload: the list (GetCompanyProfiles: listing date, board, IDX-IC sector) and,
 * one request per company, paced, the detail (GetCompanyProfilesDetail: the dividend history). Paste it into the
 * DevTools console (F12 > Console) of a tab on www.idx.co.id. About 8 minutes for ~950 companies; set DETAILS to false
 * for the list only (a few seconds).
 */
export const IDX_PROFILE_COLLECTOR = `// Neraca Lab - IDX company profiles collector. Run in the DevTools console of a www.idx.co.id tab.
(async () => {
  const DETAILS = true // false: the list only (listing date, board, sector), no dividends
  const PAUSE_MS = 400 // between two detail requests
  const base = "/primary/ListedCompany"
  const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
  async function get(url, tries = 3) {
    for (let attempt = 1; ; attempt++) {
      const response = await fetch(url, { headers: { Accept: "application/json" }, credentials: "include" })
      if (response.ok) return response.json()
      if (attempt >= tries) throw new Error("HTTP " + response.status + " for " + url)
      await pause(3000 * attempt)
    }
  }
  const list = await get(base + "/GetCompanyProfiles?emitenType=s&start=0&length=9999")
  const rows = Array.isArray(list) ? list : list.data || []
  const tickers = [...new Set(rows.map((r) => String(r.KodeEmiten || "").trim()).filter(Boolean))]
  console.log("IDX profiles: " + tickers.length + " companies in the list")
  const details = {}
  const failed = []
  if (DETAILS) {
    for (const [i, ticker] of tickers.entries()) {
      try {
        details[ticker] = await get(base + "/GetCompanyProfilesDetail?KodeEmiten=" + encodeURIComponent(ticker))
      } catch (e) {
        failed.push(ticker)
      }
      if ((i + 1) % 25 === 0 || i + 1 === tickers.length) console.log("IDX profiles: details " + (i + 1) + " / " + tickers.length)
      await pause(PAUSE_MS)
    }
  }
  const file = { source: "idx.co.id", collectedAt: new Date().toISOString(), profiles: list, details, failed }
  const link = document.createElement("a")
  link.href = URL.createObjectURL(new Blob([JSON.stringify(file)], { type: "application/json" }))
  link.download = "company-profiles-" + new Date().toISOString().slice(0, 10) + ".json"
  document.body.appendChild(link)
  link.click()
  link.remove()
  console.log("IDX profiles: saved " + link.download + " - " + tickers.length + " companies, " + Object.keys(details).length + " details" + (failed.length ? ", failed: " + failed.join(", ") : ""))
})()
`

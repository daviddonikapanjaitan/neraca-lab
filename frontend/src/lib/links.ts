/** /companies/IDX/HRTA */
export function companyHref(exchange: string, ticker: string): string {
  return `/companies/${encodeURIComponent(exchange)}/${encodeURIComponent(ticker)}`
}

/** Tickers of an exchange's index (charted on Technicals; no company page) */
export const INDEX_TICKERS = new Set(["IHSG"])

/** /companies/technicals?exchange=IDX&ticker=IHSG */
export function technicalsHref(exchange: string, ticker: string): string {
  return `/companies/technicals?exchange=${encodeURIComponent(exchange)}&ticker=${encodeURIComponent(ticker)}`
}

/** /companies?exchange=IDX */
export function companiesHref(exchange: string): string {
  return `/companies?exchange=${encodeURIComponent(exchange)}`
}

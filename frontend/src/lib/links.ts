/** /companies/IDX/HRTA */
export function companyHref(exchange: string, ticker: string): string {
  return `/companies/${encodeURIComponent(exchange)}/${encodeURIComponent(ticker)}`
}

/** /companies?exchange=IDX */
export function companiesHref(exchange: string): string {
  return `/companies?exchange=${encodeURIComponent(exchange)}`
}

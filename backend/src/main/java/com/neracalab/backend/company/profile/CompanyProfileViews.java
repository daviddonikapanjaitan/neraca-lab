package com.neracalab.backend.company.profile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Responses of the IDX company profile APIs. */
public final class CompanyProfileViews {

    private CompanyProfileViews() {
    }

    // ------------------------------------------------------------------ upload

    /**
     * What an upload did ({@code POST /api/v1/companies/profiles/upload}).
     *
     * @param duplicate      the same file was uploaded before: nothing was written, the earlier result is returned
     * @param profiles       companies of the file with a profile row
     * @param details        companies of the file with a detail response (their IDX dividends were replaced)
     * @param updated        known companies whose profile or dividends changed
     * @param unchanged      known companies already as in the file
     * @param listingDates   listing dates written (companies that had none)
     * @param dividends      cash dividends stored from the detail responses
     * @param unknownTickers tickers of the file that are not companies of the exchange (never created)
     * @param conflicts      stored values the file contradicts and that were kept (a listing date)
     * @param checks         listing dates that do not fit the stored prices or Yahoo's first trade date (written,
     *                       for information)
     */
    public record UploadResult(Long uploadId, String fileName, boolean duplicate, OffsetDateTime uploadedAt,
                               String uploadedBy, int profiles, int details, int updated, int unchanged,
                               int listingDates, int dividends, List<String> unknownTickers, List<Conflict> conflicts,
                               List<ListingCheck> checks, List<String> warnings) {

        public UploadResult asDuplicate() {
            return new UploadResult(uploadId, fileName, true, uploadedAt, uploadedBy, profiles, details, updated,
                    unchanged, listingDates, dividends, unknownTickers, conflicts, checks, warnings);
        }
    }

    /** A stored value the file contradicts (kept). */
    public record Conflict(String ticker, String field, String stored, String file, String note) {
    }

    /**
     * A listing date that does not fit the other dates: the first stored price is before it (a relisting, a ticker
     * reused, or a wrong date), or Yahoo's first trade date is before it.
     */
    public record ListingCheck(String ticker, LocalDate listingDate, LocalDate firstPriceDate,
                               LocalDate yahooFirstTradeDate, String note) {
    }

    // ------------------------------------------------------------------ status

    /** The IDX company profiles card of Ingestion > Screening Data IDX. */
    public record Status(String exchange, int companies, int active, int withListingDate, int withBoard,
                         int withIdxSector, int withIdxDividends, int idxDividends, OffsetDateTime lastProfileAt,
                         UploadResult lastUpload, List<MissingListing> missing, int missingTotal) {
    }

    public record MissingListing(String ticker, String companyName, boolean active, LocalDate firstPriceDate,
                                 LocalDate yahooFirstTradeDate) {
    }

    // ------------------------------------------------------------------ company page

    /**
     * The Profile tab of a company ({@code GET /api/v1/companies/{exchange}/{ticker}/profile}).
     *
     * @param currency        the company's reporting currency (statement amounts)
     * @param listingCurrency the currency its shares trade in (prices, per-share dividends, yields)
     */
    public record CompanyProfile(String exchange, String ticker, String currency, String listingCurrency,
                                 Profile profile, DividendSummary summary, List<DividendRow> dividends,
                                 List<DividendYear> years, List<String> notes) {
    }

    /**
     * @param listingDate      {@code company.ipo_date} (the IDX listing date once a profile is uploaded)
     * @param listingBoard     MAIN, DEVELOPMENT, ACCELERATION, NEW_ECONOMY, WATCHLIST, or as filed
     * @param firstPriceDate   oldest stored daily price
     * @param yahooFirstTradeDate Yahoo Finance's first trade date (screening data), a cross-check only
     * @param sector           the company's sector of the company list (Yahoo's scheme)
     */
    public record Profile(String companyName, String legalName, String idxName, LocalDate listingDate,
                          Integer yearsListed, String listingBoard, String idxSector, String idxSubSector,
                          String idxIndustry, String idxSubIndustry, String businessActivity, String shareRegistrar,
                          String sector, String industry, String country, LocalDate fiscalYearEnd,
                          LocalDate firstPriceDate, LocalDate yahooFirstTradeDate, OffsetDateTime profileUpdatedAt) {
    }

    /**
     * @param trailingPerShare   dividends per share with an ex date in the 12 months to {@code priceDate}, in the listing
     *                           currency, on today's share basis (after later splits)
     * @param trailingCount      dividends in that sum
     * @param price              last close in the listing currency
     * @param trailingYield      trailingPerShare / price
     * @param latestPayout       the latest fiscal year's cash payout ratio (dividends paid / profit to owners)
     * @param latestPayoutYear   its fiscal year
     * @param averagePayout      mean cash payout ratio of the last (up to) five fiscal years with a profit
     * @param averagePayoutYears the years in that mean
     * @param epsTtm             trailing-twelve-month EPS of the latest valuation snapshot, in the listing currency
     * @param epsDate            that snapshot's date
     * @param trailingPayout     trailingPerShare / epsTtm: the payout of the owners' profit per share, unlike the cash
     *                           payout not inflated by dividends a subsidiary pays its minority shareholders; null
     *                           without a positive EPS
     */
    public record DividendSummary(BigDecimal trailingPerShare, int trailingCount, LocalDate priceDate,
                                  BigDecimal price, BigDecimal trailingYield, BigDecimal latestPayout,
                                  Integer latestPayoutYear, BigDecimal averagePayout, int averagePayoutYears,
                                  int idxDividends, int yahooDividends, BigDecimal epsTtm, LocalDate epsDate,
                                  BigDecimal trailingPayout) {
    }

    /**
     * One cash dividend, latest first. {@code cashPerShare} is the amount as announced (IDX) or Yahoo's amount brought
     * back onto the share basis of its date ({@code yahooPerShare} x {@code splitFactor}); the price is the close of
     * the cum date (the last day with the right to the dividend), also on the basis of its date, so the yield holds
     * whatever splits came later.
     *
     * @param source        IDX, YAHOO or IDX_YAHOO (both know it: ex dates at most 3 days apart)
     * @param currency      currency of cashPerShare
     * @param yahooPerShare Yahoo's stored amount (split-adjusted to today's basis, listing currency)
     * @param splitFactor   new shares per old share of the splits since the dividend (1 without any)
     * @param priceDate     the cum-date close's trading day
     * @param price         that close in the listing currency, on the basis of the dividend's date
     * @param perShareListing cashPerShare in the listing currency (converted at the rate of the price day when the
     *                      dividend is declared in another currency)
     * @param dividendYield perShareListing / price
     */
    public record DividendRow(LocalDate exDate, LocalDate cumDate, LocalDate recordingDate, LocalDate paymentDate,
                              Integer fiscalYear, String type, String source, BigDecimal cashPerShare, String currency,
                              BigDecimal yahooPerShare, BigDecimal splitFactor, LocalDate priceDate, BigDecimal price,
                              BigDecimal perShareListing, BigDecimal dividendYield, BigDecimal totalAmount,
                              String totalCurrency) {
    }

    /**
     * Dividends of a fiscal year against its profit (reporting currency).
     *
     * @param dividendsPaid    dividends paid in the year's cash flow statement (positive; may include those a
     *                         subsidiary paid to its minority shareholders)
     * @param cashPayout       dividendsPaid / netIncomeToParent (null without a profit)
     * @param declaredPerShare IDX: the dividends declared from the year's profit (TahunBuku), per share as announced,
     *                         in declaredCurrency
     * @param declaredTotal    IDX: their total in the reporting currency
     * @param declaredPayout   declaredTotal / netIncomeToParent; else declaredPerShare / basicEps (same currency)
     * @param declaredCount    IDX dividends of the year
     */
    public record DividendYear(int fiscalYear, LocalDate periodEnd, BigDecimal netIncomeToParent, BigDecimal basicEps,
                               BigDecimal dividendsPaid, BigDecimal cashPayout, BigDecimal declaredPerShare,
                               String declaredCurrency, BigDecimal declaredTotal, BigDecimal declaredPayout,
                               int declaredCount) {
    }
}

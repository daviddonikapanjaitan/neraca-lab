package com.neracalab.backend.company.profile;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.neracalab.backend.company.profile.IdxProfileParser.Dividend;

/** Reads and writes of the IDX company profiles: company columns, {@code company_dividend}, uploads. */
@Repository
public class CompanyProfileRepository {

    /** The stored profile of a company, before an upload changes it. */
    public record StoredProfile(long companyId, String ticker, String legalName, LocalDate ipoDate, String listingBoard,
                                String idxSector, String idxSubSector, String idxIndustry, String idxSubIndustry,
                                String businessActivity, String shareRegistrar, String idxName, LocalDate firstPriceDate,
                                LocalDate yahooFirstTradeDate) {
    }

    /** A stored upload. */
    public record Upload(long uploadId, String fileName, String sha256, int profiles, int details, int updated,
                         int unchanged, int dividends, String result, String uploadedBy, OffsetDateTime uploadedAt) {
    }

    /** A company of the exchange without a listing date. */
    public record MissingListing(String ticker, String companyName, boolean active, LocalDate firstPriceDate,
                                 LocalDate yahooFirstTradeDate) {
    }

    /** @param missing companies of the exchange (active or not) without a listing date */
    public record Counts(int companies, int active, int withListingDate, int withBoard, int withIdxSector,
                         int withIdxDividends, int idxDividends, OffsetDateTime lastProfileAt, int missing) {
    }

    private final JdbcClient jdbc;

    public CompanyProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ upload

    /** Every company of the exchange by ticker, with its first price day and Yahoo's first trade date. */
    public Map<String, StoredProfile> profiles(String exchange) {
        Map<String, StoredProfile> out = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT c.company_id, c.ticker, c.legal_name, c.ipo_date, c.listing_board, c.idx_sector,
                               c.idx_sub_sector, c.idx_industry, c.idx_sub_industry, c.business_activity,
                               c.share_registrar, c.idx_name,
                               (SELECT min(p.trading_date) FROM price_daily p WHERE p.company_id = c.company_id)
                                   AS first_price_date,
                               (SELECT l.first_trade_date FROM stock_listing l
                                WHERE l.exchange = c.exchange AND l.ticker = c.ticker) AS yahoo_first_trade
                        FROM company c WHERE c.exchange = :exchange""")
                .param("exchange", exchange)
                .query((rs, i) -> new StoredProfile(rs.getLong("company_id"), rs.getString("ticker"),
                        rs.getString("legal_name"), date(rs, "ipo_date"), rs.getString("listing_board"),
                        rs.getString("idx_sector"), rs.getString("idx_sub_sector"), rs.getString("idx_industry"),
                        rs.getString("idx_sub_industry"), rs.getString("business_activity"),
                        rs.getString("share_registrar"), rs.getString("idx_name"), date(rs, "first_price_date"),
                        date(rs, "yahoo_first_trade")))
                .list()
                .forEach(p -> out.put(p.ticker(), p));
        return out;
    }

    /**
     * Writes the profile columns; {@code ipoDate} only when the stored one is empty (a different date is a conflict the
     * service reports). A null value keeps the stored one (a file without that field never clears it).
     */
    public void updateProfile(long companyId, LocalDate ipoDate, String board, String sector, String subSector,
                              String industry, String subIndustry, String businessActivity, String shareRegistrar,
                              String idxName) {
        jdbc.sql("""
                        UPDATE company SET
                            ipo_date          = COALESCE(ipo_date, :ipo),
                            listing_board     = COALESCE(:board, listing_board),
                            idx_sector        = COALESCE(:sector, idx_sector),
                            idx_sub_sector    = COALESCE(:subSector, idx_sub_sector),
                            idx_industry      = COALESCE(:industry, idx_industry),
                            idx_sub_industry  = COALESCE(:subIndustry, idx_sub_industry),
                            business_activity = COALESCE(:activity, business_activity),
                            share_registrar   = COALESCE(:registrar, share_registrar),
                            idx_name          = COALESCE(:name, idx_name),
                            profile_updated_at = now(),
                            updated_at        = now()
                        WHERE company_id = :c""")
                .param("c", companyId).param("ipo", ipoDate).param("board", board).param("sector", cut(sector, 150))
                .param("subSector", cut(subSector, 150)).param("industry", cut(industry, 150))
                .param("subIndustry", cut(subIndustry, 150)).param("activity", businessActivity)
                .param("registrar", cut(shareRegistrar, 255)).param("name", cut(idxName, 255))
                .update();
    }

    /** The company's stored IDX dividends, in a comparable form. */
    public List<Dividend> idxDividends(long companyId) {
        return jdbc.sql("""
                        SELECT fiscal_year, dividend_type, cash_per_share, currency, cum_date, ex_date, recording_date,
                               payment_date, total_amount, total_currency
                        FROM company_dividend WHERE company_id = :c AND source = 'idx'
                        ORDER BY ex_date NULLS LAST, cum_date NULLS LAST, dividend_id""")
                .param("c", companyId)
                .query((rs, i) -> new Dividend(rs.getObject("fiscal_year", Integer.class), rs.getString("dividend_type"),
                        strip(rs.getBigDecimal("cash_per_share")), rs.getString("currency"), date(rs, "cum_date"),
                        date(rs, "ex_date"), date(rs, "recording_date"), date(rs, "payment_date"),
                        strip(rs.getBigDecimal("total_amount")), rs.getString("total_currency")))
                .list();
    }

    /** Replaces the company's IDX dividends by those of a detail response (its whole history). */
    public void replaceDividends(long companyId, long uploadId, List<Dividend> dividends) {
        jdbc.sql("DELETE FROM company_dividend WHERE company_id = :c AND source = 'idx'").param("c", companyId).update();
        for (Dividend d : dividends) {
            jdbc.sql("""
                            INSERT INTO company_dividend (company_id, source, fiscal_year, dividend_type, cash_per_share,
                                                          currency, cum_date, ex_date, recording_date, payment_date,
                                                          total_amount, total_currency, upload_id)
                            VALUES (:c, 'idx', :year, :type, :perShare, :currency, :cum, :ex, :recording, :payment,
                                    :total, :totalCurrency, :upload)
                            ON CONFLICT ON CONSTRAINT uq_company_dividend DO NOTHING""")
                    .param("c", companyId).param("year", d.fiscalYear()).param("type", cut(d.type(), 40))
                    .param("perShare", d.cashPerShare()).param("currency", d.currency()).param("cum", d.cumDate())
                    .param("ex", d.exDate()).param("recording", d.recordingDate()).param("payment", d.paymentDate())
                    .param("total", d.totalAmount()).param("totalCurrency", d.totalCurrency()).param("upload", uploadId)
                    .update();
        }
    }

    public Optional<Upload> upload(String exchange, String sha256) {
        return jdbc.sql("SELECT * FROM company_profile_upload WHERE exchange = :e AND file_sha256 = :sha")
                .param("e", exchange).param("sha", sha256).query(this::upload).optional();
    }

    public Optional<Upload> lastUpload(String exchange) {
        return jdbc.sql("""
                        SELECT * FROM company_profile_upload WHERE exchange = :e
                        ORDER BY uploaded_at DESC, upload_id DESC LIMIT 1""")
                .param("e", exchange).query(this::upload).optional();
    }

    /** Inserts the upload row (counts filled later by {@link #finishUpload}). */
    public long startUpload(String exchange, String fileName, String sha256, String user) {
        return jdbc.sql("""
                        INSERT INTO company_profile_upload (exchange, file_name, file_sha256, profiles, details, updated,
                                                            unchanged, dividends, result, uploaded_by)
                        VALUES (:e, :name, :sha, 0, 0, 0, 0, 0, '{}'::jsonb, :user)
                        RETURNING upload_id""")
                .param("e", exchange).param("name", cut(fileName, 255)).param("sha", sha256).param("user", cut(user, 100))
                .query(Long.class).single();
    }

    public void finishUpload(long uploadId, int profiles, int details, int updated, int unchanged, int dividends,
                             String resultJson) {
        jdbc.sql("""
                        UPDATE company_profile_upload SET profiles = :p, details = :d, updated = :u, unchanged = :n,
                               dividends = :div, result = CAST(:result AS jsonb)
                        WHERE upload_id = :id""")
                .param("id", uploadId).param("p", profiles).param("d", details).param("u", updated)
                .param("n", unchanged).param("div", dividends).param("result", resultJson).update();
    }

    // ------------------------------------------------------------------ status

    public Counts counts(String exchange) {
        return jdbc.sql("""
                        SELECT count(*) AS companies,
                               count(*) FILTER (WHERE c.active) AS active,
                               count(*) FILTER (WHERE c.active AND c.ipo_date IS NOT NULL) AS with_listing,
                               count(*) FILTER (WHERE c.active AND c.listing_board IS NOT NULL) AS with_board,
                               count(*) FILTER (WHERE c.active AND c.idx_sector IS NOT NULL) AS with_sector,
                               (SELECT count(DISTINCT d.company_id) FROM company_dividend d
                                JOIN company x ON x.company_id = d.company_id
                                WHERE x.exchange = :e AND d.source = 'idx') AS with_dividends,
                               (SELECT count(*) FROM company_dividend d
                                JOIN company x ON x.company_id = d.company_id
                                WHERE x.exchange = :e AND d.source = 'idx') AS dividends,
                               max(c.profile_updated_at) AS last_profile_at,
                               count(*) FILTER (WHERE c.ipo_date IS NULL) AS missing
                        FROM company c WHERE c.exchange = :e""")
                .param("e", exchange)
                .query((rs, i) -> new Counts(rs.getInt("companies"), rs.getInt("active"), rs.getInt("with_listing"),
                        rs.getInt("with_board"), rs.getInt("with_sector"), rs.getInt("with_dividends"),
                        rs.getInt("dividends"), rs.getObject("last_profile_at", OffsetDateTime.class),
                        rs.getInt("missing")))
                .single();
    }

    /** Active companies first, then by ticker. */
    public List<MissingListing> missingListingDates(String exchange, int limit) {
        return jdbc.sql("""
                        SELECT c.ticker, c.company_name, c.active,
                               (SELECT min(p.trading_date) FROM price_daily p WHERE p.company_id = c.company_id)
                                   AS first_price_date,
                               (SELECT l.first_trade_date FROM stock_listing l
                                WHERE l.exchange = c.exchange AND l.ticker = c.ticker) AS yahoo_first_trade
                        FROM company c WHERE c.exchange = :e AND c.ipo_date IS NULL
                        ORDER BY c.active DESC, c.ticker LIMIT :limit""")
                .param("e", exchange).param("limit", limit)
                .query((rs, i) -> new MissingListing(rs.getString("ticker"), rs.getString("company_name"),
                        rs.getBoolean("active"), date(rs, "first_price_date"), date(rs, "yahoo_first_trade")))
                .list();
    }

    // ------------------------------------------------------------------ helpers

    private Upload upload(ResultSet rs, int i) throws SQLException {
        return new Upload(rs.getLong("upload_id"), rs.getString("file_name"), rs.getString("file_sha256"),
                rs.getInt("profiles"), rs.getInt("details"), rs.getInt("updated"), rs.getInt("unchanged"),
                rs.getInt("dividends"), rs.getString("result"), rs.getString("uploaded_by"),
                rs.getObject("uploaded_at", OffsetDateTime.class));
    }

    static boolean same(Object a, Object b) {
        return Objects.equals(a, b);
    }

    private static BigDecimal strip(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }
}

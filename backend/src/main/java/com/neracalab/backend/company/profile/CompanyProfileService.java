package com.neracalab.backend.company.profile;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.company.CompanyService.CompanyNotFoundException;
import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.company.Tickers;
import com.neracalab.backend.company.profile.CompanyProfileRepository.StoredProfile;
import com.neracalab.backend.company.profile.CompanyProfileViews.CompanyProfile;
import com.neracalab.backend.company.profile.CompanyProfileViews.Conflict;
import com.neracalab.backend.company.profile.CompanyProfileViews.ListingCheck;
import com.neracalab.backend.company.profile.CompanyProfileViews.MissingListing;
import com.neracalab.backend.company.profile.CompanyProfileViews.Status;
import com.neracalab.backend.company.profile.CompanyProfileViews.UploadResult;
import com.neracalab.backend.company.profile.IdxProfileParser.Company;
import com.neracalab.backend.company.profile.IdxProfileParser.Dividend;
import com.neracalab.backend.company.profile.IdxProfileParser.Parsed;

import tools.jackson.databind.json.JsonMapper;

/**
 * IDX company profiles: the upload of the JSON a browser session collected on idx.co.id (Ingestion > Screening Data
 * IDX), its status, and the Profile tab of a company (profile and dividend table).
 * <p>
 * Writes of an upload: the listing date when none is stored (a different stored date is a conflict, kept), the
 * listing board and the IDX-IC classification, the main business activity and the share registrar (a value the file
 * lacks keeps the stored one), and of every company with a detail response its IDX dividends (replaced). Tickers that
 * are not companies of the exchange are listed, never created. The same file (SHA-256) again writes nothing and
 * returns the earlier result.
 */
@Service
public class CompanyProfileService {

    /**
     * Largest file accepted (spring.servlet.multipart.max-file-size): a detail response per company of IDX is about
     * 5 - 30 kB, ~950 companies.
     */
    public static final int MAX_UPLOAD_BYTES = 50 * 1024 * 1024;
    private static final int MAX_WARNINGS = 200;
    private static final int MISSING_SHOWN = 50;

    private final CompanyProfileRepository repository;
    private final CompanyProfileReadRepository reads;
    private final JsonMapper json;
    private final IdxProfileParser parser;

    public CompanyProfileService(CompanyProfileRepository repository, CompanyProfileReadRepository reads,
                                 JsonMapper json) {
        this.repository = repository;
        this.reads = reads;
        this.json = json;
        this.parser = new IdxProfileParser(json);
    }

    // ------------------------------------------------------------------ upload

    @Transactional
    public UploadResult upload(String exchangeCode, String fileName, byte[] bytes, String user) {
        Exchange exchange = Exchange.of(exchangeCode);
        fileName = displayName(fileName);
        if (bytes == null || bytes.length == 0) {
            throw new IdxProfileParser.InvalidProfileFileException("The file is empty");
        }
        if (bytes.length > MAX_UPLOAD_BYTES) {
            throw new IdxProfileParser.InvalidProfileFileException("The file exceeds " + (MAX_UPLOAD_BYTES >> 20) + " MB");
        }
        String sha = sha256(bytes);
        var earlier = repository.upload(exchange.code(), sha);
        if (earlier.isPresent()) {
            return result(earlier.get()).asDuplicate();
        }
        Parsed parsed = parser.parse(bytes);
        long uploadId;
        try {
            uploadId = repository.startUpload(exchange.code(), fileName, sha, user);
        } catch (DuplicateKeyException e) {
            // the same file uploaded at the same moment by another request
            return repository.upload(exchange.code(), sha).map(u -> result(u).asDuplicate()).orElseThrow(() -> e);
        }

        Map<String, StoredProfile> stored = repository.profiles(exchange.code());
        List<String> unknown = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        List<ListingCheck> checks = new ArrayList<>();
        int updated = 0;
        int unchanged = 0;
        int listingDates = 0;
        int dividends = 0;
        for (Company c : parsed.companies()) {
            StoredProfile s = stored.get(c.ticker());
            if (s == null) {
                unknown.add(c.ticker());
                continue;
            }
            boolean changed = false;
            LocalDate listing = c.listingDate();
            if (listing != null) {
                if (s.ipoDate() == null) {
                    listingDates++;
                    changed = true;
                } else if (!s.ipoDate().equals(listing)) {
                    conflicts.add(new Conflict(c.ticker(), "listingDate", s.ipoDate().toString(), listing.toString(),
                            "the stored listing date was kept; correct company.ipo_date if IDX's is right"));
                }
                ListingCheck check = check(c.ticker(), listing, s);
                if (check != null) {
                    checks.add(check);
                }
            }
            changed |= differs(c.listingBoard(), s.listingBoard()) || differs(c.sector(), s.idxSector())
                    || differs(c.subSector(), s.idxSubSector()) || differs(c.industry(), s.idxIndustry())
                    || differs(c.subIndustry(), s.idxSubIndustry())
                    || differs(c.businessActivity(), s.businessActivity())
                    || differs(c.shareRegistrar(), s.shareRegistrar()) || differs(c.name(), s.idxName());
            repository.updateProfile(s.companyId(), listing, c.listingBoard(), c.sector(), c.subSector(), c.industry(),
                    c.subIndustry(), c.businessActivity(), c.shareRegistrar(), c.name());
            if (c.detail()) {
                List<Dividend> before = repository.idxDividends(s.companyId());
                repository.replaceDividends(s.companyId(), uploadId, c.dividends());
                List<Dividend> after = repository.idxDividends(s.companyId());
                dividends += after.size();
                changed |= !sameDividends(before, after);
            }
            if (changed) {
                updated++;
            } else {
                unchanged++;
            }
        }
        List<String> warnings = parsed.warnings().size() <= MAX_WARNINGS ? parsed.warnings()
                : new ArrayList<>(parsed.warnings().subList(0, MAX_WARNINGS));
        if (parsed.warnings().size() > MAX_WARNINGS) {
            warnings.add("... and " + (parsed.warnings().size() - MAX_WARNINGS) + " more warnings");
        }
        UploadResult result = new UploadResult(uploadId, fileName, false, null, user, parsed.profiles(),
                parsed.details(), updated, unchanged, listingDates, dividends, unknown, conflicts, checks, warnings);
        repository.finishUpload(uploadId, parsed.profiles(), parsed.details(), updated, unchanged, dividends,
                json.writeValueAsString(result));
        return repository.upload(exchange.code(), sha).map(this::result).orElse(result);
    }

    /**
     * A listing date the stored prices or Yahoo contradict: a close stored before it (a relisting, a ticker reused or
     * a wrong date), or Yahoo's first trade more than a week before it. Null when it fits.
     */
    static ListingCheck check(String ticker, LocalDate listing, StoredProfile s) {
        List<String> notes = new ArrayList<>();
        if (s.firstPriceDate() != null && s.firstPriceDate().isBefore(listing)) {
            notes.add("a price is stored for " + s.firstPriceDate() + ", before the listing date (a relisting, a "
                    + "ticker reused or a wrong date)");
        }
        if (s.yahooFirstTradeDate() != null && s.yahooFirstTradeDate().isBefore(listing.minusDays(7))) {
            notes.add("Yahoo Finance's first trade date " + s.yahooFirstTradeDate() + " is before it");
        }
        return notes.isEmpty() ? null
                : new ListingCheck(ticker, listing, s.firstPriceDate(), s.yahooFirstTradeDate(), String.join("; ", notes));
    }

    private static boolean differs(String file, String stored) {
        // a value the file lacks keeps the stored one: no change
        return file != null && !file.equals(stored);
    }

    private static boolean sameDividends(List<Dividend> a, List<Dividend> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            Dividend x = a.get(i);
            Dividend y = b.get(i);
            if (!Objects.equals(x.fiscalYear(), y.fiscalYear()) || !Objects.equals(x.exDate(), y.exDate())
                    || !Objects.equals(x.cumDate(), y.cumDate()) || !Objects.equals(x.paymentDate(), y.paymentDate())
                    || !Objects.equals(x.recordingDate(), y.recordingDate())
                    || !Objects.equals(x.currency(), y.currency()) || !Objects.equals(x.type(), y.type())
                    || x.cashPerShare().compareTo(y.cashPerShare()) != 0
                    || !Objects.equals(x.totalCurrency(), y.totalCurrency())
                    || (x.totalAmount() == null) != (y.totalAmount() == null)
                    || (x.totalAmount() != null && x.totalAmount().compareTo(y.totalAmount()) != 0)) {
                return false;
            }
        }
        return true;
    }

    private UploadResult result(CompanyProfileRepository.Upload u) {
        UploadResult stored;
        try {
            stored = json.readValue(u.result(), UploadResult.class);
        } catch (RuntimeException e) {
            stored = null;
        }
        if (stored == null || stored.uploadId() == null) {
            return new UploadResult(u.uploadId(), u.fileName(), false, u.uploadedAt(), u.uploadedBy(), u.profiles(),
                    u.details(), u.updated(), u.unchanged(), 0, u.dividends(), List.of(), List.of(), List.of(),
                    List.of());
        }
        return new UploadResult(u.uploadId(), u.fileName(), false, u.uploadedAt(), u.uploadedBy(), stored.profiles(),
                stored.details(), stored.updated(), stored.unchanged(), stored.listingDates(), stored.dividends(),
                nonNull(stored.unknownTickers()), nonNull(stored.conflicts()), nonNull(stored.checks()),
                nonNull(stored.warnings()));
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ status

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Status status(String exchangeCode) {
        Exchange exchange = Exchange.of(exchangeCode);
        CompanyProfileRepository.Counts n = repository.counts(exchange.code());
        List<MissingListing> missing = repository.missingListingDates(exchange.code(), MISSING_SHOWN).stream()
                .map(m -> new MissingListing(m.ticker(), m.companyName(), m.active(), m.firstPriceDate(),
                        m.yahooFirstTradeDate()))
                .toList();
        return new Status(exchange.code(), n.companies(), n.active(), n.withListingDate(), n.withBoard(),
                n.withIdxSector(), n.withIdxDividends(), n.idxDividends(), n.lastProfileAt(),
                repository.lastUpload(exchange.code()).map(this::result).orElse(null), missing, n.missing());
    }

    // ------------------------------------------------------------------ company page

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CompanyProfile companyProfile(String exchangeCode, String tickerCode) {
        Exchange exchange = Exchange.of(exchangeCode);
        String ticker = Tickers.normalize(tickerCode);
        CompanyProfileReadRepository.Row row = reads.company(exchange.code(), ticker)
                .orElseThrow(() -> new CompanyNotFoundException(exchange.code(), ticker));
        String currency = row.currency() == null ? exchange.currency() : row.currency().toUpperCase(Locale.ROOT);
        String listing = exchange.currency();
        var eps = reads.latestEpsTtm(row.companyId()).orElse(null);
        DividendHistory.Result history = DividendHistory.of(new DividendHistory.Input(currency, listing,
                reads.closes(row.companyId()), reads.splits(row.companyId()), reads.yahooDividends(row.companyId()),
                repository.idxDividends(row.companyId()), reads.fiscalYears(row.companyId()),
                reads.rates(currency, listing, row.companyId()), eps == null ? null : eps.getValue(),
                eps == null ? null : eps.getKey()));

        List<String> notes = new ArrayList<>();
        if (row.profileUpdatedAt() == null) {
            notes.add("No IDX company profile uploaded for " + ticker + " yet (Ingestion > Screening Data IDX > IDX "
                    + "company profiles): no listing board, IDX-IC sector or IDX dividends.");
        }
        if (!currency.equals(listing)) {
            notes.add(ticker + " reports in " + currency + "; prices and dividends per share are in " + listing
                    + " (closes converted back at the rate the price ingestion used), payout ratios in " + currency + ".");
        }
        Integer yearsListed = row.ipoDate() == null ? null : Period.between(row.ipoDate(), LocalDate.now()).getYears();
        CompanyProfileViews.Profile profile = new CompanyProfileViews.Profile(row.companyName(), row.legalName(),
                row.idxName(), row.ipoDate(), yearsListed, row.listingBoard(), row.idxSector(), row.idxSubSector(),
                row.idxIndustry(), row.idxSubIndustry(), row.businessActivity(), row.shareRegistrar(), row.sector(),
                row.industry(), row.country(), row.fiscalYearEnd(), row.firstPriceDate(), row.yahooFirstTradeDate(),
                row.profileUpdatedAt());
        return new CompanyProfile(exchange.code(), ticker, currency, listing, profile, history.summary(),
                history.rows(), history.years(), notes);
    }

    /** The file name without a path a browser may send. */
    static String displayName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String base = name.replace('\\', '/');
        return base.substring(base.lastIndexOf('/') + 1).trim();
    }
}

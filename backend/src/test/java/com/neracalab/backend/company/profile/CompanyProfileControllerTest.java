package com.neracalab.backend.company.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.TestLogins;
import com.neracalab.backend.company.profile.CompanyProfileViews.CompanyProfile;
import com.neracalab.backend.company.profile.CompanyProfileViews.DividendRow;
import com.neracalab.backend.company.profile.CompanyProfileViews.Status;
import com.neracalab.backend.company.profile.CompanyProfileViews.UploadResult;
import com.neracalab.backend.company.score.ScorecardService.AgentCard;
import com.neracalab.backend.company.score.ScorecardService.CriterionView;
import com.neracalab.backend.company.score.ScorecardService.Scorecards;
import com.neracalab.backend.screening.InvestorAgent;
import com.neracalab.backend.screening.quant.QuantScorer;

import tools.jackson.databind.json.JsonMapper;

/**
 * The IDX company profile upload, the Profile tab and the Quant Score tab against the Docker Postgres. Every test runs
 * in a transaction that is rolled back: nothing it uploads stays.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CompanyProfileControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

    private MockMvc mvc;
    private String token;

    @BeforeEach
    void login() {
        token = TestLogins.rootToken(context);
        mvc = TestLogins.mockMvc(context, token);
        // the tests start from companies without a profile
        jdbc.sql("""
                        UPDATE company SET ipo_date = NULL, listing_board = NULL, idx_sector = NULL, idx_sub_sector = NULL,
                               idx_industry = NULL, idx_sub_industry = NULL, business_activity = NULL,
                               share_registrar = NULL, idx_name = NULL, profile_updated_at = NULL
                        WHERE exchange = 'IDX' AND ticker IN ('HRTA', 'BBCA')""").update();
        jdbc.sql("""
                        DELETE FROM company_dividend WHERE company_id IN (
                            SELECT company_id FROM company WHERE exchange = 'IDX' AND ticker IN ('HRTA', 'BBCA'))""")
                .update();
    }

    @AfterEach
    void logout() {
        TestLogins.logout(context, token);
    }

    private static final String FILE = """
            {"source": "idx.co.id", "collectedAt": "2026-11-08T03:00:00Z",
             "profiles": {"data": [
               {"KodeEmiten": "HRTA", "NamaEmiten": "Hartadinata Abadi Tbk", "TanggalPencatatan": "2017-06-21T00:00:00",
                "PapanPencatatan": "Utama", "Sektor": "Barang Konsumen Non-Primer", "SubSektor": "Barang Rumah Tangga",
                "Industri": "Barang Rumah Tangga", "SubIndustri": "Perhiasan", "KegiatanUsahaUtama": "Perhiasan emas",
                "BAE": "PT Adimitra Jasa Korpora"},
               {"KodeEmiten": "ZZQ9", "TanggalPencatatan": "2026-01-02T00:00:00", "PapanPencatatan": "Utama"}]},
             "details": {"HRTA": {"Profiles": [{"KodeEmiten": "HRTA"}], "Dividen": [
               {"Jenis": "dt", "TahunBuku": "2024", "CashDividenPerSaham": 12, "CashDividenPerSahamMU": "IDR",
                "TanggalCum": "2025-06-10T00:00:00", "TanggalExRegulerDanNegosiasi": "2025-06-11T00:00:00",
                "TanggalPembayaran": "2025-07-02T00:00:00"}]}},
             "failed": []}""";

    private UploadResult upload(String name, String body) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", name, "application/json",
                body.getBytes(StandardCharsets.UTF_8));
        String response = mvc.perform(multipart("/api/v1/companies/profiles/upload").file(file))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readValue(response, UploadResult.class);
    }

    private <T> T getJson(String path, Class<T> type) throws Exception {
        String body = mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readValue(body, type);
    }

    @Test
    void anUploadWritesTheProfileAndTheDividendsAndListsUnknownTickers() throws Exception {
        UploadResult result = upload("company-profiles-2026-11-08.json", FILE);

        assertThat(result.duplicate()).isFalse();
        assertThat(result.profiles()).isEqualTo(2);
        assertThat(result.details()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.listingDates()).isEqualTo(1);
        assertThat(result.dividends()).isEqualTo(1);
        assertThat(result.unknownTickers()).containsExactly("ZZQ9");
        assertThat(result.conflicts()).isEmpty();
        // never created
        assertThat(jdbc.sql("SELECT count(*) FROM company WHERE ticker = 'ZZQ9'").query(Long.class).single()).isZero();

        Map<String, Object> hrta = jdbc.sql("""
                        SELECT ipo_date, listing_board, idx_sector, idx_sub_industry, business_activity, share_registrar
                        FROM company WHERE exchange = 'IDX' AND ticker = 'HRTA'""").query().singleRow();
        assertThat(hrta.get("ipo_date").toString()).isEqualTo("2017-06-21");
        assertThat(hrta.get("listing_board")).isEqualTo("MAIN");
        assertThat(hrta.get("idx_sector")).isEqualTo("Barang Konsumen Non-Primer");
        assertThat(hrta.get("idx_sub_industry")).isEqualTo("Perhiasan");
        assertThat(hrta.get("share_registrar")).isEqualTo("PT Adimitra Jasa Korpora");

        // the Profile tab
        CompanyProfile profile = getJson("/api/v1/companies/IDX/hrta/profile", CompanyProfile.class);
        assertThat(profile.profile().listingDate()).isEqualTo(LocalDate.of(2017, 6, 21));
        assertThat(profile.profile().listingBoard()).isEqualTo("MAIN");
        assertThat(profile.profile().businessActivity()).isEqualTo("Perhiasan emas");
        assertThat(profile.currency()).isEqualTo("IDR");
        DividendRow idx = profile.dividends().stream().filter(d -> LocalDate.of(2025, 6, 11).equals(d.exDate()))
                .findFirst().orElseThrow();
        assertThat(idx.source()).startsWith("IDX");
        assertThat(idx.fiscalYear()).isEqualTo(2024);
        assertThat(idx.cashPerShare()).isEqualByComparingTo("12");
        // the cum-date close is stored for HRTA: a yield
        assertThat(idx.price()).isNotNull();
        assertThat(idx.dividendYield()).isNotNull();
        assertThat(profile.summary().idxDividends()).isEqualTo(1);
        assertThat(profile.years()).isNotEmpty();
        assertThat(profile.years()).extracting(y -> y.fiscalYear()).isSortedAccordingTo((a, b) -> b - a);

        // the card's status
        Status status = getJson("/api/v1/companies/profiles/status?exchange=IDX", Status.class);
        assertThat(status.lastUpload()).isNotNull();
        assertThat(status.lastUpload().fileName()).isEqualTo("company-profiles-2026-11-08.json");
        assertThat(status.lastUpload().unknownTickers()).containsExactly("ZZQ9");
        assertThat(status.withListingDate()).isGreaterThanOrEqualTo(1);
        assertThat(status.missing()).extracting(m -> m.ticker()).doesNotContain("HRTA");
    }

    @Test
    void theSameFileAgainWritesNothing() throws Exception {
        UploadResult first = upload("a.json", FILE);
        jdbc.sql("UPDATE company SET listing_board = 'WATCHLIST' WHERE exchange = 'IDX' AND ticker = 'HRTA'").update();

        UploadResult again = upload("b.json", FILE);

        assertThat(again.duplicate()).isTrue();
        assertThat(again.uploadId()).isEqualTo(first.uploadId());
        assertThat(again.updated()).isEqualTo(first.updated());
        assertThat(jdbc.sql("SELECT listing_board FROM company WHERE exchange = 'IDX' AND ticker = 'HRTA'")
                .query(String.class).single()).isEqualTo("WATCHLIST");
    }

    @Test
    void aDifferentStoredListingDateIsAConflictAndKept() throws Exception {
        jdbc.sql("UPDATE company SET ipo_date = DATE '2017-06-20' WHERE exchange = 'IDX' AND ticker = 'HRTA'").update();

        UploadResult result = upload("c.json", FILE);

        assertThat(result.listingDates()).isZero();
        assertThat(result.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.ticker()).isEqualTo("HRTA");
            assertThat(c.stored()).isEqualTo("2017-06-20");
            assertThat(c.file()).isEqualTo("2017-06-21");
        });
        assertThat(jdbc.sql("SELECT ipo_date FROM company WHERE exchange = 'IDX' AND ticker = 'HRTA'")
                .query(LocalDate.class).single()).isEqualTo(LocalDate.of(2017, 6, 20));
    }

    @Test
    void anUploadWithoutTheFieldsKeepsTheStoredOnesAndADetailReplacesTheDividends() throws Exception {
        upload("d.json", FILE);
        // a second file: the detail of HRTA with another dividend, its list row without a board
        UploadResult second = upload("e.json", """
                {"profiles": [{"KodeEmiten": "HRTA", "TanggalPencatatan": "2017-06-21"}],
                 "details": {"HRTA": {"Dividen": [
                   {"TahunBuku": "2025", "CashDividenPerSaham": 15, "CashDividenPerSahamMU": "IDR",
                    "TanggalCum": "2026-06-09", "TanggalExRegulerDanNegosiasi": "2026-06-10"}]}}}""");

        assertThat(second.updated()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT listing_board FROM company WHERE exchange = 'IDX' AND ticker = 'HRTA'")
                .query(String.class).single()).isEqualTo("MAIN");
        assertThat(jdbc.sql("""
                        SELECT fiscal_year FROM company_dividend d JOIN company c USING (company_id)
                        WHERE c.exchange = 'IDX' AND c.ticker = 'HRTA'""").query(Integer.class).list())
                .containsExactly(2025);
    }

    @Test
    void aFileWithoutProfilesIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "x.json", "application/json",
                "{\"data\": []}".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/companies/profiles/upload").file(file))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Rejected"));
    }

    @Test
    void anUnknownCompanyIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/companies/IDX/ZZQ9/profile")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/companies/IDX/ZZQ9/scorecards")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ Quant Score

    @Test
    void theQuantScoreHasTheSixAgentsAndTheOverallOfQuantScorer() throws Exception {
        Scorecards s = getJson("/api/v1/companies/IDX/HRTA/scorecards", Scorecards.class);

        assertThat(s.ticker()).isEqualTo("HRTA");
        assertThat(s.financial()).isFalse();
        assertThat(s.agents()).extracting(AgentCard::agent)
                .containsExactly("BUFFETT", "MUNGER", "LYNCH", "FISHER", "GILL", "RISK");
        Map<InvestorAgent, Double> counted = new EnumMap<>(InvestorAgent.class);
        for (AgentCard a : s.agents()) {
            assertThat(a.criteria()).hasSameSizeAs(QuantScorer.card(InvestorAgent.valueOf(a.agent())));
            if (a.inOverall()) {
                counted.put(InvestorAgent.valueOf(a.agent()), a.score());
                assertThat(a.coverage()).isGreaterThanOrEqualTo(s.minCoverage());
            }
            for (CriterionView c : a.criteria()) {
                if (c.points() != null) {
                    assertThat(c.points()).isBetween(0d, 1d);
                    assertThat(c.value()).isNotNull();
                    assertThat(c.source()).isNotNull();
                }
            }
        }
        // HRTA's filings, valuation and prices are stored: the scorecards are scored
        assertThat(counted).isNotEmpty();
        assertThat(s.overall()).isEqualTo(QuantScorer.overall(counted, s.riskWeight()));
        assertThat(s.verdict()).isNotNull();
        assertThat(s.fiscalYear()).startsWith("FY");
        // Buffett's ROE comes from the filings
        CriterionView roe = s.agents().get(0).criteria().stream().filter(c -> c.key().equals("roe")).findFirst()
                .orElseThrow();
        assertThat(roe.source()).isEqualTo("FILINGS");
        assertThat(roe.poor()).isEqualTo(0.08);
        assertThat(roe.good()).isEqualTo(0.20);
    }

    @Test
    void aBankIsScoredOnItsCapital() throws Exception {
        Scorecards s = getJson("/api/v1/companies/IDX/BBCA/scorecards", Scorecards.class);
        assertThat(s.financial()).isTrue();
        AgentCard risk = s.agents().stream().filter(a -> a.agent().equals("RISK")).findFirst().orElseThrow();
        assertThat(risk.criteria()).filteredOn(c -> c.key().equals("debtToEquity")).singleElement()
                .satisfies(c -> assertThat(c.applies()).isFalse());
        assertThat(risk.criteria()).filteredOn(c -> c.key().equals("equityToAssets")).singleElement()
                .satisfies(c -> {
                    assertThat(c.applies()).isTrue();
                    assertThat(c.value()).isNotNull();
                });
    }

    @Test
    void aUsdReportersDividendYieldsAreInRupiah() throws Exception {
        CompanyProfile aadi = getJson("/api/v1/companies/IDX/AADI/profile", CompanyProfile.class);
        assertThat(aadi.currency()).isEqualTo("USD");
        assertThat(aadi.listingCurrency()).isEqualTo("IDR");
        assertThat(aadi.dividends()).isNotEmpty();
        for (DividendRow d : aadi.dividends()) {
            if (d.price() != null) {
                // a rupiah price (thousands), not the stored USD (below 1)
                assertThat(d.price().doubleValue()).isGreaterThan(100);
                assertThat(d.dividendYield().doubleValue()).isBetween(0.001, 0.5);
            }
        }
    }
}

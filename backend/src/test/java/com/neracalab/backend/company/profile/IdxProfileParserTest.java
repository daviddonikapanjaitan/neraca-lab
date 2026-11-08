package com.neracalab.backend.company.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.company.profile.IdxProfileParser.Company;
import com.neracalab.backend.company.profile.IdxProfileParser.Dividend;
import com.neracalab.backend.company.profile.IdxProfileParser.InvalidProfileFileException;
import com.neracalab.backend.company.profile.IdxProfileParser.Parsed;

import tools.jackson.databind.json.JsonMapper;

/** The JSON shapes of idx.co.id's company profile responses and of the collector script's file. */
class IdxProfileParserTest {

    private final IdxProfileParser parser = new IdxProfileParser(JsonMapper.builder().build());

    private Parsed parse(String json) {
        return parser.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static Company company(Parsed p, String ticker) {
        return p.companies().stream().filter(c -> c.ticker().equals(ticker)).findFirst().orElseThrow();
    }

    @Test
    void readsTheListResponseOfGetCompanyProfiles() {
        Parsed p = parse("""
                {"draw": 0, "recordsTotal": 2, "recordsFiltered": 2, "data": [
                  {"KodeEmiten": "AALI", "NamaEmiten": "Astra Agro Lestari Tbk", "TanggalPencatatan": "1997-12-09T00:00:00",
                   "PapanPencatatan": "Utama", "Sektor": "Barang Konsumen Primer", "SubSektor": "Makanan & Minuman",
                   "Industri": "Produk Makanan Pertanian", "SubIndustri": "Perkebunan & Tanaman Pangan",
                   "KegiatanUsahaUtama": "Perkebunan kelapa sawit", "BAE": "PT Raya Saham Registra", "Website": "x"},
                  {"KodeEmiten": " hrta ", "NamaEmiten": "Hartadinata Abadi Tbk", "TanggalPencatatan": "2017-06-21T00:00:00",
                   "PapanPencatatan": "Pengembangan", "Sektor": "", "SubSektor": "-"}
                ]}""");

        assertThat(p.profiles()).isEqualTo(2);
        assertThat(p.details()).isZero();
        Company aali = company(p, "AALI");
        assertThat(aali.listingDate()).isEqualTo(LocalDate.of(1997, 12, 9));
        assertThat(aali.listingBoard()).isEqualTo("MAIN");
        assertThat(aali.boardLabel()).isEqualTo("Utama");
        assertThat(aali.sector()).isEqualTo("Barang Konsumen Primer");
        assertThat(aali.subIndustry()).isEqualTo("Perkebunan & Tanaman Pangan");
        assertThat(aali.businessActivity()).isEqualTo("Perkebunan kelapa sawit");
        assertThat(aali.shareRegistrar()).isEqualTo("PT Raya Saham Registra");
        assertThat(aali.detail()).isFalse();
        assertThat(aali.dividends()).isEmpty();

        // ticker trimmed and upper case; blank and "-" are no value
        Company hrta = company(p, "HRTA");
        assertThat(hrta.listingBoard()).isEqualTo("DEVELOPMENT");
        assertThat(hrta.sector()).isNull();
        assertThat(hrta.subSector()).isNull();
    }

    @Test
    void readsADetailResponseWithItsDividends() {
        Parsed p = parse("""
                {"Profiles": [{"KodeEmiten": "BBCA", "NamaEmiten": "Bank Central Asia Tbk",
                               "TanggalPencatatan": "2000-05-31T00:00:00", "PapanPencatatan": "Utama"}],
                 "Direktur": [{"Nama": "x"}],
                 "Dividen": [
                   {"Nama": "Bank Central Asia Tbk", "Jenis": "dt", "TahunBuku": "2025", "TotalSahamBonus": 0,
                    "CashDividenPerSaham": 250, "CashDividenPerSahamMU": "IDR", "TanggalCum": "2026-03-24T00:00:00",
                    "TanggalExRegulerDanNegosiasi": "2026-03-25T00:00:00", "TanggalDPS": "2026-03-26T00:00:00",
                    "TanggalPembayaran": "2026-04-10T00:00:00", "CashDividenTotal": 30818755000000,
                    "CashDividenTotalMU": "IDR"},
                   {"Jenis": "dt", "TahunBuku": "2025", "CashDividenPerSaham": 250, "CashDividenPerSahamMU": "IDR",
                    "TanggalCum": "2026-03-24T00:00:00", "TanggalExRegulerDanNegosiasi": "2026-03-25T00:00:00"},
                   {"Jenis": "saham bonus", "TahunBuku": "2024", "CashDividenPerSaham": 0, "TanggalCum": "2025-01-02"}
                 ]}""");

        assertThat(p.details()).isEqualTo(1);
        Company bbca = company(p, "BBCA");
        assertThat(bbca.detail()).isTrue();
        assertThat(bbca.listingDate()).isEqualTo(LocalDate.of(2000, 5, 31));
        // the repeated row once, the bonus-share row (no cash) left out
        assertThat(bbca.dividends()).hasSize(1);
        Dividend d = bbca.dividends().get(0);
        assertThat(d.fiscalYear()).isEqualTo(2025);
        assertThat(d.cashPerShare()).isEqualByComparingTo("250");
        assertThat(d.currency()).isEqualTo("IDR");
        assertThat(d.cumDate()).isEqualTo(LocalDate.of(2026, 3, 24));
        assertThat(d.exDate()).isEqualTo(LocalDate.of(2026, 3, 25));
        assertThat(d.recordingDate()).isEqualTo(LocalDate.of(2026, 3, 26));
        assertThat(d.paymentDate()).isEqualTo(LocalDate.of(2026, 4, 10));
        assertThat(d.totalAmount()).isEqualByComparingTo("30818755000000");
        assertThat(p.warnings()).anyMatch(w -> w.contains("repeats an earlier one"));
    }

    @Test
    void readsTheCollectorFileListAndDetailsByTicker() {
        Parsed p = parse("""
                {"source": "idx.co.id", "collectedAt": "2026-11-08T03:00:00Z",
                 "profiles": {"data": [
                    {"KodeEmiten": "AADI", "TanggalPencatatan": "2025-01-08T00:00:00", "PapanPencatatan": "Utama"},
                    {"KodeEmiten": "ZZZZ", "TanggalPencatatan": "2026-01-02T00:00:00"}]},
                 "details": {
                    "AADI": {"Profiles": [{"KodeEmiten": "AADI"}], "Dividen": [
                       {"TahunBuku": 2024, "CashDividenPerSaham": "538,08", "CashDividenPerSahamMU": "IDR",
                        "TanggalCum": "14/11/2025", "TanggalExRegulerDanNegosiasi": "17 Nov 2025"}]},
                    "XYZ1": {"Profiles": [], "Dividen": []}},
                 "failed": ["ABCD"]}""");

        assertThat(p.profiles()).isEqualTo(2);
        assertThat(p.details()).isEqualTo(2);
        Company aadi = company(p, "AADI");
        assertThat(aadi.listingDate()).isEqualTo(LocalDate.of(2025, 1, 8));
        assertThat(aadi.dividends()).singleElement().satisfies(d -> {
            // "538,08" is a decimal comma, not thousands
            assertThat(d.cashPerShare()).isEqualByComparingTo(new BigDecimal("538.08"));
            assertThat(d.cumDate()).isEqualTo(LocalDate.of(2025, 11, 14));
            assertThat(d.exDate()).isEqualTo(LocalDate.of(2025, 11, 17));
            assertThat(d.fiscalYear()).isEqualTo(2024);
        });
        // a detail without a profile keeps the ticker of its key
        assertThat(company(p, "XYZ1").detail()).isTrue();
    }

    @Test
    void readsFieldNamesWithoutRegardToCaseAndOtherDateForms() {
        Parsed p = parse("""
                [{"kodeemiten": "TLKM", "tanggal_pencatatan": "/Date(1006966800000)/", "papanpencatatan": "PAPAN UTAMA"},
                 {"KodeEmiten": "GOTO", "TanggalPencatatan": "11 April 2022", "PapanPencatatan": "Ekonomi Baru"},
                 {"KodeEmiten": "BAD", "TanggalPencatatan": "soon", "PapanPencatatan": "Pemantauan Khusus"},
                 {"NamaEmiten": "no ticker"}]""");

        // 1006966800000 ms = 2001-11-28 17:00 UTC = 2001-11-29 in Jakarta
        assertThat(company(p, "TLKM").listingDate()).isEqualTo(LocalDate.of(2001, 11, 29));
        assertThat(company(p, "TLKM").listingBoard()).isEqualTo("MAIN");
        assertThat(company(p, "GOTO").listingDate()).isEqualTo(LocalDate.of(2022, 4, 11));
        assertThat(company(p, "GOTO").listingBoard()).isEqualTo("NEW_ECONOMY");
        assertThat(company(p, "BAD").listingDate()).isNull();
        assertThat(company(p, "BAD").listingBoard()).isEqualTo("WATCHLIST");
        assertThat(p.warnings()).anyMatch(w -> w.contains("BAD: listing date 'soon' cannot be read"))
                .anyMatch(w -> w.contains("has no ticker"));
    }

    @Test
    void numbersInIndonesianAndEnglishNotation() {
        JsonMapper json = JsonMapper.builder().build();
        assertThat(IdxProfileParser.number(json.readTree("\"1.234.567,5\""))).isEqualByComparingTo("1234567.5");
        assertThat(IdxProfileParser.number(json.readTree("\"1,234,567.5\""))).isEqualByComparingTo("1234567.5");
        assertThat(IdxProfileParser.number(json.readTree("\"Rp 25\""))).isEqualByComparingTo("25");
        assertThat(IdxProfileParser.number(json.readTree("\"12,5\""))).isEqualByComparingTo("12.5");
        assertThat(IdxProfileParser.number(json.readTree("12.75"))).isEqualByComparingTo("12.75");
        assertThat(IdxProfileParser.number(json.readTree("\"-\""))).isNull();
    }

    @Test
    void rejectsAFileWithoutProfiles() {
        assertThatThrownBy(() -> parse("not json")).isInstanceOf(InvalidProfileFileException.class);
        assertThatThrownBy(() -> parse("{\"data\": []}")).isInstanceOf(InvalidProfileFileException.class)
                .hasMessageContaining("No company profile");
    }
}

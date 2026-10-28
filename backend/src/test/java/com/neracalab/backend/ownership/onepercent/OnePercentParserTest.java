package com.neracalab.backend.ownership.onepercent;

import static com.neracalab.backend.ownership.onepercent.OnePercentBooks.holding;
import static com.neracalab.backend.ownership.onepercent.OnePercentBooks.workbook;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ownership.onepercent.OnePercentParser.Holder;
import com.neracalab.backend.ownership.onepercent.OnePercentParser.OnePercentFormatException;
import com.neracalab.backend.ownership.onepercent.OnePercentParser.Report;

/**
 * KSEI's monthly lists of the shareholders of more than 1%: workbooks of its layouts (type codes before April 2026,
 * classifications after), and the lists of February - September 2026 in data/IDX_OWNERSHIP_ONE_PERCENTAGE when present.
 */
class OnePercentParserTest {

    static final Path DIR = Path.of("..", "data", "IDX_OWNERSHIP_ONE_PERCENTAGE");
    private static final LocalDate DATE = LocalDate.of(2001, 1, 31);

    private static Holder holder(Report report, String ticker, String name) {
        return report.holders().stream().filter(h -> h.ticker().equals(ticker) && h.holderName().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError(ticker + " " + name + " not read"));
    }

    @Test
    void readsTheOlderLayoutWithTypeCodes() throws IOException {
        Report r = OnePercentParser.parse(workbook(DATE, true,
                holding("ZZOP", "PT ALFA", "CP", "L         ", 600, 0, 600, 60.0),
                holding("ZZOP", "ALFA, PT", "CP", "L", 0, 50, 50, 5.0),
                holding("ZZOP", "IR. BETA WIJAYA", "ID", "L", 100, 0, 100, 10.0),
                holding("ZZOP", "GAMMA FUND", "MF", "A         ", 30, 0, 30, 3.0000000000000004),
                // scripless + scrip = 120 (12% of the 1,000 shares listed): the sum is right, the total is not
                holding("ZZOP", "DELTA", "IB", "A", 100, 20, 150, 12.0),
                // the total 150 (15%) is right, its scripless shares are not
                holding("ZZOP", "EPSILON", "IS", "L", 180, 20, 150, 15.0),
                holding("ZZOP", "SPECIAL SCRIP ACCOUNT", null, null, 0, 20, 20, 2.0),
                new Object[0]));

        assertThat(r.reportDate()).isEqualTo(DATE);
        assertThat(r.sourceRows()).isEqualTo(7);
        assertThat(r.warnings()).isEmpty();
        assertThat(r.holders()).hasSize(6);
        assertThat(r.tickers()).containsExactly("ZZOP");

        // the same investor listed twice (an SID and a scrip account): one investor
        Holder alfa = holder(r, "ZZOP", "PT ALFA");
        assertThat(alfa.row()).isEqualTo(1);
        assertThat(alfa.holderKey()).isEqualTo("ALFA");
        assertThat(alfa.sourceRows()).isEqualTo(2);
        assertThat(alfa.otherNames()).containsExactly("ALFA, PT");
        assertThat(alfa.shares()).isEqualTo(650);
        assertThat(alfa.scripless()).isEqualTo(600);
        assertThat(alfa.scrip()).isEqualTo(50);
        assertThat(alfa.percent()).isEqualByComparingTo("65.00");
        assertThat(alfa.investorType()).isEqualTo("Corporate");
        assertThat(alfa.typeGroup()).isEqualTo("CORPORATE");
        assertThat(alfa.status()).isEqualTo("L");

        Holder beta = holder(r, "ZZOP", "IR. BETA WIJAYA");
        assertThat(beta.holderKey()).isEqualTo("BETA WIJAYA");
        assertThat(beta.investorType()).isEqualTo("Individual");
        assertThat(beta.nationality()).isEqualTo("INDONESIAN");

        Holder gamma = holder(r, "ZZOP", "GAMMA FUND");
        assertThat(gamma.status()).isEqualTo("A");
        assertThat(gamma.typeGroup()).isEqualTo("FUND");
        assertThat(gamma.percent()).isEqualTo(new BigDecimal("3.0000"));

        Holder delta = holder(r, "ZZOP", "DELTA");
        assertThat(delta.shares()).isEqualTo(120);
        assertThat(delta.investorType()).isEqualTo("Financial Institution");
        Holder epsilon = holder(r, "ZZOP", "EPSILON");
        assertThat(epsilon.shares()).isEqualTo(150);
        assertThat(epsilon.scripless()).isEqualTo(130);
        assertThat(epsilon.scrip()).isEqualTo(20);
        assertThat(epsilon.typeGroup()).isEqualTo("FINANCIAL");

        Holder special = holder(r, "ZZOP", "SPECIAL SCRIP ACCOUNT");
        assertThat(special.investorType()).isNull();
        assertThat(special.typeGroup()).isNull();
        assertThat(special.status()).isNull();

        assertThat(r.notes()).anyMatch(n -> n.contains("PT ALFA listed 2 times (also as ALFA, PT)"))
                .anyMatch(n -> n.startsWith("ZZOP, DELTA") && n.contains("the sum kept"))
                .anyMatch(n -> n.startsWith("ZZOP, EPSILON") && n.contains("the total kept")
                        && n.contains("its scripless shares read as 130"))
                .anyMatch(n -> n.equals("1 row without a ticker and an investor skipped"));
    }

    @Test
    void readsTheClassificationsOfTheNewerLayout() throws IOException {
        Report r = OnePercentParser.parse(workbook(DATE, false,
                holding("ZZOQ", "PT ALFA SEJAHTERA", "Commanditaire Vennootschap (CV) or Limited partnership ", "L",
                        500, 0, 500, 50.0),
                holding("ZZOQ", "BETA LTD", "PermanentEstablishment", "F", 100, 0, 100, 10.0),
                holding("ZZOQ", "GAMMA", "Sovereign Wealth Fund", "F", 100, 0, 100, 10.0),
                holding("ZZOQ", "DELTA", "Diocese", "L", 20, 0, 20, 2.0),
                holding("ZZOQ", "EPSILON", "Something New", "L", 20, 0, 20, 2.0)));
        assertThat(holder(r, "ZZOQ", "PT ALFA SEJAHTERA").investorType()).isEqualTo("Limited Partnership (CV)");
        assertThat(holder(r, "ZZOQ", "PT ALFA SEJAHTERA").typeGroup()).isEqualTo("CORPORATE");
        assertThat(holder(r, "ZZOQ", "BETA LTD").investorType()).isEqualTo("Permanent Establishment");
        assertThat(holder(r, "ZZOQ", "BETA LTD").status()).isEqualTo("A");
        assertThat(holder(r, "ZZOQ", "GAMMA").typeGroup()).isEqualTo("GOVERNMENT");
        assertThat(holder(r, "ZZOQ", "DELTA").typeGroup()).isEqualTo("OTHER");
        assertThat(holder(r, "ZZOQ", "EPSILON").investorType()).isEqualTo("Something New");
        assertThat(holder(r, "ZZOQ", "EPSILON").typeGroup()).isEqualTo("OTHER");
        assertThat(r.notes()).isEmpty();
    }

    @Test
    void refusesWhatIsNotTheList() throws IOException {
        assertThatThrownBy(() -> OnePercentParser.parse("not a workbook".getBytes()))
                .isInstanceOf(OnePercentFormatException.class).hasMessageContaining("Not a readable Excel workbook");
        assertThatThrownBy(() -> OnePercentParser.parse(workbook(DATE, false)))
                .isInstanceOf(OnePercentFormatException.class).hasMessageContaining("no holding");
    }

    @Test
    void normalizesNames() {
        assertThat(OnePercentParser.holderKey("PT. Beyond Media")).isEqualTo("BEYOND MEDIA");
        assertThat(OnePercentParser.holderKey("BEYOND MEDIA, PT")).isEqualTo("BEYOND MEDIA");
        assertThat(OnePercentParser.holderKey("LO KHENG  HONG. DRS")).isEqualTo("LO KHENG HONG");
        assertThat(OnePercentParser.holderKey("PERUSAHAAN PERSEROAN (PERSERO) PT. ASABRI"))
                .isEqualTo("PERUSAHAAN PERSEROAN ASABRI");
        assertThat(OnePercentParser.holderKey("SARATOGA INVESTAMA SEDAYA TBK PT")).isEqualTo("SARATOGA INVESTAMA SEDAYA");
    }

    /** The lists of February - September 2026, as published. */
    @Test
    void readsThePublishedLists() throws IOException {
        assumeTrue(Files.isDirectory(DIR), "1% ownership source data not available: " + DIR);
        Map<String, Report> reports;
        try (var files = Files.list(DIR)) {
            List<Path> lists = files.filter(f -> f.getFileName().toString().endsWith("-satu-persen.xlsx")).sorted()
                    .toList();
            assumeTrue(!lists.isEmpty(), "no list in " + DIR);
            reports = lists.stream().collect(Collectors.toMap(f -> f.getFileName().toString(), f -> {
                try {
                    return OnePercentParser.parse(Files.readAllBytes(f));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        for (Report r : reports.values()) {
            assertThat(r.warnings()).as(r.reportDate().toString()).isEmpty();
            assertThat(r.holders().size()).as(r.reportDate().toString()).isBetween(6500, 7500);
            assertThat(r.tickers().size()).isBetween(900, 1000);
            assertThat(r.holders()).allMatch(h -> h.shares() == h.scripless() + h.scrip());
        }
        Report february = reports.get("peng-02-00011-satu-persen.xlsx");
        if (february != null) {
            assertThat(february.reportDate()).isEqualTo(LocalDate.of(2026, 2, 27));
            assertThat(february.sourceRows()).isEqualTo(7257);
            // the total 6,023,326,332 does not add up: the scrip shares 6,323,076,332 are confirmed by 24.16%
            assertThat(holder(february, "MAYA", "MAYAPADA KARUNIA PT").shares()).isEqualTo(6_323_076_332L);
            Holder aadi = holder(february, "AADI", "ADARO STRATEGIC INVESTMENTS");
            assertThat(aadi.shares()).isEqualTo(3_200_142_830L);
            assertThat(aadi.percent()).isEqualByComparingTo("41.10");
            assertThat(aadi.investorType()).isEqualTo("Corporate");
            assertThat(holder(february, "AADI", "FTAG INVESTMENT BANK LTD").status()).isEqualTo("A");
        }
        Report march = reports.get("peng-03-00012-satu-persen.xlsx");
        if (march != null) {
            // the total 5,909,304,916 is confirmed by 35.80%: the scripless shares are 5,909,304,916 - 2,123,804,901
            Holder smra = holder(march, "SMRA", "PT SEMAROP AGUNG");
            assertThat(smra.shares()).isEqualTo(5_909_304_916L);
            assertThat(smra.scripless()).isEqualTo(3_785_500_015L);
        }
        Report april = reports.get("peng-04-00013-satu-persen.xlsx");
        if (april != null) {
            assertThat(april.reportDate()).isEqualTo(LocalDate.of(2026, 4, 30));
            // listed twice: a scrip account without a type and a scripless SID
            Holder rals = holder(april, "RALS", "PT RAMAYANA MAKMURSENTOSA");
            assertThat(rals.sourceRows()).isEqualTo(2);
            assertThat(rals.shares()).isEqualTo(3_965_000_000L + 203_513_800L);
            assertThat(rals.investorType()).isEqualTo("Corporate");
        }
        Report september = reports.get("peng-2026-09-00024-satu-persen.xlsx");
        if (september != null) {
            assertThat(september.reportDate()).isEqualTo(LocalDate.of(2026, 9, 30));
            Holder timothy = holder(september, "ZYRX", "TIMOTHY SIDDIK SHU");
            assertThat(timothy.scrip()).isEqualTo(750_000_000L);
            assertThat(timothy.shares()).isEqualTo(752_076_700L);
            assertThat(holder(september, "AADI", "FTAG INVESTMENT BANK LTD").investorType())
                    .isEqualTo("Financial Institutional");
        }
    }
}

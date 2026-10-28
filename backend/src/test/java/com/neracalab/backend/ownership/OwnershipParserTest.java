package com.neracalab.backend.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import com.neracalab.backend.ownership.OwnershipParser.Account;
import com.neracalab.backend.ownership.OwnershipParser.Holder;
import com.neracalab.backend.ownership.OwnershipParser.Movement;
import com.neracalab.backend.ownership.OwnershipParser.OwnershipFormatException;
import com.neracalab.backend.ownership.OwnershipParser.Report;

/**
 * KSEI's daily lists of the shareholders of more than 5% in data/IDX_OWNERSHIP_FIVE_PERCENTAGE (19 - 27 October
 * 2026, not in the repository: skipped without them), and sheets built cell by cell.
 */
class OwnershipParserTest {

    static final Path DIR = Path.of("..", "data", "IDX_OWNERSHIP_FIVE_PERCENTAGE");

    static byte[] read(String name) throws IOException {
        Path file = DIR.resolve(name);
        assumeTrue(Files.exists(file), "ownership source data not available: " + file);
        return Files.readAllBytes(file);
    }

    private static Holder holder(Report report, String ticker, String name) {
        return report.holders().stream().filter(h -> h.ticker().equals(ticker) && h.holderName().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError(ticker + " " + name + " not read"));
    }

    @Test
    void readsTheListOf19October() throws IOException {
        Report report = OwnershipParser.parse(read("peng-2026-10-19-00098-lima-persen.xlsx"));
        assertThat(report.reportDate()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(report.previousDate()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(report.title()).startsWith("KEPEMILIKAN EFEK DIATAS 5% BERDASARKAN SID (PUBLIK)");
        assertThat(report.holders()).hasSize(1916);
        assertThat(report.warnings()).isEmpty();

        Holder adaro = holder(report, "AADI", "ADARO STRATEGIC INVESTMENTS");
        assertThat(adaro.row()).isEqualTo(1);
        assertThat(adaro.issuerName()).isEqualTo("ADARO ANDALAN INDONESIA Tbk, PT");
        assertThat(adaro.sharesCurrent()).isEqualTo(3_200_142_830L);
        assertThat(adaro.percentCurrent()).isEqualByComparingTo("41.10");
        assertThat(adaro.accounts()).hasSize(10);
        assertThat(adaro.accounts().getFirst().participant()).isEqualTo("CITIBANK, N. A");
        assertThat(adaro.status()).isEqualTo("L");
        assertThat(adaro.movement()).isEqualTo(Movement.UNCHANGED);

        // 441.4 is 441,400 and the small whole numbers are thousands: the accounts add up to the combined shares
        Holder lo = holder(report, "ABMM", "DRS LO KHENG HONG");
        assertThat(lo.sharesPrevious()).isEqualTo(156_487_700L);
        assertThat(lo.sharesCurrent()).isEqualTo(156_762_700L);
        assertThat(lo.accounts().stream().map(Account::sharesPrevious).toList()).containsExactly(12_178_200L,
                1_454_800L, 36_646_100L, 200_000L, 441_400L, 2_973_100L, 410_000L, 256_500L, 79_406_900L, 22_371_000L,
                29_700L, 120_000L);
        assertThat(lo.movement()).isEqualTo(Movement.BUY);
        assertThat(lo.accountMoves()).isEqualTo(1);
        assertThat(lo.nationality()).isEqualTo("INDONESIAN");

        // here 200 and 600 are as written, 120 is 120,000
        Holder gpra = holder(report, "GPRA", "ABADIMUKTI GUNALESTARI, PT");
        assertThat(gpra.sharesPrevious()).isEqualTo(1_960_801_020L);
        assertThat(gpra.accounts().stream().map(Account::sharesPrevious).toList()).containsExactly(200L, 86_100_000L,
                120_000L, 278_254_000L, 265_500_000L, 76_143_800L, 600L, 292_628_000L, 1_403_600L, 679_847_720L,
                280_803_100L);
        assertThat(gpra.sharesCurrent()).isEqualTo(1_948_868_420L);
        assertThat(gpra.movement()).isEqualTo(Movement.SELL);

        Holder honoris = holder(report, "MDLN", "PT HONORIS CORPORINDO PRATAMA");
        assertThat(honoris.accounts().stream().map(Account::sharesPrevious).toList()).containsExactly(439_605_433L,
                1_500L, 140_100L, 9_900L, 1_000L, 1_000L, 1_031_888_993L, 500L);

        // -5.4 in the change column: 5,400 shares sold
        Holder oona = holder(report, "ABDA", "OONA INDONESIA PTE LTD");
        assertThat(oona.sharesCurrent() - oona.sharesPrevious()).isEqualTo(-5_400L);
        assertThat(oona.status()).isEqualTo("A");
        assertThat(oona.domicile()).isEqualTo("SINGAPORE");
    }

    @Test
    void marksTheSameInvestorUnderAnotherNameAsAnAlias() throws IOException {
        Report report = OwnershipParser.parse(read("peng-2026-10-19-00098-lima-persen.xlsx"));
        List<Holder> evio = report.holders().stream()
                .filter(h -> h.ticker().equals("SIAP") && h.holderKey().equals("EVIO SECURITIES")).toList();
        assertThat(evio).hasSize(3);
        assertThat(evio.getFirst().aliasOf()).isNull();
        assertThat(evio.get(1).aliasOf()).isEqualTo(evio.getFirst().row());
        assertThat(evio.get(2).aliasOf()).isEqualTo(evio.getFirst().row());
        assertThat(evio.getFirst().sharesCurrent()).isEqualTo(2_628_897_282L);
        assertThat(report.aliases()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void readsInvestorsCrossingFivePercentAndMovesBetweenAccounts() throws IOException {
        Report report = OwnershipParser.parse(read("peng-2026-10-27-00104-lima-persen.xlsx"));
        assertThat(report.reportDate()).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(report.previousDate()).isEqualTo(LocalDate.of(2026, 10, 26));

        Holder yulie = holder(report, "AHAP", "PT YULIE SEKURITAS INDONESIA TBK");
        assertThat(yulie.sharesPrevious()).isNull();
        assertThat(yulie.sharesCurrent()).isEqualTo(599_736_535L);
        assertThat(yulie.percentCurrent()).isEqualByComparingTo(new BigDecimal("7.14"));
        assertThat(yulie.movement()).isEqualTo(Movement.ENTERED);

        // one of its accounts closed
        Holder aca = holder(report, "AHAP", "ASURANSI CENTRAL ASIA, PT");
        assertThat(aca.sharesPrevious()).isEqualTo(5_838_687_150L);
        assertThat(aca.sharesCurrent()).isEqualTo(5_256_588_015L);
        assertThat(aca.accounts().getFirst().sharesCurrent()).isNull();
        assertThat(aca.movement()).isEqualTo(Movement.SELL);

        Holder rafi = holder(report, "RAFI", "GIDEON SURYATIKA");
        assertThat(rafi.movement()).isEqualTo(Movement.ENTERED);
        assertThat(rafi.sharesPrevious()).isNull();

        // 383,000,000 shares moved from one account to another: the investor's total did not change
        Holder adaro = holder(report, "ADRO", "ADARO STRATEGIC INVESTMENTS");
        assertThat(adaro.movement()).isEqualTo(Movement.UNCHANGED);
        assertThat(adaro.accountMoves()).isEqualTo(2);

        // AHAP's Yulie Sekuritas, RAFI's Gideon Suryatika and TRUK's JPMorgan Chase Singapore crossed 5%; an investor
        // that fell below 5% is not in the list at all (TRUK's Suwito): the repository finds it in the day before
        assertThat(report.count(Movement.ENTERED)).isEqualTo(3);
        assertThat(report.count(Movement.EXITED)).isZero();
        assertThat(report.holders()).noneMatch(h -> h.ticker().equals("TRUK") && h.holderName().equals("SUWITO"));
    }

    @Test
    void readsEveryListStored() throws IOException {
        assumeTrue(Files.isDirectory(DIR), "ownership source data not available: " + DIR);
        try (var files = Files.list(DIR)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".xlsx")).toList()) {
                Report report = OwnershipParser.parse(Files.readAllBytes(file));
                assertThat(report.warnings()).as(file.toString()).isEmpty();
                assertThat(report.tickers().size()).as(file.toString()).isGreaterThan(800);
                assertThat(report.previousDate()).isBefore(report.reportDate());
                // MAMIP: three holders, every number below 1,000 and nothing to tell them from thousands
                assertThat(report.notes()).as(file.toString()).allMatch(n -> n.startsWith("MAMIP "));
            }
        }
    }

    // ------------------------------------------------------------------ sheets built cell by cell

    private static byte[] sheet(Object[][] rows) throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Balance");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v instanceof String s) {
                        row.createCell(c).setCellValue(s);
                    } else if (v instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    }
                }
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    private static final Object[] HEADER = {"No", "Kode Efek", "Nama Emiten", "Nama Pemegang Rekening Efek",
            "Nama Pemegang Saham", "Nama Rekening Efek", "Alamat", "Alamat (Lanjutan)", "Kebangsaan", "Domisili",
            "Status (Lokal/Asing)", "Kepemilikan Per 23-OKT-2026", null, null, "Kepemilikan Per 26-OKT-2026", null, null,
            "Perubahan"};
    private static final Object[] SUB = {null, null, null, null, null, null, null, null, null, null, null,
            "Jumlah Saham", "Saham Gabungan Per Investor", "Persentase Kepemilikan Per Investor (%)", "Jumlah Saham",
            "Saham Gabungan Per Investor", "Persentase Kepemilikan Per Investor (%)", null};

    @Test
    void readsIndonesianMonthsAndDecidesByThePercentage() throws IOException {
        byte[] content = sheet(new Object[][] {
                {"KEPEMILIKAN EFEK DIATAS 5% BERDASARKAN SID (PUBLIK) per tanggal 26 Okt 2026"},
                {},
                HEADER,
                SUB,
                // 60% of 10,000,000 listed
                {1, "TEST", "TEST Tbk, PT", "BROKER A", "PT BESAR", "PT BESAR", "x", "x", null, "INDONESIA", "L",
                        "6,000,000", "6,000,000", "60.00", "6,000,000", "6,000,000", "60.00", 0},
                // 500 alone: its own total is no help, 5.00% of 10,000,000 is 500,000
                {2, "TEST", "TEST Tbk, PT", "BROKER B", "KECIL", "KECIL", "x", "x", "INDONESIAN", "INDONESIA", "L",
                        500, 500, "5.00", 500, 500, "5.00", 0},
                {null, null, null, null, null, null},
                {"Keterangan"},
        });
        Report report = OwnershipParser.parse(content);
        assertThat(report.reportDate()).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(report.previousDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(report.holders()).hasSize(2);
        assertThat(report.holders().get(1).sharesCurrent()).isEqualTo(500_000L);
        assertThat(report.holders().get(1).accounts().getFirst().sharesCurrent()).isEqualTo(500_000L);
        assertThat(report.notes()).isEmpty();
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void warnsWhenTheAccountsDoNotAddUp() throws IOException {
        byte[] content = sheet(new Object[][] {
                {"KEPEMILIKAN EFEK DIATAS 5% BERDASARKAN SID (PUBLIK) per tanggal 26 Oct 2026"},
                HEADER,
                SUB,
                {1, "TEST", "TEST Tbk, PT", "BROKER A", "PT BESAR", "PT BESAR", "x", "x", null, "INDONESIA", "L",
                        "6,000,000", "7,000,000", "60.00", "6,000,000", "7,000,000", "60.00", 0},
                {null, "TEST", null, "BROKER B", null, "PT BESAR", "x", "x", null, "INDONESIA", "L",
                        "500,000", null, null, "500,000", null, null, 0},
        });
        Report report = OwnershipParser.parse(content);
        assertThat(report.holders().getFirst().sharesCurrent()).isEqualTo(7_000_000L);
        assertThat(report.holders().getFirst().accounts()).hasSize(2);
        assertThat(report.warnings()).hasSize(2).allMatch(w -> w.contains("add up to 6,500,000"));
    }

    @Test
    void refusesWhatIsNotTheList() throws IOException {
        assertThatThrownBy(() -> OwnershipParser.parse("not a workbook".getBytes()))
                .isInstanceOf(OwnershipFormatException.class).hasMessageContaining("Not a readable Excel workbook");
        assertThatThrownBy(() -> OwnershipParser.parse(sheet(new Object[][] {{"Laporan"}, {"a", "b"}})))
                .isInstanceOf(OwnershipFormatException.class).hasMessageContaining("no sheet has the header");
        assertThatThrownBy(() -> OwnershipParser.parse(sheet(new Object[][] {
                {"KEPEMILIKAN EFEK DIATAS 1% BERDASARKAN SID"}, HEADER, SUB})))
                .isInstanceOf(OwnershipFormatException.class).hasMessageContaining("more than 1%");
        assertThatThrownBy(() -> OwnershipParser.parse(sheet(new Object[][] {
                {"KEPEMILIKAN EFEK DIATAS 5%"}, HEADER, SUB,
                {1, "TEST", "TEST Tbk, PT", "BROKER A", "PT BESAR", "PT BESAR", "x", "x", null, "INDONESIA", "L",
                        "enam juta", "6,000,000", "60.00", "6,000,000", "6,000,000", "60.00", 0}})))
                .isInstanceOf(OwnershipFormatException.class).hasMessageContaining("is not a share count");
    }

    @Test
    void normalizesInvestorNames() {
        assertThat(OwnershipParser.holderKey("PT. EVIO SECURITIES")).isEqualTo("EVIO SECURITIES");
        assertThat(OwnershipParser.holderKey("EVIO SECURITIES, PT")).isEqualTo("EVIO SECURITIES");
        assertThat(OwnershipParser.holderKey("PT ABADIMUKTI GUNALESTARI")).isEqualTo("ABADIMUKTI GUNALESTARI");
        assertThat(OwnershipParser.holderKey("GAJAH TUNGGAL, TbK. PT.")).isEqualTo("GAJAH TUNGGAL");
    }
}

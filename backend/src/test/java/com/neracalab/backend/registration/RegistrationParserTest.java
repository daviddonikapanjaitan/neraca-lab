package com.neracalab.backend.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.registration.PdfWords.Page;
import com.neracalab.backend.registration.PdfWords.Word;
import com.neracalab.backend.registration.RegistrationParser.Holder;
import com.neracalab.backend.registration.RegistrationParser.Insider;
import com.neracalab.backend.registration.RegistrationParser.Ownership;
import com.neracalab.backend.registration.RegistrationParser.Report;
import com.neracalab.backend.registration.RegistrationParser.RegistrationFormatException;

/**
 * The monthly registration reports of HRTA in data/IDX_OWNERSHIP/HRTA (May - August 2026, the June one a correction)
 * and of NCKL, DMAS and BBCA (their large holders),
 * read as filed, and tables built word by word for what those four do not show (a table going on over a page, a
 * percentage that does not match).
 */
class RegistrationParserTest {

    static final Path DIR = Path.of("..", "data", "IDX_OWNERSHIP", "HRTA");
    static final long LISTED = 4_605_262_400L;

    static byte[] read(String month) throws IOException {
        Path file = DIR.resolve("registrasi_hrta_" + month + ".pdf");
        assumeTrue(Files.exists(file), "HRTA source data not available: " + file);
        return Files.readAllBytes(file);
    }

    /** The four directors and commissioners of HRTA: the same holdings from May to August 2026. */
    private static void hasTheFourInsiders(List<Insider> insiders) {
        assertThat(insiders).extracting(Insider::name)
                .containsExactly("CUNCUN MULIAWAN", "FENDY WIJAYA", "SANDRA SUNANTO", "ONG DENY");
        assertThat(insiders).extracting(Insider::position).containsExactly("Direksi", "Komisaris", "Direksi", "Direksi");
        assertThat(insiders).extracting(Insider::role).containsExactly(InsiderRole.DIRECTOR, InsiderRole.COMMISSIONER,
                InsiderRole.DIRECTOR, InsiderRole.DIRECTOR);
        assertThat(insiders).extracting(Insider::sharesPrevious).containsExactly(2_157_500L, 17_567_800L, 3_176_900L, 2_200_000L);
        assertThat(insiders).extracting(Insider::sharesCurrent).containsExactly(2_157_500L, 17_567_800L, 3_176_900L, 2_200_000L);
        assertThat(insiders).extracting(Insider::percentPrevious).containsExactly(new BigDecimal("0.05"),
                new BigDecimal("0.38"), new BigDecimal("0.07"), new BigDecimal("0.05"));
        assertThat(insiders).extracting(Insider::percentCurrent).containsExactly(new BigDecimal("0.05"),
                new BigDecimal("0.38"), new BigDecimal("0.07"), new BigDecimal("0.05"));
        assertThat(insiders).extracting(Insider::change).containsOnly(0L);
        assertThat(insiders).extracting(Insider::controller).containsOnly(false);
        // Fendy Wijaya is marked as an affiliate of the controller
        assertThat(insiders).extracting(Insider::controllerAffiliate).containsExactly(false, true, false, false);
        assertThat(insiders).extracting(Insider::row).containsExactly(1, 2, 3, 4);
    }

    @Test
    void readsTheMayReport() throws IOException {
        Report r = RegistrationParser.parse(read("mei"));
        assertThat(r.ticker()).isEqualTo("HRTA");
        assertThat(r.companyName()).isEqualTo("PT Hartadinata Abadi Tbk");
        assertThat(r.letterNumber()).isEqualTo("S-380/DIR-CORSEC/HRTA-OJK-IDX/VI/2026");
        assertThat(r.subject()).isEqualTo("Laporan Bulanan Registrasi Pemegang Efek");
        assertThat(r.correction()).isFalse();
        assertThat(r.period()).isEqualTo(YearMonth.of(2026, 5));
        assertThat(r.filedAt()).isEqualTo(LocalDateTime.of(2026, 6, 5, 19, 17));
        assertThat(r.registrar()).isEqualTo("PT. Sinartama Gunita");
        assertThat(r.listedSharesPrevious()).isEqualTo(LISTED);
        assertThat(r.listedSharesCurrent()).isEqualTo(LISTED);
        assertThat(r.warnings()).isEmpty();
        hasTheFourInsiders(r.insiders());
    }

    @Test
    void readsTheJuneCorrection() throws IOException {
        Report r = RegistrationParser.parse(read("juni"));
        assertThat(r.letterNumber()).isEqualTo("S-463a/DIR-CORSEC/HRTA-OJK-IDX/VII/2026");
        assertThat(r.subject()).isEqualTo("Laporan Bulanan Registrasi Pemegang Efek (KOREKSI)");
        assertThat(r.correction()).isTrue();
        assertThat(r.period()).isEqualTo(YearMonth.of(2026, 6));
        assertThat(r.filedAt()).isEqualTo(LocalDateTime.of(2026, 8, 10, 15, 14));
        assertThat(r.warnings()).isEmpty();
        hasTheFourInsiders(r.insiders());
    }

    @Test
    void readsTheJulyAndAugustReports() throws IOException {
        Report july = RegistrationParser.parse(read("juli"));
        assertThat(july.letterNumber()).isEqualTo("S-519/DIR-CORSEC/HRTA-OJK-IDX-VIII/2026");
        assertThat(july.period()).isEqualTo(YearMonth.of(2026, 7));
        assertThat(july.correction()).isFalse();
        assertThat(july.filedAt()).isEqualTo(LocalDateTime.of(2026, 8, 10, 16, 52));
        assertThat(july.warnings()).isEmpty();
        hasTheFourInsiders(july.insiders());

        // "akhir bulan Agustus" / "- 2026" over two lines
        Report august = RegistrationParser.parse(read("agustus"));
        assertThat(august.letterNumber()).isEqualTo("S-564/CORSEC-SP/HRTA/IX/2026");
        assertThat(august.period()).isEqualTo(YearMonth.of(2026, 8));
        assertThat(august.filedAt()).isEqualTo(LocalDateTime.of(2026, 9, 9, 15, 1));
        assertThat(august.warnings()).isEmpty();
        hasTheFourInsiders(august.insiders());
        // the controller; the public's row (MASYARAKAT, < 5% each) after it is no holder
        assertThat(august.holders()).singleElement().satisfies(h -> {
            assertThat(h.name()).isEqualTo("PT TERANG ANUGRAH");
            assertThat(h.sharesCurrent()).isEqualTo(3_272_048_000L);
            assertThat(h.percentCurrent()).isEqualByComparingTo("71.05");
            assertThat(h.controller()).isTrue();
        });
        assertThat(august.ownership()).isEqualTo(new Ownership(3_272_048_000L, 0L, 25_102_200L, 0L));
    }

    // ------------------------------------------------------------------ other companies' reports

    static byte[] read(String ticker, String file) throws IOException {
        Path path = Path.of("..", "data", "IDX_OWNERSHIP", ticker, file);
        assumeTrue(Files.exists(path), ticker + " source data not available: " + path);
        return Files.readAllBytes(path);
    }

    /**
     * NCKL: none of its eight directors and commissioners holds a share; its owners are in the table of the
     * shareholders of 5% or more (the controller PT Harita Jayaraya, Citibank Hong Kong) and the summary by type of
     * holder. A share count wraps in its cell ("50.344.432.00" / "0") beside an address reaching past the middle
     * between the two columns ("Lantai 5").
     */
    @Test
    void readsTheShareholdersOfFivePercentOrMoreAndTheSharesByTypeOfHolder() throws IOException {
        Report r = RegistrationParser.parse(read("NCKL", "Agustus.pdf"));
        assertThat(r.period()).isEqualTo(YearMonth.of(2026, 8));
        assertThat(r.insiders()).hasSize(8).extracting(Insider::sharesCurrent).containsOnly(0L);
        assertThat(r.holders()).extracting(Holder::name).containsExactly("PT Harita Jayaraya", "Citibank Hong Kong S/A");
        assertThat(r.holders()).extracting(Holder::sharesPrevious).containsExactly(50_344_432_000L, 3_963_201_100L);
        assertThat(r.holders()).extracting(Holder::sharesCurrent).containsExactly(50_344_432_000L, 3_958_795_700L);
        assertThat(r.holders()).extracting(Holder::percentCurrent)
                .containsExactly(new BigDecimal("79.79"), new BigDecimal("6.27"));
        assertThat(r.holders()).extracting(Holder::change).containsExactly(0L, -4_405_400L);
        assertThat(r.holders()).extracting(Holder::controller).containsExactly(true, false);
        assertThat(r.ownership()).isEqualTo(new Ownership(50_344_432_000L, 551_010_000L, 0L, 110_733_300L));
        assertThat(r.warnings()).isEmpty();
    }

    /** NCKL May 2026 writes each line of its headers as one string, the columns placed apart: read as in August. */
    @Test
    void aHeaderWrittenAsOneStringIsSplitIntoItsColumns() throws IOException {
        Report r = RegistrationParser.parse(read("NCKL", "Mei.pdf"));
        assertThat(r.period()).isEqualTo(YearMonth.of(2026, 5));
        assertThat(r.insiders()).hasSize(8);
        assertThat(r.holders()).extracting(Holder::name).containsExactly("PT Harita Jayaraya", "Citibank Hong Kong S/A");
        assertThat(r.ownership().controller()).isEqualTo(50_344_432_000L);
        assertThat(r.warnings()).isEmpty();
    }

    /** DMAS August 2026: the directors' table is left out (only its label is printed); the report is still read. */
    @Test
    void aReportWithoutTheDirectorsTableKeepsItsHolders() throws IOException {
        Report r = RegistrationParser.parse(read("DMAS", "Agustus.pdf"));
        assertThat(r.insiders()).isEmpty();
        assertThat(r.holders()).extracting(Holder::name).containsExactly("PT Sumber Arusmulia", "Sojitz Corporation");
        assertThat(r.holders()).extracting(Holder::controller).containsOnly(true);
        assertThat(r.ownership().controller()).isEqualTo(39_658_300_000L);
        assertThat(r.warnings()).singleElement().asString().contains("no table of the directors'");
    }

    /** BBCA prints its large holders' percentages of the shares issued (123,275,050,000), more than those listed. */
    @Test
    void aHoldersPercentageOfTheSharesIssuedIsNoWarning() throws IOException {
        Report r = RegistrationParser.parse(read("BBCA", "Agustus.pdf"));
        assertThat(r.holders()).singleElement().satisfies(h -> {
            assertThat(h.name()).isEqualTo("PT Dwimuria Investama");
            assertThat(h.percentCurrent()).isEqualByComparingTo("54.942");
        });
        assertThat(r.warnings()).noneMatch(w -> w.contains("5% or more"));
    }

    @Test
    void theEnglishHalfReadsTheSame() throws IOException {
        List<Page> pages = PdfWords.read(read("juli"));
        assertThat(pages).hasSize(20);
        // pages 11 - 20: "Issuer Code", "Name / Address / Position", "Number of Shares in Previous", 2,157,500
        Report r = RegistrationParser.parse(pages.subList(10, 20));
        assertThat(r.ticker()).isEqualTo("HRTA");
        assertThat(r.letterNumber()).isEqualTo("S-519/DIR-CORSEC/HRTA-OJK-IDX-VIII/2026");
        assertThat(r.period()).isEqualTo(YearMonth.of(2026, 7));
        assertThat(r.registrar()).isEqualTo("PT. Sinartama Gunita");
        assertThat(r.listedSharesCurrent()).isEqualTo(LISTED);
        assertThat(r.warnings()).isEmpty();
        hasTheFourInsiders(r.insiders());
    }

    @Test
    void aFinancialStatementIsNotARegistrationReport() throws IOException {
        Path file = Path.of("..", "data", "IDX_XBRL", "HRTA", "pdf", "FinancialStatement-2025-Tahunan-HRTA.pdf");
        assumeTrue(Files.exists(file), "HRTA source data not available: " + file);
        byte[] statement = Files.readAllBytes(file);
        assertThatThrownBy(() -> RegistrationParser.parse(statement))
                .isInstanceOf(RegistrationFormatException.class)
                .hasMessageContaining("Not a monthly registration report");
    }

    // ------------------------------------------------------------------ tables built word by word

    /** The cover page of a report of ZZRG for September 2026 (its plain text: what the cover is read from). */
    private static Page cover() {
        return new Page(1, """
                Nomor Surat 001/ZZRG/X/2026
                Nama Perusahaan PT Uji Registrasi Tbk
                Kode Emiten ZZRG
                Perihal Laporan Bulanan Registrasi Pemegang Efek
                Perseroan menyampaikan Laporan Bulanan Registrasi Pemegang Efek yang berakhir pada akhir bulan September -
                2026 Sebagai Berikut :
                Biro Administrasi Efek: PT. Biro Uji
                Jumlah saham tercatat di Bursa per akhir bulan 1.000.000.000 1.000.000.000
                Tanggal dan Waktu 06-10-2026 09:30
                """, List.of(), List.of());
    }

    /** Text placed at a column centre (as the reports centre the headers and their cells' lines). */
    private static Word at(double center, double y, String text) {
        double half = text.length() * 1.4;
        return new Word(center - half, center + half, y, text);
    }

    private static final double[] CENTERS = {132, 195, 264, 331, 387, 440, 497, 553, 609};

    /** The header of the directors' table at y 50 - 75 (Nama at 60). */
    private static List<Word> header() {
        return List.of(at(CENTERS[0], 60, "Nama"), at(CENTERS[1], 60, "Alamat"), at(CENTERS[2], 60, "Jabatan"),
                at(CENTERS[3], 55, "Jumlah Saham"), at(CENTERS[3], 65, "Sebelumnya"),
                at(CENTERS[4], 50, "Persen"), at(CENTERS[4], 60, "Saham"), at(CENTERS[4], 70, "Sebelumnya"),
                at(CENTERS[5], 55, "Jumlah Saham"), at(CENTERS[5], 65, "Bulan Ini"),
                at(CENTERS[6], 50, "Persen"), at(CENTERS[6], 60, "Saham"), at(CENTERS[6], 70, "Bulan Ini"),
                at(CENTERS[7], 60, "Pengendali"), at(CENTERS[8], 55, "Afiliasi"), at(CENTERS[8], 65, "Pengendali"));
    }

    /** A row's first line: name, position, shares and percentages (each cell's line centred in it). */
    private static List<Word> row(double y, String name, String position, String previous, String previousPct,
                                  String current, String currentPct) {
        List<Word> out = new ArrayList<>();
        out.add(at(CENTERS[0], y, name));
        out.add(at(CENTERS[1], y, "Jl. Uji"));
        out.add(at(CENTERS[2], y, position));
        out.add(at(CENTERS[3], y, previous));
        out.add(at(CENTERS[4], y, previousPct));
        out.add(at(CENTERS[5], y, current));
        out.add(at(CENTERS[6], y, currentPct));
        return out;
    }

    /** Runs as words: each run here is one word. */
    private static Page page(int number, List<Word> runs) {
        return new Page(number, "", runs, runs);
    }

    @Test
    void aTableGoingOnOverAPageKeepsItsRowsAndAWrappedName() {
        List<Word> first = new ArrayList<>(header());
        first.addAll(row(90, "BUDI", "Direktur Utama", "10.000.000", "1%", "12.500.000", "1,25%"));
        first.add(at(CENTERS[0], 101, "SANTOSO"));
        first.add(at(CENTERS[8], 101, "X"));
        first.addAll(row(112, "SITI", "Komisaris Utama", "5.000.000", "0,5%", "4.000.00", "0,4%"));
        first.add(at(CENTERS[5], 123, "0"));       // "4.000.00" / "0": a number wrapped in its cell
        first.add(at(CENTERS[0], 123, "AMINAH"));  // the last line of the page: the name goes on over the page
        List<Word> second = new ArrayList<>();
        second.add(at(CENTERS[0], 40, "LESTARI"));
        second.addAll(row(51, "DEWI", "Komisaris Independen", "-", "-", "100.000", "0,01%"));
        // the shareholders of 5% or more: another table
        second.add(at(CENTERS[0], 120, "Nama"));
        second.add(at(CENTERS[1], 120, "Alamat"));
        second.addAll(List.of(at(300, 120, "Jumlah Saham"), at(132, 140, "PT INDUK"), at(300, 140, "600.000.000")));

        Report r = RegistrationParser.parse(List.of(cover(), page(2, first), page(3, second)));
        assertThat(r.ticker()).isEqualTo("ZZRG");
        assertThat(r.period()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(r.filedAt()).isEqualTo(LocalDateTime.of(2026, 10, 6, 9, 30));
        // the stub of the shareholders' table has 3 of its 8 columns: not read, a warning only
        assertThat(r.warnings()).singleElement().asString().startsWith("The shareholders of 5% or more were not read");
        assertThat(r.holders()).isEmpty();
        assertThat(r.insiders()).extracting(Insider::name).containsExactly("BUDI SANTOSO", "SITI AMINAH LESTARI", "DEWI");
        assertThat(r.insiders()).extracting(Insider::role)
                .containsExactly(InsiderRole.DIRECTOR, InsiderRole.COMMISSIONER, InsiderRole.COMMISSIONER);
        assertThat(r.insiders()).extracting(Insider::sharesCurrent).containsExactly(12_500_000L, 4_000_000L, 100_000L);
        assertThat(r.insiders()).extracting(Insider::change).containsExactly(2_500_000L, -1_000_000L, 100_000L);
        assertThat(r.insiders()).extracting(Insider::controllerAffiliate).containsExactly(true, false, false);
        assertThat(r.insiders().get(2).sharesPrevious()).isZero();
    }

    @Test
    void aPercentageThatDoesNotMatchItsShareCountIsAWarning() {
        List<Word> words = new ArrayList<>(header());
        // 10,000,000 of 1,000,000,000 is 1 %, not 10 %
        words.addAll(row(90, "BUDI", "Direksi", "10.000.000", "1%", "10.000.000", "10%"));
        Report r = RegistrationParser.parse(List.of(cover(), page(2, words)));
        assertThat(r.insiders()).hasSize(1);
        assertThat(r.warnings()).singleElement().asString()
                .contains("row 1 (BUDI)", "10,000,000 shares are 1.0000 %", "this month", "prints 10 %");
    }

    @Test
    void aTableWithoutItsColumnsIsNotGuessed() {
        List<Word> words = new ArrayList<>(header().subList(0, 13));     // no controller columns
        words.addAll(row(90, "BUDI", "Direksi", "10.000.000", "1%", "10.000.000", "1%"));
        assertThatThrownBy(() -> RegistrationParser.parse(List.of(cover(), page(2, words))))
                .isInstanceOf(RegistrationFormatException.class)
                .hasMessageContaining("has 7 columns, expected 9");

        List<Word> broken = new ArrayList<>(header());
        broken.addAll(row(90, "BUDI", "Direksi", "10.000.0O0", "1%", "10.000.000", "1%"));
        assertThatThrownBy(() -> RegistrationParser.parse(List.of(cover(), page(2, broken))))
                .isInstanceOf(RegistrationFormatException.class)
                .hasMessageContaining("'10.000.0O0' is not a share count (row 1 (BUDI)");

        assertThatThrownBy(() -> RegistrationParser.parse(List.of(cover())))
                .isInstanceOf(RegistrationFormatException.class)
                .hasMessageContaining("table of the directors' and commissioners' shares");
    }

    @Test
    void readsTheRolesOfThePositions() {
        assertThat(InsiderRole.ofPosition("Direksi")).isEqualTo(InsiderRole.DIRECTOR);
        assertThat(InsiderRole.ofPosition("Presiden Direktur")).isEqualTo(InsiderRole.DIRECTOR);
        assertThat(InsiderRole.ofPosition("Komisaris Independen")).isEqualTo(InsiderRole.COMMISSIONER);
        assertThat(InsiderRole.ofPosition("President Commissioner")).isEqualTo(InsiderRole.COMMISSIONER);
        assertThat(InsiderRole.ofPosition("Sekretaris Perusahaan")).isEqualTo(InsiderRole.OTHER);
    }
}

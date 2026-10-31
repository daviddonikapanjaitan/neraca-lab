package com.neracalab.backend.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbook;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;
import com.neracalab.backend.ingestion.xlsx.RawSheet;

/** The text of an IDX XBRL workbook for the vector store, against HRTA's FY2025 workbook and small sheets. */
class XbrlDocumentTextTest {

    private static final String TITLE = "FinancialStatement-2025-Tahunan-HRTA";

    @Test
    void theTitleIsTheFileNameWithoutItsExtension() {
        assertThat(XbrlDocumentText.title("FinancialStatement-2025-Tahunan-HRTA.xlsx")).isEqualTo(TITLE);
        assertThat(XbrlDocumentText.title("FinancialStatement-2025-Tahunan-HRTA.XLSX")).isEqualTo(TITLE);
        assertThat(XbrlDocumentText.title("upload")).isEqualTo("upload");
    }

    @Test
    void everySheetWithTextIsReadInTheOrderOfTheFile() throws Exception {
        IdxWorkbook hrta = workbook();
        List<XbrlDocumentText.Sheet> sheets = XbrlDocumentText.sheets(hrta, TITLE);

        List<String> names = sheets.stream().map(XbrlDocumentText.Sheet::name).toList();
        assertThat(names).startsWith("1000000", "1210000").contains("1311000", "1510000", "1670000")
                .doesNotContain("Context", "InlineXBRL", "hidden", "Token");
        assertThat(names).isSubsetOf(hrta.allSheets().keySet());
        List<String> fileOrder = new ArrayList<>(hrta.allSheets().keySet());
        fileOrder.retainAll(names);
        assertThat(names).isEqualTo(fileOrder);

        XbrlDocumentText.Sheet info = sheet(sheets, "1000000");
        assertThat(info.heading()).isEqualTo(TITLE + " | [1000000] General information\ncolumns: CurrentYearInstant");
        assertThat(lines(info)).contains("Nama entitas | PT Hartadinata Abadi Tbk | Entity name", "Kode entitas | HRTA | Entity code");
    }

    /** Amounts in full (the cells hold 4.4548424151152E13), labels in both languages; rows of labels alone are left out. */
    @Test
    void aStatementRowIsItsLabelsAndAmounts() throws Exception {
        XbrlDocumentText.Sheet income = sheet(XbrlDocumentText.sheets(workbook(), TITLE), "1311000");

        assertThat(income.heading()).isEqualTo(TITLE
                + " | [1311000] Statement of profit or loss and other comprehensive income, OCI components presented net"
                + " of tax, by function - General Industry\n"
                + "columns: CurrentYearDuration | PriorYearDuration");
        assertThat(lines(income)).contains(
                "Penjualan dan pendapatan usaha | 44548424151152 | 18228628989765 | Sales and revenue",
                "Beban pokok penjualan dan pendapatan | 42626749035722 | 17131863817700 | Cost of sales and revenue");
        // an empty line of the template, the statement's title and its header are no lines
        assertThat(income.text()).doesNotContain("Dividends income", "CurrentYearDuration")
                .doesNotContain("Laporan laba rugi dan penghasilan komprehensif lain");
    }

    @Test
    void everyChunkStartsWithTheHeadingOfItsSheet() throws Exception {
        List<XbrlDocumentText.Sheet> sheets = XbrlDocumentText.sheets(workbook(), TITLE);
        List<TextChunker.Chunk> chunks = XbrlDocumentText.chunks(sheets, new TextChunker(1500, 200));

        assertThat(chunks).hasSizeGreaterThan(sheets.size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunker.Chunk chunk = chunks.get(i);
            assertThat(chunk.index()).isEqualTo(i);
            assertThat(chunk.pageFrom()).isNull();
            assertThat(chunk.pageTo()).isNull();
            // the heading of its sheet, then a piece of that sheet within the chunk size
            assertThat(sheets).anySatisfy(s -> {
                assertThat(chunk.text()).startsWith(s.heading() + "\n");
                String piece = chunk.text().substring(s.heading().length() + 1);
                assertThat(piece.length()).isBetween(1, 1500);
                // (the chunker collapses runs of spaces: a piece is part of the sheet's cleaned text)
                assertThat(String.join("\n", TextChunker.cleanLines(s.text()))).contains(piece.split("\n")[0]);
            });
        }
        // the first chunk of each sheet, in the order of the sheets
        assertThat(chunks.getFirst().text()).startsWith(sheets.getFirst().heading() + "\nNama entitas");
        assertThat(chunks).anySatisfy(c -> assertThat(c.text())
                .contains("Penjualan dan pendapatan usaha | 44548424151152 | 18228628989765 | Sales and revenue"));
    }

    @Test
    void cellsAreWrittenAsFiled() {
        assertThat(XbrlDocumentText.cell(BigDecimal.valueOf(4.4548424151152E13))).isEqualTo("44548424151152");
        assertThat(XbrlDocumentText.cell(BigDecimal.valueOf(-0.0))).isEqualTo("0");
        assertThat(XbrlDocumentText.cell(BigDecimal.valueOf(0.0))).isEqualTo("0");
        assertThat(XbrlDocumentText.cell(BigDecimal.valueOf(1.0E-5))).isEqualTo("0.00001");
        assertThat(XbrlDocumentText.cell(BigDecimal.valueOf(75.68))).isEqualTo("75.68");
        assertThat(XbrlDocumentText.cell(BigDecimal.valueOf(-1250.0))).isEqualTo("-1250");
        assertThat(XbrlDocumentText.cell(LocalDate.of(2025, 12, 31))).isEqualTo("2025-12-31");
        assertThat(XbrlDocumentText.cell("Kas\u0000 dan bank")).isEqualTo("Kas dan bank");
    }

    /** A line: an amount or a date, or three cells; two labels are none. */
    @Test
    void aRowOfLabelsAloneIsNoLine() {
        RawSheet sheet = new RawSheet("1610000", List.of(
                row("[1610000] Notes", null),
                row(null, "CurrentYearInstant"),
                row("Kebijakan akuntansi", null, "Accounting policies"),
                row("Dasar penyusunan", "Laporan keuangan disusun ...", "Basis of preparation"),
                row("Tanggal", LocalDate.of(2026, 3, 1)),
                row("Kas", BigDecimal.valueOf(1.5E9), null, "Cash"),
                row(null, null, null)));
        assertThat(XbrlDocumentText.line(sheet, 0)).isNull();
        assertThat(XbrlDocumentText.line(sheet, 1)).isNull();
        assertThat(XbrlDocumentText.line(sheet, 2)).isNull();
        assertThat(XbrlDocumentText.line(sheet, 3)).isEqualTo("Dasar penyusunan | Laporan keuangan disusun ... | Basis of preparation");
        assertThat(XbrlDocumentText.line(sheet, 4)).isEqualTo("Tanggal | 2026-03-01");
        assertThat(XbrlDocumentText.line(sheet, 5)).isEqualTo("Kas | 1500000000 | Cash");
        assertThat(XbrlDocumentText.line(sheet, 6)).isNull();
        assertThat(XbrlDocumentText.heading(sheet, "T")).isEqualTo("T | [1610000] Notes\ncolumns: CurrentYearInstant");
    }

    @Test
    void aSheetWithoutTitleOrHeaderIsNamedByItsCode() {
        RawSheet sheet = new RawSheet("1616000", List.of(row((Object) null), row("a", "b", "c")));
        assertThat(XbrlDocumentText.heading(sheet, "T")).isEqualTo("T | [1616000]");
        RawSheet comma = new RawSheet("1311000", List.of(row("[1311000] Statement of profit or loss,")));
        assertThat(XbrlDocumentText.heading(comma, "T")).isEqualTo("T | [1311000] Statement of profit or loss");
    }

    @Test
    void aWorkbookWithoutTextHasNoChunks() {
        IdxWorkbook empty = new IdxWorkbook("x.xlsx", java.util.Map.of("1000000", new RawSheet("1000000", List.of(row("Title")))),
                com.neracalab.backend.ingestion.xlsx.IdxTaxonomy.GENERAL);
        assertThat(XbrlDocumentText.chunks(XbrlDocumentText.sheets(empty, "x"), new TextChunker(1500, 200))).isEmpty();
    }

    private static List<Object> row(Object... cells) {
        return Arrays.asList(cells);
    }

    private static List<String> lines(XbrlDocumentText.Sheet sheet) {
        return List.of(sheet.text().split("\n"));
    }

    private static XbrlDocumentText.Sheet sheet(List<XbrlDocumentText.Sheet> sheets, String name) {
        return sheets.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    static IdxWorkbook workbook() throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", "HRTA", "xlsx", "FinancialStatement-2025-Tahunan-HRTA.xlsx");
        assumeTrue(Files.exists(file), "HRTA source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new IdxWorkbookReader().read(in, file.getFileName().toString());
        }
    }
}

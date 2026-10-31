package com.neracalab.backend.rag;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbook;
import com.neracalab.backend.ingestion.xlsx.RawSheet;

/**
 * The text of an IDX XBRL financial statement workbook (FinancialStatement-2025-Tahunan-HRTA.xlsx) for the RAG vector
 * store: every sheet of the file ({@link IdxWorkbook#allSheets()}: general information, the statements and every note,
 * in the order of the file; the technical sheets Context, InlineXBRL, hidden and Token are not read), each row that
 * carries something as one line of its cells joined by {@code " | "}:
 * <pre>
 * Penjualan dan pendapatan usaha | 44548424151152 | 18228628989765 | Sales and revenue
 * Nama entitas | PT Hartadinata Abadi Tbk | Entity name
 * </pre>
 * A row is a line when it holds an amount or a date, or at least three cells (a label, a value, the English label:
 * general information and the text of the notes); a row of labels alone (titles, section headings, an empty line
 * of the template) is left out. Amounts are written in full as filed (in the filing's unit, never in exponent form),
 * dates as yyyy-mm-dd.
 * <p>
 * Each sheet is chunked on its own ({@link #chunks}) and every chunk starts with its {@link Sheet#heading()}: the
 * document title, the sheet's title and its value columns, so an excerpt says by itself which filing, statement and
 * period its amounts are:
 * <pre>
 * FinancialStatement-2025-Tahunan-HRTA | [1311000] Statement of profit or loss and other comprehensive income
 * columns: CurrentYearDuration | PriorYearDuration
 * </pre>
 * The backfill of the workbooks stored before this existed ran in Python on the same rules; keep any change here in
 * step with the documents already stored (docs/v1_docs/RAG_DOCS.md).
 */
public final class XbrlDocumentText {

    /**
     * One sheet with text.
     *
     * @param name    the sheet's name in the file (1311000, 1611000PY, ...)
     * @param heading the lines each of its chunks starts with
     * @param text    its rows, one line each
     */
    public record Sheet(String name, String heading, String text) {
    }

    /** Longest sheet title kept in a heading. */
    static final int TITLE_CHARS = 200;
    /** Rows below the title searched for the header of the value columns. */
    private static final int HEADER_ROWS = 8;

    private XbrlDocumentText() {
    }

    /** "FinancialStatement-2025-Tahunan-HRTA.xlsx" -> "FinancialStatement-2025-Tahunan-HRTA" */
    public static String title(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }

    /** The sheets with at least one line, in the order of the file. */
    public static List<Sheet> sheets(IdxWorkbook workbook, String documentTitle) {
        List<Sheet> out = new ArrayList<>();
        for (RawSheet sheet : workbook.allSheets().values()) {
            List<String> lines = new ArrayList<>();
            for (int r = 0; r < sheet.rowCount(); r++) {
                String line = line(sheet, r);
                if (line != null) {
                    lines.add(line);
                }
            }
            if (!lines.isEmpty()) {
                out.add(new Sheet(sheet.name(), heading(sheet, documentTitle), String.join("\n", lines)));
            }
        }
        return out;
    }

    /**
     * The chunks of the document: each sheet split by {@code chunker}, each piece with the sheet's heading in front
     * of it; indexes run through the whole document. No pages (an .xlsx has none).
     */
    public static List<TextChunker.Chunk> chunks(List<Sheet> sheets, TextChunker chunker) {
        List<TextChunker.Chunk> out = new ArrayList<>();
        for (Sheet sheet : sheets) {
            for (TextChunker.Chunk piece : chunker.chunk(List.of(new TextChunker.Part(sheet.text(), null)))) {
                out.add(new TextChunker.Chunk(out.size(), sheet.heading() + "\n" + piece.text(), null, null));
            }
        }
        return out;
    }

    /** The row as a line, or {@code null} when it carries nothing (labels alone, empty). */
    static String line(RawSheet sheet, int row) {
        List<String> cells = new ArrayList<>();
        boolean value = false;
        for (int c = 0; c < sheet.width(row); c++) {
            Object v = sheet.cell(row, c);
            if (v == null) {
                continue;
            }
            value |= v instanceof BigDecimal || v instanceof LocalDate;
            cells.add(cell(v));
        }
        if (!value && cells.size() < 3) {
            return null;
        }
        return String.join(" | ", cells);
    }

    /**
     * "[1311000] Statement of profit or loss ..." (cell A1; trailing commas and spaces dropped) or "[1311000]", and the
     * header of its value columns: the first row under the title whose first cell is empty and whose second is not.
     */
    static String heading(RawSheet sheet, String documentTitle) {
        Object first = sheet.cell(0, 0);
        String title = first == null ? "" : cell(first).replaceAll("[, \\t\\n\\x0B\\f\\r]+\\z", "");
        if (title.isEmpty()) {
            title = "[" + sheet.name() + "]";
        }
        if (title.length() > TITLE_CHARS) {
            title = title.substring(0, TITLE_CHARS);
        }
        StringBuilder heading = new StringBuilder(documentTitle).append(" | ").append(title);
        for (int r = 1; r < Math.min(sheet.rowCount(), HEADER_ROWS); r++) {
            if (sheet.cell(r, 0) == null && sheet.cell(r, 1) != null) {
                List<String> columns = new ArrayList<>();
                for (int c = 1; c < sheet.width(r); c++) {
                    Object v = sheet.cell(r, c);
                    if (v != null) {
                        columns.add(cell(v));
                    }
                }
                heading.append("\ncolumns: ").append(String.join(" | ", columns));
                break;
            }
        }
        return heading.toString();
    }

    /**
     * A cell as text: an amount in full ({@code 4.4548424151152E13} -> 44548424151152, 0.0 -> 0), a date as
     * yyyy-mm-dd, a text as filed (without NUL characters, which PostgreSQL text cannot hold).
     */
    static String cell(Object v) {
        if (v instanceof BigDecimal d) {
            return d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString();
        }
        return v.toString().replace("\u0000", "");
    }
}

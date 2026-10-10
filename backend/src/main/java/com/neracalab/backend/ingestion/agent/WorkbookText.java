package com.neracalab.backend.ingestion.agent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbook;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;
import com.neracalab.backend.ingestion.xlsx.RawSheet;

/**
 * The text of a whole workbook as it is given to the model by the {@link FullTextExtractor}: every sheet, every
 * row that carries a figure, each row under a reference such as {@code 1311000!R6} (sheet and row number as in
 * Excel). The model answers with references, never with amounts, and {@link #value} reads the amount back from
 * the cell - so a figure that is stored always exists in the workbook.
 * <p>
 * The statement sheets (balance sheet 12xxxxx, profit or loss 13xxxxx, cash flow 15xxxxx) have one layout:
 * Indonesian label, one value column per period, English label. Their value columns are shown as c1, c2
 * (c1 = the current period, c2 = the comparative).
 */
public final class WorkbookText {

    /** The three primary statements; only rows of their sheets may be referenced. */
    public enum Kind {
        BALANCE_SHEET("12", "balance_sheet"), INCOME("13", "income_statement"), CASH_FLOW("15", "cash_flow_statement");

        private final String prefix;
        private final String table;

        Kind(String prefix, String table) {
            this.prefix = prefix;
            this.table = table;
        }

        public String table() {
            return table;
        }

        static Optional<Kind> ofSheet(String sheet) {
            for (Kind k : values()) {
                if (sheet.startsWith(k.prefix)) {
                    return Optional.of(k);
                }
            }
            return Optional.empty();
        }

        static Kind ofTable(String table) {
            for (Kind k : values()) {
                if (k.table.equals(table)) {
                    return k;
                }
            }
            throw new IllegalArgumentException(table);
        }
    }

    /** A referenced row. */
    public record Row(String ref, String sheet, int index, String label) {
    }

    /** Most rows of a notes sheet shown (the statement sheets are shown completely). */
    private static final int NOTE_ROWS = 60;
    private static final int CELL_CHARS = 80;

    private final IdxWorkbook workbook;
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final String text;
    private final boolean truncated;

    public WorkbookText(IdxWorkbook workbook, int maxChars) {
        this.workbook = workbook;
        StringBuilder sb = new StringBuilder();
        boolean cut = false;
        // general information first, then the three statements, then the notes
        List<String> order = new ArrayList<>();
        workbook.sheets().keySet().stream().filter(IdxWorkbookReader.GENERAL_INFO::equals).forEach(order::add);
        workbook.sheets().keySet().stream().filter(s -> Kind.ofSheet(s).isPresent()).forEach(order::add);
        workbook.sheets().keySet().stream().filter(s -> !order.contains(s)).forEach(order::add);
        for (String name : order) {
            String block = render(workbook.sheets().get(name));
            if (block.isEmpty()) {
                continue;
            }
            if (sb.length() + block.length() > maxChars && Kind.ofSheet(name).isEmpty()
                    && !IdxWorkbookReader.GENERAL_INFO.equals(name)) {
                cut = true;
                continue;       // a notes sheet that no longer fits; the statements always do
            }
            sb.append(block).append('\n');
        }
        this.text = sb.toString();
        this.truncated = cut;
    }

    public String text() {
        return text;
    }

    /** Some notes sheets were left out to stay within the size limit. */
    public boolean truncated() {
        return truncated;
    }

    public Optional<Row> row(String ref) {
        return Optional.ofNullable(rows.get(ref == null ? null : ref.trim()));
    }

    /** The amount of a statement row in a value column (0 = current period, 1 = comparative); null when empty. */
    public BigDecimal value(Row row, int valueColumn) {
        RawSheet sheet = workbook.sheets().get(row.sheet());
        int width = sheet.width(row.index());
        int col = 1 + valueColumn;
        if (valueColumn < 0 || col >= width - 1) {
            return null;
        }
        return sheet.cell(row.index(), col) instanceof BigDecimal d ? d : null;
    }

    /** Whether the statement sheets of a kind carry figures in a value column (at least three amounts that are not 0). */
    public boolean hasFigures(Kind kind, int valueColumn) {
        if (valueColumn < 0) {
            return false;
        }
        long amounts = rows.values().stream()
                .filter(r -> Kind.ofSheet(r.sheet()).filter(kind::equals).isPresent())
                .map(r -> value(r, valueColumn))
                .filter(v -> v != null && v.signum() != 0)
                .count();
        return amounts >= 3;
    }

    // ------------------------------------------------------------------ rendering

    private String render(RawSheet sheet) {
        if (IdxWorkbookReader.GENERAL_INFO.equals(sheet.name())) {
            return generalInformation(sheet);
        }
        boolean statement = Kind.ofSheet(sheet.name()).isPresent();
        List<String> lines = new ArrayList<>();
        int shown = 0;
        int more = 0;
        for (int r = 0; r < sheet.rowCount(); r++) {
            if (!hasAmount(sheet, r)) {
                continue;
            }
            if (!statement && shown >= NOTE_ROWS) {
                more++;
                continue;
            }
            String ref = sheet.name() + "!R" + (r + 1);
            if (statement) {
                String label = label(sheet, r);
                rows.put(ref, new Row(ref, sheet.name(), r, label));
                StringBuilder line = new StringBuilder(ref).append(" | ").append(label);
                for (int c = 1; c < sheet.width(r) - 1; c++) {
                    Object v = sheet.cell(r, c);
                    line.append(" | c").append(c).append('=').append(v instanceof BigDecimal d ? plain(d) : "-");
                }
                lines.add(line.toString());
            } else {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < sheet.width(r); c++) {
                    Object v = sheet.cell(r, c);
                    if (v != null && !v.toString().isBlank()) {
                        cells.add(v instanceof BigDecimal d ? plain(d) : abbreviate(v.toString()));
                    }
                }
                lines.add(String.join(" | ", cells));
            }
            shown++;
        }
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder("## Sheet ").append(sheet.name());
        String title = sheet.text(0, 0);
        if (title != null && !title.isBlank()) {
            block.append(": ").append(abbreviate(title.trim()));
        }
        block.append('\n');
        if (statement) {
            block.append("columns: ").append(columns(sheet)).append('\n');
        }
        lines.forEach(l -> block.append(l).append('\n'));
        if (more > 0) {
            block.append("(").append(more).append(" more rows not shown)\n");
        }
        return block.toString();
    }

    private static String generalInformation(RawSheet sheet) {
        StringBuilder block = new StringBuilder("## Sheet ").append(sheet.name()).append(": General information\n");
        for (int r = 0; r < sheet.rowCount(); r++) {
            String english = sheet.text(r, 2);
            Object value = sheet.cell(r, 1);
            if (english != null && value != null && !value.toString().isBlank()) {
                block.append(english.trim()).append(": ").append(abbreviate(value.toString().trim())).append('\n');
            }
        }
        return block.toString();
    }

    /** "c1=CurrentYearInstant, c2=PriorEndYearInstant": the header row above the first amount. */
    private static String columns(RawSheet sheet) {
        for (int r = 1; r < Math.min(sheet.rowCount(), 8); r++) {
            if (sheet.cell(r, 0) == null && sheet.width(r) > 2 && sheet.cell(r, 1) != null) {
                List<String> names = new ArrayList<>();
                for (int c = 1; c < sheet.width(r) - 1; c++) {
                    Object v = sheet.cell(r, c);
                    names.add("c" + c + "=" + (v == null ? "?" : v.toString().trim()));
                }
                return String.join(", ", names);
            }
        }
        return "c1=current period, c2=comparative";
    }

    private static boolean hasAmount(RawSheet sheet, int row) {
        for (int c = 0; c < sheet.width(row); c++) {
            if (sheet.cell(row, c) instanceof BigDecimal) {
                return true;
            }
        }
        return false;
    }

    /** The English label (last cell), else the Indonesian one (first cell). */
    private static String label(RawSheet sheet, int row) {
        int width = sheet.width(row);
        Object english = width == 0 ? null : sheet.cell(row, width - 1);
        if (english instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        Object first = sheet.cell(row, 0);
        return first == null ? "(no label)" : first.toString().trim();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String abbreviate(String text) {
        String oneLine = text.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= CELL_CHARS ? oneLine : oneLine.substring(0, CELL_CHARS) + "...";
    }
}

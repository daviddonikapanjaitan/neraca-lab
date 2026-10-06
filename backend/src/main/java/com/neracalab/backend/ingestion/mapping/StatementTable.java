package com.neracalab.backend.ingestion.mapping;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.neracalab.backend.ingestion.xlsx.RawSheet;

/**
 * A statement sheet of the IDX template (1210000, 1311000, 1510000, ...): one header row with the
 * XBRL contexts (CurrentYearDuration, PriorYearDuration, CurrentYearInstant, ...), then one row per
 * line item: Indonesian label | value per context | English label.
 */
public final class StatementTable {

    /** One line item. {@code values[i]} belongs to context {@code i}; {@code null} = not reported. */
    public record Line(int row, String label, BigDecimal[] values) {

        public BigDecimal value(int contextIndex) {
            return contextIndex >= 0 && contextIndex < values.length ? values[contextIndex] : null;
        }

        public boolean hasValue(int contextIndex) {
            return value(contextIndex) != null;
        }
    }

    private final String sheet;
    private final List<String> contexts;
    private final List<Line> lines;                      // every labelled row, in sheet order
    private final Map<String, Line> valued = new LinkedHashMap<>();
    private final List<String> duplicates = new ArrayList<>();

    private StatementTable(String sheet, List<String> contexts, List<Line> lines) {
        this.sheet = sheet;
        this.contexts = contexts;
        this.lines = lines;
        for (Line line : lines) {
            boolean hasAny = Arrays.stream(line.values()).anyMatch(Objects::nonNull);
            Line existing = valued.get(line.label());
            if (existing == null || !Arrays.stream(existing.values()).anyMatch(Objects::nonNull)) {
                if (hasAny || existing == null) {
                    valued.put(line.label(), line);
                }
            } else if (hasAny) {
                duplicates.add(line.label());
            }
        }
    }

    public static StatementTable parse(RawSheet sheet) {
        int header = -1;
        List<String> contexts = new ArrayList<>();
        for (int r = 0; r < sheet.rowCount() && header < 0; r++) {
            String first = sheet.text(r, 1);
            if (sheet.cell(r, 0) == null && first != null && isContextHeader(first)) {
                header = r;
                for (int c = 1; sheet.text(r, c) != null; c++) {
                    contexts.add(sheet.text(r, c));
                }
            }
        }
        if (header < 0) {
            throw new IllegalArgumentException("Sheet " + sheet.name() + " has no context header row");
        }
        int labelCol = 1 + contexts.size();
        List<Line> lines = new ArrayList<>();
        for (int r = header + 1; r < sheet.rowCount(); r++) {
            String label = sheet.text(r, labelCol);
            if (label == null) {
                continue;
            }
            BigDecimal[] values = new BigDecimal[contexts.size()];
            for (int c = 0; c < contexts.size(); c++) {
                values[c] = sheet.number(r, 1 + c);
            }
            lines.add(new Line(r + 1, label, values));
        }
        return new StatementTable(sheet.name(), List.copyOf(contexts), List.copyOf(lines));
    }

    /**
     * "CurrentYearDuration", "PriorEndYearInstant", ... or, in the pre-2023 IDX template, the period
     * date ("31 December 2022"). Contexts are used by position (current first, then prior).
     */
    static boolean isContextHeader(String text) {
        return text.endsWith("Duration") || text.endsWith("Instant") || isDateHeader(text);
    }

    static boolean isDateHeader(String text) {
        return text.matches("\\d{1,2} \\p{L}+ \\d{4}");
    }

    public String sheet() {
        return sheet;
    }

    public List<String> contexts() {
        return contexts;
    }

    public List<Line> lines() {
        return lines;
    }

    /** Labels that carry values on more than one row (ambiguous, first one is used). */
    public List<String> duplicates() {
        return duplicates;
    }

    /** Raw reported value of a line in a context, {@code null} when absent. */
    public BigDecimal value(String label, int contextIndex) {
        Line line = valued.get(label);
        return line == null ? null : line.value(contextIndex);
    }

    public boolean hasData(int contextIndex) {
        return lines.stream().anyMatch(l -> l.hasValue(contextIndex));
    }

    /** Index of the first line with the given label in sheet order, or -1. */
    public int indexOf(String label) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).label().equals(label)) {
                return i;
            }
        }
        return -1;
    }
}

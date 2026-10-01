package com.neracalab.backend.ingestion.xlsx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Cell values of one worksheet, detached from Apache POI. A cell is {@code null}, a trimmed
 * non-empty {@link String}, a {@link BigDecimal} or a {@link LocalDate}.
 */
public record RawSheet(String name, List<List<Object>> rows) {

    public int rowCount() {
        return rows.size();
    }

    public Object cell(int row, int col) {
        if (row < 0 || row >= rows.size()) {
            return null;
        }
        List<Object> r = rows.get(row);
        return col < 0 || col >= r.size() ? null : r.get(col);
    }

    public String text(int row, int col) {
        Object v = cell(row, col);
        return v == null ? null : v.toString();
    }

    public BigDecimal number(int row, int col) {
        Object v = cell(row, col);
        if (v instanceof BigDecimal d) {
            return d;
        }
        if (v instanceof String s) {
            try {
                return new BigDecimal(s.replace(",", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public int width(int row) {
        return row < 0 || row >= rows.size() ? 0 : rows.get(row).size();
    }
}

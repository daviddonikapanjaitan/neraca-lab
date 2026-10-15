package com.neracalab.backend.macro.source;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import com.neracalab.backend.crawl.PoliteHttpClient;
import com.neracalab.backend.crawl.SourceException;
import com.neracalab.backend.macro.MacroProperties;
import com.neracalab.backend.macro.MacroSeries;

/**
 * A row of a Bank Indonesia SEKI table (Statistik Ekonomi dan Keuangan Indonesia, {@code /SEKI/tabel/TABEL5_9.xls}):
 * {@code source_ref} = {@code TABLE.xls#row label}, e.g. {@code TABEL5_9.xls#Total} (the reserves) or
 * {@code TABEL1_1_1.xls#Uang Beredar Luas} (M2). The months run across the columns: a row of month names (Jan .. Des,
 * an asterisk marks a provisional figure) under a row of years written once per year. The year of each month column is
 * counted from the first year label (a month not after the one before starts a new year) and every year label must
 * agree with it; a column without a month (the yearly total) is skipped. Anything else fails: nothing is guessed.
 */
@Component
public class BiSekiSource implements MacroSource {

    private final PoliteHttpClient http;
    private final MacroProperties properties;

    public BiSekiSource(PoliteHttpClient http, MacroProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "bi_seki";
    }

    @Override
    public List<Observation> fetch(MacroSeries series, LocalDate from, LocalDate today) {
        String ref = series.sourceRef();
        int hash = ref == null ? -1 : ref.indexOf('#');
        if (hash <= 0 || hash == ref.length() - 1) {
            throw new SourceException("SEKI " + series.code() + ": source_ref must be 'TABLE.xls#row label', not '" + ref + "'");
        }
        String table = ref.substring(0, hash).trim();
        if (!table.matches("TABEL[0-9_]+\\.xlsx?")) {
            throw new SourceException("SEKI " + series.code() + ": '" + table + "' is not a SEKI table");
        }
        URI uri = URI.create(properties.biSekiBaseUrl() + "/" + table);
        PoliteHttpClient.Response response = http.get(uri);
        PoliteHttpClient.requireOk(uri, response);
        return parse(response.body(), ref.substring(hash + 1).trim(), table);
    }

    static List<Observation> parse(byte[] workbook, String label, String table) {
        DataFormatter text = new DataFormatter(Locale.US);
        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(workbook))) {
            Sheet sheet = book.getSheetAt(0);
            int dataRow = findRow(sheet, text, label);
            if (dataRow < 0) {
                throw new SourceException("SEKI " + table + ": no row '" + label + "'");
            }
            int monthRow = -1;
            for (int r = dataRow - 1; r >= Math.max(0, dataRow - 15) && monthRow < 0; r--) {
                if (monthCells(sheet.getRow(r), text) >= 12) {
                    monthRow = r;
                }
            }
            if (monthRow < 1) {
                throw new SourceException("SEKI " + table + ": no row of month names above '" + label + "'");
            }
            Row months = sheet.getRow(monthRow);
            Row years = sheet.getRow(monthRow - 1);
            Row values = sheet.getRow(dataRow);
            List<Observation> out = new ArrayList<>();
            Integer year = null;
            int previousMonth = 0;
            for (int c = 0; c < months.getLastCellNum(); c++) {
                Integer month = IndonesianDates.month(text.formatCellValue(months.getCell(c)));
                Integer label1 = year(years == null ? null : years.getCell(c), text);
                if (month == null) {
                    continue;   // the label column, a yearly total
                }
                if (year == null) {
                    year = label1 != null ? label1 : yearLeftOf(years, c, text);
                    if (year == null) {
                        throw new SourceException("SEKI " + table + ": no year above the first month column");
                    }
                } else if (month <= previousMonth) {
                    year++;
                }
                if (label1 != null && !label1.equals(year)) {
                    throw new SourceException("SEKI " + table + ": the year label " + label1 + " of column " + (c + 1)
                            + " does not follow the months (counted " + year + ")");
                }
                previousMonth = month;
                BigDecimal value = number(values.getCell(c), text);
                if (value != null) {
                    out.add(new Observation(LocalDate.of(year, month, 1), value));
                }
            }
            if (out.isEmpty()) {
                throw new SourceException("SEKI " + table + ": the row '" + label + "' has no monthly value");
            }
            return out;
        } catch (IOException e) {
            throw new SourceException("SEKI " + table + ": the table could not be read: " + e.getMessage(), e);
        }
    }

    /** The first row whose label (one of the first four columns) starts with {@code label}. */
    private static int findRow(Sheet sheet, DataFormatter text, String label) {
        String wanted = normalize(label);
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (int c = 0; c < 4; c++) {
                String v = normalize(text.formatCellValue(row.getCell(c)));
                if (!v.isEmpty() && v.startsWith(wanted)) {
                    return r;
                }
            }
        }
        return -1;
    }

    private static int monthCells(Row row, DataFormatter text) {
        if (row == null) {
            return 0;
        }
        int n = 0;
        for (Cell cell : row) {
            if (IndonesianDates.month(text.formatCellValue(cell)) != null) {
                n++;
            }
        }
        return n;
    }

    private static Integer year(Cell cell, DataFormatter text) {
        if (cell == null) {
            return null;
        }
        String v = text.formatCellValue(cell).trim().replace("*", "");
        if (!v.matches("(19|20)\\d\\d(\\.0+)?")) {
            return null;
        }
        return Integer.parseInt(v.substring(0, 4));
    }

    private static Integer yearLeftOf(Row years, int col, DataFormatter text) {
        if (years == null) {
            return null;
        }
        for (int c = col; c >= 0; c--) {
            Integer y = year(years.getCell(c), text);
            if (y != null) {
                return y;
            }
        }
        return null;
    }

    private static BigDecimal number(Cell cell, DataFormatter text) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC
                || cell.getCellType() == CellType.FORMULA && cell.getCachedFormulaResultType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue());
        }
        String v = text.formatCellValue(cell).trim().replace(",", "");
        if (v.isEmpty() || v.equals("-")) {
            return null;
        }
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}

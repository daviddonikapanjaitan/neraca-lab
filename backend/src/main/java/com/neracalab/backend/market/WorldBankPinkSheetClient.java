package com.neracalab.backend.market;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.neracalab.backend.crawl.PoliteHttpClient;
import com.neracalab.backend.crawl.SourceException;

/**
 * Monthly commodity prices of the World Bank Pink Sheet (CMO-Historical-Data-Monthly.xlsx, sheet "Monthly Prices"): one
 * value per month and commodity, the monthly average in nominal US dollars. The workbook's address changes with every
 * release, so it is taken from the link on the Commodity Markets page. A layout the parser does not know (no column of
 * that name, no month in the first column) fails loudly; nothing is guessed. One workbook serves every monthly
 * commodity (coal, nickel, tin, rubber, LNG): it is kept for {@link #CACHE_FOR}, so "Update all commodities" downloads it
 * once.
 */
@Component
public class WorldBankPinkSheetClient {

    static final String SOURCE = "worldbank";

    private static final Pattern FILE_LINK = Pattern.compile(
            "https://thedocs\\.worldbank\\.org/[^\"'\\s<>]+CMO-Historical-Data-Monthly\\.xlsx");
    /** "2026M09" */
    private static final Pattern MONTH = Pattern.compile("(\\d{4})M(\\d{2})");

    /** A month and its value. */
    public record MonthlyValue(LocalDate month, double value) {
    }

    /** How long a downloaded workbook is reused (it changes once a month). */
    static final Duration CACHE_FOR = Duration.ofMinutes(10);

    private final PoliteHttpClient http;
    private final MarketProperties properties;
    private final Clock clock;
    private byte[] workbook;
    private Instant downloadedAt;

    @Autowired
    public WorldBankPinkSheetClient(PoliteHttpClient http, MarketProperties properties) {
        this(http, properties, Clock.systemUTC());
    }

    WorldBankPinkSheetClient(PoliteHttpClient http, MarketProperties properties, Clock clock) {
        this.http = http;
        this.properties = properties;
        this.clock = clock;
    }

    /** Every month of the column {@code column} ("Coal, Australian"), oldest first; months without a value are left out. */
    public List<MonthlyValue> monthly(String column) {
        return parse(workbook(), properties.worldBank().sheet(), column);
    }

    /** The workbook, downloaded again when older than {@link #CACHE_FOR}. */
    private synchronized byte[] workbook() {
        Instant now = clock.instant();
        if (workbook != null && downloadedAt.plus(CACHE_FOR).isAfter(now)) {
            return workbook;
        }
        URI file = fileUri();
        PoliteHttpClient.Response response = http.get(file);
        PoliteHttpClient.requireOk(file, response);
        workbook = response.body();
        downloadedAt = now;
        return workbook;
    }

    private URI fileUri() {
        String configured = properties.worldBank().fileUrl();
        if (configured != null && !configured.isBlank()) {
            return URI.create(configured.trim());
        }
        URI page = URI.create(properties.worldBank().pageUrl());
        Matcher m = FILE_LINK.matcher(http.getText(page));
        if (!m.find()) {
            throw new SourceException("The World Bank Commodity Markets page (" + PoliteHttpClient.describe(page)
                    + ") no longer links CMO-Historical-Data-Monthly.xlsx; set neracalab.markets.world-bank.file-url");
        }
        return URI.create(m.group());
    }

    /** The months of {@code column} in a Pink Sheet workbook. */
    static List<MonthlyValue> parse(byte[] workbook, String sheetName, String column) {
        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
            Sheet sheet = book.getSheet(sheetName);
            if (sheet == null) {
                throw new SourceException("The World Bank workbook has no sheet '" + sheetName + "'");
            }
            int headerRow = -1;
            int col = -1;
            for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 20) && col < 0; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                for (Cell cell : row) {
                    if (cell.getCellType() == CellType.STRING
                            && normalize(cell.getStringCellValue()).equals(normalize(column))) {
                        headerRow = r;
                        col = cell.getColumnIndex();
                        break;
                    }
                }
            }
            if (col < 0) {
                throw new SourceException("The World Bank sheet '" + sheetName + "' has no column '" + column + "'");
            }
            List<MonthlyValue> out = new ArrayList<>();
            for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || row.getCell(0) == null || row.getCell(0).getCellType() != CellType.STRING) {
                    continue;
                }
                Matcher m = MONTH.matcher(row.getCell(0).getStringCellValue().trim());
                if (!m.matches()) {
                    continue;   // the unit row under the header, notes
                }
                Cell cell = row.getCell(col);
                Double value = number(cell);
                if (value == null) {
                    continue;   // "…": no price that month
                }
                if (value <= 0) {
                    throw new SourceException("The World Bank '" + column + "' of " + m.group() + " is " + value);
                }
                out.add(new MonthlyValue(LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 1), value));
            }
            if (out.isEmpty()) {
                throw new SourceException("The World Bank column '" + column + "' has no monthly value");
            }
            for (int i = 1; i < out.size(); i++) {
                if (!out.get(i).month().isAfter(out.get(i - 1).month())) {
                    throw new SourceException("The World Bank months are not in order at " + out.get(i).month());
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            if (e instanceof SourceException se) {
                throw se;
            }
            throw new SourceException("The World Bank workbook could not be read: " + e.getMessage(), e);
        }
    }

    private static Double number(Cell cell) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        }
        if (cell.getCellType() == CellType.FORMULA && cell.getCachedFormulaResultType() == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        }
        if (cell.getCellType() == CellType.STRING) {
            String s = cell.getStringCellValue().trim();
            try {
                return s.isEmpty() ? null : Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String normalize(String s) {
        return s.replaceAll("[\\s*]+", " ").trim().toLowerCase(Locale.ROOT);
    }
}

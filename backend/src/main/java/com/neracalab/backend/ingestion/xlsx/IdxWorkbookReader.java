package com.neracalab.backend.ingestion.xlsx;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Reads an IDX XBRL workbook with Apache POI into an {@link IdxWorkbook}. Only the sheets the
 * ingestion needs are kept; the workbook is closed before returning.
 */
@Component
public class IdxWorkbookReader {

    /** General information sheet; its presence identifies an IDX XBRL workbook. */
    public static final String GENERAL_INFO = "1000000";

    private static final Set<String> SHEETS = Set.of(
            GENERAL_INFO,
            IdxSheets.BALANCE_SHEET, IdxSheets.BALANCE_SHEET_LIQUIDITY,
            IdxSheets.INCOME_BY_FUNCTION, IdxSheets.INCOME_BY_FUNCTION_BEFORE_TAX,
            IdxSheets.INCOME_BY_NATURE, IdxSheets.INCOME_BY_NATURE_BEFORE_TAX,
            IdxSheets.EQUITY, IdxSheets.EQUITY_PRIOR_YEAR,
            IdxSheets.CASH_FLOW_DIRECT, IdxSheets.CASH_FLOW_INDIRECT,
            IdxSheets.PPE, IdxSheets.PPE_PRIOR_YEAR, IdxSheets.RIGHT_OF_USE, IdxSheets.RIGHT_OF_USE_PRIOR_YEAR,
            IdxSheets.REVENUE_BY_TYPE, IdxSheets.REVENUE_BY_SOURCE);

    static {
        // Pre-2023 IDX workbooks compress xl/styles.xml ~140:1, past POI's default 100:1 zip-bomb
        // ratio. Allow 1000:1, but bound every entry at 100 MB (real filings: under 6 MB).
        ZipSecureFile.setMinInflateRatio(0.001);
        ZipSecureFile.setMaxEntrySize(100L * 1024 * 1024);
    }

    public IdxWorkbook read(InputStream in, String fileName) {
        Map<String, RawSheet> sheets = new LinkedHashMap<>();
        Set<IdxTaxonomy> taxonomies = EnumSet.noneOf(IdxTaxonomy.class);
        try (Workbook wb = new XSSFWorkbook(in)) {
            for (Sheet sheet : wb) {
                String name = canonicalName(sheet.getSheetName());
                if (SHEETS.contains(name)) {
                    sheets.put(name, toRaw(sheet, name));
                    if (!name.equals(GENERAL_INFO)) {
                        taxonomies.add(IdxTaxonomy.of(filedName(sheet.getSheetName())));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new IdxWorkbookException("Cannot read '" + fileName + "' as an .xlsx workbook: " + e.getMessage(), e);
        }
        if (!sheets.containsKey(GENERAL_INFO)) {
            throw new IdxWorkbookException("'" + fileName + "' is not an IDX XBRL financial statement workbook "
                    + "(sheet " + GENERAL_INFO + " 'General information' is missing).");
        }
        if (taxonomies.size() > 1) {
            throw new IdxWorkbookException("'" + fileName + "' mixes the statement sheets of several IDX taxonomies " + taxonomies);
        }
        return new IdxWorkbook(fileName, sheets, taxonomies.stream().findFirst().orElse(IdxTaxonomy.GENERAL));
    }

    /**
     * Sheet names of the "General Industry" taxonomy ({@link IdxSheets}):
     * <ul>
     *   <li>pre-2023 template: "1410000 1 CurrentYear" -> 1410000, "1410000 2 PriorYear" -> 1410000PY
     *       (the names of the current template);</li>
     *   <li>"Infrastructure Industry" taxonomy (e.g. SMDR): the same roles and line items under codes
     *       starting with 3 instead of 1 (3210000 balance sheet, 3311000 profit or loss, 3410000 equity,
     *       3510000 cash flow, 3611000 / 3612000 / 3617000 / 3618000 notes) -> 1210000, 1311000, ...</li>
     *   <li>"Financial and Sharia Industry" taxonomy (banks, e.g. BNGA): the same roles under codes
     *       starting with 4 (4220000 balance sheet by order of liquidity, 4312000 / 4322000 profit or loss
     *       by nature, 4410000 equity, 4510000 / 4520000 cash flow, 4611000 / 4612000 notes) -> 1220000,
     *       1312000, ...; its line items differ, see {@link IdxTaxonomy#FINANCIAL}.</li>
     * </ul>
     * Sheet 1000000 (general information) is shared by all taxonomies.
     */
    static String canonicalName(String sheetName) {
        String name = filedName(sheetName);
        if (name.matches("[34]\\d{6}(PY)?")) {
            name = "1" + name.substring(1);
        }
        return name;
    }

    /** Sheet name in the current template, with the code as filed: "4410000 2 PriorYear" -> 4410000PY. */
    private static String filedName(String sheetName) {
        String name = sheetName.trim();
        if (name.matches("\\d{7} \\d+ CurrentYear")) {
            name = name.substring(0, 7);
        } else if (name.matches("\\d{7} \\d+ PriorYear")) {
            name = name.substring(0, 7) + "PY";
        }
        return name;
    }

    private static RawSheet toRaw(Sheet sheet, String name) {
        List<List<Object>> rows = new ArrayList<>();
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            List<Object> cells = new ArrayList<>();
            if (row != null && row.getLastCellNum() > 0) {
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    cells.add(value(row.getCell(c)));
                }
            }
            rows.add(cells);
        }
        return new RawSheet(name, rows);
    }

    private static Object value(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate()
                    // Double.toString gives the shortest exact decimal form (75.68, not 75.6799999...)
                    : BigDecimal.valueOf(cell.getNumericCellValue());
            case STRING -> {
                String s = cell.getStringCellValue().trim();
                yield s.isEmpty() ? null : s;
            }
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            default -> null;
        };
    }
}

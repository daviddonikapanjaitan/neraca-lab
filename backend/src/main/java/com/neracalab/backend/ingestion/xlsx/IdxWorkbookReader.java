package com.neracalab.backend.ingestion.xlsx;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public IdxWorkbook read(InputStream in, String fileName) {
        Map<String, RawSheet> sheets = new LinkedHashMap<>();
        try (Workbook wb = new XSSFWorkbook(in)) {
            for (Sheet sheet : wb) {
                if (SHEETS.contains(sheet.getSheetName())) {
                    sheets.put(sheet.getSheetName(), toRaw(sheet));
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new IdxWorkbookException("Cannot read '" + fileName + "' as an .xlsx workbook: " + e.getMessage(), e);
        }
        if (!sheets.containsKey(GENERAL_INFO)) {
            throw new IdxWorkbookException("'" + fileName + "' is not an IDX XBRL financial statement workbook "
                    + "(sheet " + GENERAL_INFO + " 'General information' is missing).");
        }
        return new IdxWorkbook(fileName, sheets);
    }

    private static RawSheet toRaw(Sheet sheet) {
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
        return new RawSheet(sheet.getSheetName(), rows);
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

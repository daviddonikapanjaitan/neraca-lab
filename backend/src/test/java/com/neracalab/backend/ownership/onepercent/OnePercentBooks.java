package com.neracalab.backend.ownership.onepercent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Workbooks laid out as KSEI's monthly lists of the shareholders of more than 1%: the disclaimer, "[PUBLIC]", the
 * header row ({@code INVESTOR_TYPE} codes as before April 2026, or {@code INVESTOR_CLASSIFICATION}), one row per
 * holding with its DATE as an Excel date.
 */
final class OnePercentBooks {

    private OnePercentBooks() {
    }

    /** A holding: ticker, investor, type, local / foreign, scripless, scrip, total, percentage. */
    static Object[] holding(String ticker, String investor, String type, String localForeign, long scripless, long scrip,
                            long total, double percent) {
        return new Object[] {ticker, investor, type, localForeign, scripless, scrip, total, percent};
    }

    static byte[] workbook(LocalDate date, boolean typeCodes, Object[]... holdings) throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Sheet 1");
            CellStyle dated = book.createCellStyle();
            dated.setDataFormat(book.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            sheet.createRow(0).createCell(0).setCellValue("*Penafian:\n1. Sumber data saham scripless ...");
            sheet.createRow(2).createCell(0).setCellValue("*Disclaimer:\n1. Scripless share data ...");
            sheet.createRow(3).createCell(0).setCellValue("[PUBLIC]");
            String[] header = {"DATE", "SHARE_CODE", "ISSUER_NAME", "INVESTOR_NAME",
                    typeCodes ? "INVESTOR_TYPE" : "INVESTOR_CLASSIFICATION", "LOCAL_FOREIGN", "NATIONALITY", "DOMICILE",
                    "HOLDINGS_SCRIPLESS", "HOLDINGS_SCRIP", "TOTAL_HOLDING_SHARES", "PERCENTAGE"};
            Row h = sheet.createRow(5);
            for (int c = 0; c < header.length; c++) {
                h.createCell(c).setCellValue(header[c]);
            }
            int r = 6;
            for (Object[] x : holdings) {
                Row row = sheet.createRow(r++);
                if (x.length == 0) {
                    // a junk row: a value in a column without a header only
                    row.createCell(14).setCellValue("#DIV/0!");
                    continue;
                }
                row.createCell(0).setCellValue(date);
                row.getCell(0).setCellStyle(dated);
                row.createCell(1).setCellValue((String) x[0]);
                row.createCell(2).setCellValue("PT UJI " + x[0] + " Tbk");
                row.createCell(3).setCellValue((String) x[1]);
                if (x[2] != null) {
                    row.createCell(4).setCellValue((String) x[2]);
                }
                if (x[3] != null) {
                    row.createCell(5).setCellValue((String) x[3]);
                }
                if ("ID".equals(x[2]) || "Individual".equals(x[2])) {
                    row.createCell(6).setCellValue("INDONESIAN");
                }
                row.createCell(7).setCellValue("A".equals(x[3]) || "F".equals(x[3]) ? "SINGAPORE" : "INDONESIA");
                row.createCell(8).setCellValue(((Number) x[4]).doubleValue());
                row.createCell(9).setCellValue(((Number) x[5]).doubleValue());
                row.createCell(10).setCellValue(((Number) x[6]).doubleValue());
                row.createCell(11).setCellValue(((Number) x[7]).doubleValue());
            }
            book.write(out);
            return out.toByteArray();
        }
    }
}

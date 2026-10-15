package com.neracalab.backend.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import com.neracalab.backend.crawl.SourceException;

/**
 * The World Bank Pink Sheet parser on a workbook laid out like CMO-Historical-Data-Monthly.xlsx (title rows, the
 * commodity names in a header row, the units under it, months as "2026M09", "…" for a month without a price).
 */
class WorldBankPinkSheetClientTest {

    private static byte[] workbook() throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            book.createSheet("AFOSHEET");
            Sheet sheet = book.createSheet("Monthly Prices");
            sheet.createRow(0).createCell(0).setCellValue("World Bank Commodity Price Data (The Pink Sheet)");
            sheet.createRow(3).createCell(0).setCellValue("Updated on October 02, 2026");
            Row header = sheet.createRow(4);
            header.createCell(1).setCellValue("Crude oil, Brent");
            header.createCell(2).setCellValue("Coal, Australian");
            header.createCell(3).setCellValue("Coal, South African **");
            Row units = sheet.createRow(5);
            units.createCell(1).setCellValue("($/bbl)");
            units.createCell(2).setCellValue("($/mt)");
            Object[][] rows = {{"1960M01", 1.63, "…"}, {"2026M07", 70.1, 131.9}, {"2026M08", 71.2, 135.2},
                    {"2026M09", 72.3, 147.1}};
            int r = 6;
            for (Object[] values : rows) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue((String) values[0]);
                row.createCell(1).setCellValue((Double) values[1]);
                if (values[2] instanceof Double d) {
                    row.createCell(2).setCellValue(d);
                } else {
                    row.createCell(2).setCellValue((String) values[2]);
                }
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void readsTheMonthsOfOneColumn() throws IOException {
        List<WorldBankPinkSheetClient.MonthlyValue> coal = WorldBankPinkSheetClient.parse(workbook(), "Monthly Prices",
                "Coal, Australian");
        assertThat(coal).extracting(WorldBankPinkSheetClient.MonthlyValue::month).containsExactly(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1));
        assertThat(coal.getLast().value()).isEqualTo(147.1);
    }

    @Test
    void anUnknownColumnOrSheetFails() throws IOException {
        byte[] book = workbook();
        assertThatThrownBy(() -> WorldBankPinkSheetClient.parse(book, "Monthly Prices", "Coal, Indonesian"))
                .isInstanceOf(SourceException.class).hasMessageContaining("no column 'Coal, Indonesian'");
        assertThatThrownBy(() -> WorldBankPinkSheetClient.parse(book, "Monthly Indices", "Coal, Australian"))
                .isInstanceOf(SourceException.class).hasMessageContaining("no sheet");
        assertThatThrownBy(() -> WorldBankPinkSheetClient.parse(new byte[] {1, 2, 3}, "Monthly Prices", "Coal, Australian"))
                .isInstanceOf(SourceException.class);
    }
}

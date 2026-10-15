package com.neracalab.backend.macro.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import com.neracalab.backend.crawl.SourceException;

import tools.jackson.databind.json.JsonMapper;

/**
 * The macro source parsers on answers shaped like the real ones (checked on 2026-10-15): the FRED graph CSV, the CNBC
 * bars, the CFTC report rows, the Bank Indonesia page form and its .xlsx export, a SEKI .xls table. A layout the parser
 * does not know fails; nothing is guessed.
 */
class MacroSourceParsersTest {

    private final JsonMapper json = JsonMapper.builder().build();

    // ------------------------------------------------------------------ FRED

    @Test
    void fredCsvSkipsDaysWithoutAValue() {
        String csv = "observation_date,DGS10\n2026-10-09,5.24\n2026-10-12,\n2026-10-13,5.21\n2026-10-14,.\n";
        List<MacroSource.Observation> obs = FredSource.parse(csv, "DGS10");
        assertThat(obs).containsExactly(new MacroSource.Observation(LocalDate.of(2026, 10, 9), new BigDecimal("5.24")),
                new MacroSource.Observation(LocalDate.of(2026, 10, 13), new BigDecimal("5.21")));
    }

    @Test
    void fredOtherSeriesOrHtmlFails() {
        assertThatThrownBy(() -> FredSource.parse("observation_date,DGS2\n2026-10-09,4.8\n", "DGS10"))
                .isInstanceOf(SourceException.class).hasMessageContaining("unexpected header");
        assertThatThrownBy(() -> FredSource.parse("<html>Error</html>", "DGS10")).isInstanceOf(SourceException.class);
        assertThatThrownBy(() -> FredSource.parse("observation_date,DGS10\nyesterday,5\n", "DGS10"))
                .isInstanceOf(SourceException.class);
    }

    // ------------------------------------------------------------------ CNBC

    @Test
    void cnbcBarsLeaveOutTodayAndTheWeekend() {
        String body = """
                {"barData":{"priceBars":[
                  {"open":"7.1670","high":"7.1680","low":"7.1670","close":"7.1680","volume":0,"tradeTime":"20261007000000"},
                  {"open":"7.2210","high":"7.2210","low":"7.2210","close":"7.2210","volume":0,"tradeTime":"20261011000000"},
                  {"open":"7.1690","high":"7.1690","low":"7.1690","close":"7.1690","volume":0,"tradeTime":"20261014000000"},
                  {"open":"7.1700","high":"7.1700","low":"7.1700","close":"7.1710","volume":0,"tradeTime":"20261015000000"}]}}""";
        List<MacroSource.Observation> obs = CnbcSource.parse(json, body, "ID10Y-ID", LocalDate.of(2026, 10, 15));
        assertThat(obs).extracting(MacroSource.Observation::date)
                .containsExactly(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 14));
        assertThat(obs.getLast().value()).isEqualByComparingTo("7.169");
        assertThatThrownBy(() -> CnbcSource.parse(json, "{\"error\":1}", "ID10Y-ID", LocalDate.of(2026, 10, 15)))
                .isInstanceOf(SourceException.class);
    }

    // ------------------------------------------------------------------ CFTC

    @Test
    void cftcNetIsLongMinusShort() {
        String body = """
                [{"report_date_as_yyyy_mm_dd":"2026-09-29T00:00:00.000","noncomm_positions_long_all":"240000",
                  "noncomm_positions_short_all":"40000"},
                 {"report_date_as_yyyy_mm_dd":"2026-10-06T00:00:00.000","noncomm_positions_long_all":"245403",
                  "noncomm_positions_short_all":"35068"}]""";
        List<MacroSource.Observation> obs = CftcSource.parse(json, body, "088691");
        assertThat(obs).hasSize(2);
        assertThat(obs.getLast()).isEqualTo(new MacroSource.Observation(LocalDate.of(2026, 10, 6), new BigDecimal("210335")));
        assertThatThrownBy(() -> CftcSource.parse(json, "{\"message\":\"bad\"}", "088691")).isInstanceOf(SourceException.class);
    }

    // ------------------------------------------------------------------ Bank Indonesia

    @Test
    void biExportFormHasTheDownloadButtonAndTheDateRange() {
        String html = """
                <html><body><form method="post" id="aspnetForm">
                <input type="hidden" name="__VIEWSTATE" value="abc" />
                <input type="hidden" name="__EVENTVALIDATION" value="def" />
                <input type="search" value="Search this site" />
                <input name="ctl00$x$ctl00$TextBoxDateStart" type="text" />
                <input name="ctl00$x$ctl00$TextBoxDateEnd" type="text" />
                <input type="submit" name="ctl00$x$ctl00$ButtonSearch" value="Search" />
                <input type="submit" name="ctl00$x$ctl00$ButtonExport" value="Download" />
                <input type="image" name="ctl00$x$ctl00$DataPager$ctl02$ctl00" />
                </form></body></html>""";
        Map<String, String> form = BankIndonesiaSource.exportForm(Jsoup.parse(html), LocalDate.of(2016, 1, 1),
                LocalDate.of(2026, 10, 15));
        assertThat(form).containsEntry("__VIEWSTATE", "abc").containsEntry("__EVENTVALIDATION", "def")
                .containsEntry("ctl00$x$ctl00$ButtonExport", "Download")
                .containsEntry("ctl00$x$ctl00$TextBoxDateStart", "01/01/2016")
                .containsEntry("ctl00$x$ctl00$TextBoxDateEnd", "15/10/2026")
                .doesNotContainKey("ctl00$x$ctl00$ButtonSearch");
        // the inflation page filters by month
        String monthly = html.replace("TextBoxDateStart", "TextBoxDateFrom").replace("TextBoxDateEnd", "TextBoxDateTo");
        assertThat(BankIndonesiaSource.exportForm(Jsoup.parse(monthly), LocalDate.of(2003, 1, 1), LocalDate.of(2026, 10, 15)))
                .containsEntry("ctl00$x$ctl00$TextBoxDateFrom", "01/2003")
                .containsEntry("ctl00$x$ctl00$TextBoxDateTo", "10/2026");
        assertThatThrownBy(() -> BankIndonesiaSource.exportForm(Jsoup.parse("<form></form>"), LocalDate.now(), LocalDate.now()))
                .isInstanceOf(SourceException.class).hasMessageContaining("Download");
    }

    private static byte[] biExport(String[][] rows) throws IOException {
        try (Workbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("BI-7Day-RR");
            sheet.createRow(2).createCell(0).setCellValue("BI-7Day-RR");
            Row header = sheet.createRow(4);
            header.createCell(0).setCellValue("NO");
            header.createCell(1).setCellValue("Period");
            header.createCell(2).setCellValue("BI-7Day-RR");
            int r = 5;
            for (String[] values : rows) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue(r - 4);
                row.createCell(1).setCellValue(values[0]);
                row.createCell(2).setCellValue(values[1]);
                r++;
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void biRateExportInIndonesianAndEnglish() throws IOException {
        byte[] book = biExport(new String[][] {{"23 September 2026", "5.75 %"}, {"19 Agustus 2026", "5.75 %"},
                {"21 April 2016", "5.50 %"}});
        List<MacroSource.Observation> obs = BankIndonesiaSource.parse(book, true, "BI_RATE");
        assertThat(obs).extracting(MacroSource.Observation::date).containsExactly(LocalDate.of(2016, 4, 21),
                LocalDate.of(2026, 8, 19), LocalDate.of(2026, 9, 23));
        assertThat(obs.getLast().value()).isEqualByComparingTo("5.75");
    }

    @Test
    void inflationExportByMonth() throws IOException {
        byte[] book = biExport(new String[][] {{"Agustus 2026", "3.19 %"}, {"Juli 2026", "2,88 %"}, {"Desember 2002", "0 %"}});
        List<MacroSource.Observation> obs = BankIndonesiaSource.parse(book, false, "ID_CPI_YOY");
        assertThat(obs).extracting(MacroSource.Observation::date).containsExactly(LocalDate.of(2002, 12, 1),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1));
        assertThat(obs.get(1).value()).isEqualByComparingTo("2.88");
        assertThatThrownBy(() -> BankIndonesiaSource.parse(biExport(new String[][] {{"Semester 1 2026", "3 %"}}), false, "X"))
                .isInstanceOf(SourceException.class).hasMessageContaining("not a month");
    }

    @Test
    void indonesianMonths() {
        assertThat(IndonesianDates.month("Agustus")).isEqualTo(8);
        assertThat(IndonesianDates.month("Aug*")).isEqualTo(8);
        assertThat(IndonesianDates.month("Des")).isEqualTo(12);
        assertThat(IndonesianDates.month("Mei")).isEqualTo(5);
        assertThat(IndonesianDates.month("Total")).isNull();
        assertThat(IndonesianDates.percent("5.75 %")).isEqualByComparingTo("5.75");
    }

    // ------------------------------------------------------------------ SEKI

    /**
     * A SEKI table like TABEL5_9.xls: years written once (on the first or the last month of a year), month names, a
     * yearly total column without a month, the value row labelled in the third column.
     */
    private static byte[] seki(boolean wrongYear) throws IOException {
        try (Workbook book = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("5.9");
            sheet.createRow(0).createCell(0).setCellValue("V.9. POSISI CADANGAN DEVISA");
            Row years = sheet.createRow(4);
            Row months = sheet.createRow(5);
            Row reserves = sheet.createRow(13);
            years.createCell(2).setCellValue("KETERANGAN");
            reserves.createCell(0).setCellValue(8.0);
            reserves.createCell(2).setCellValue("Total");
            String[] names = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
            int c = 3;
            for (int i = 0; i < 12; i++) {
                if (i == 0) {
                    years.createCell(c).setCellValue(2025);
                }
                months.createCell(c).setCellValue(names[i]);
                reserves.createCell(c).setCellValue(150000 + i);
                c++;
            }
            reserves.createCell(c++).setCellValue(150011);   // the yearly total: no month
            for (int i = 0; i < 9; i++) {
                if (i == 8) {
                    years.createCell(c).setCellValue(wrongYear ? 2027 : 2026);
                }
                months.createCell(c).setCellValue(i == 8 ? "Sep*" : names[i]);
                reserves.createCell(c).setCellValue(146000 + i);
                c++;
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void sekiMonthsAcrossTheColumns() throws IOException {
        List<MacroSource.Observation> obs = BiSekiSource.parse(seki(false), "Total", "TABEL5_9.xls");
        assertThat(obs).hasSize(21);
        assertThat(obs.getFirst()).isEqualTo(new MacroSource.Observation(LocalDate.of(2025, 1, 1), BigDecimal.valueOf(150000.0)));
        assertThat(obs.get(11).date()).isEqualTo(LocalDate.of(2025, 12, 1));
        assertThat(obs.getLast()).isEqualTo(new MacroSource.Observation(LocalDate.of(2026, 9, 1), BigDecimal.valueOf(146008.0)));
    }

    @Test
    void sekiYearLabelThatDisagreesWithTheMonthsFails() throws IOException {
        byte[] book = seki(true);
        assertThatThrownBy(() -> BiSekiSource.parse(book, "Total", "TABEL5_9.xls"))
                .isInstanceOf(SourceException.class).hasMessageContaining("does not follow the months");
        byte[] good = seki(false);
        assertThatThrownBy(() -> BiSekiSource.parse(good, "Uang Beredar Luas", "TABEL5_9.xls"))
                .isInstanceOf(SourceException.class).hasMessageContaining("no row");
    }
}

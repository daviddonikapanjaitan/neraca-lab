package com.neracalab.backend.macro.source;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import com.neracalab.backend.crawl.PoliteHttpClient;
import com.neracalab.backend.crawl.SourceException;
import com.neracalab.backend.macro.MacroSeries;

/**
 * Bank Indonesia indicator pages: the BI-Rate ({@code /statistik/indikator/bi-rate.aspx}) and the inflation
 * ({@code data-inflasi.aspx}). The page shows ten rows; its "Download" button exports the whole table as .xlsx, so the
 * page is read, its form is posted back with that button and a date range, and the workbook is parsed: a "Period" column
 * ("23 September 2026" for a decision, "Agustus 2026" for a month) and a value column ("5.75 %").
 */
@Component
public class BankIndonesiaSource implements MacroSource {

    private static final DateTimeFormatter FORM_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FORM_MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    private final PoliteHttpClient http;

    public BankIndonesiaSource(PoliteHttpClient http) {
        this.http = http;
    }

    @Override
    public String name() {
        return "bi";
    }

    @Override
    public List<Observation> fetch(MacroSeries series, LocalDate from, LocalDate today) {
        URI page = URI.create(series.sourceRef());
        Document html = Jsoup.parse(http.getText(page), page.toString());
        Map<String, String> form = exportForm(html, from, today);
        PoliteHttpClient.Response response = http.postForm(page, form);
        PoliteHttpClient.requireOk(page, response);
        if (!response.contentType().contains("spreadsheet") && !response.contentType().contains("excel")) {
            throw new SourceException("Bank Indonesia " + PoliteHttpClient.describe(page)
                    + ": the Download export did not return a workbook (" + response.contentType() + ")");
        }
        return parse(response.body(), "EVENT".equals(series.frequency()), series.code());
    }

    /** The page's form with its Download button and the date range the page offers. */
    static Map<String, String> exportForm(Document html, LocalDate from, LocalDate to) {
        Element form = html.selectFirst("form");
        if (form == null) {
            throw new SourceException("Bank Indonesia: the page has no form");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        String button = null;
        for (Element input : form.select("input[name]")) {
            String name = input.attr("name");
            String type = input.attr("type").toLowerCase(java.util.Locale.ROOT);
            if (type.equals("submit") || type.equals("image") || type.equals("button")) {
                if (name.endsWith("$ButtonExport")) {
                    button = name;
                    fields.put(name, input.attr("value"));
                }
                continue;
            }
            if (type.equals("checkbox") || type.equals("radio")) {
                if (input.hasAttr("checked")) {
                    fields.put(name, input.attr("value"));
                }
                continue;
            }
            fields.put(name, input.attr("value"));
        }
        if (button == null) {
            throw new SourceException("Bank Indonesia: the page has no Download (ButtonExport) button");
        }
        // the BI-Rate page filters by day (TextBoxDateStart / End, dd/mm/yyyy), the inflation page by month
        // (TextBoxDateFrom / To, mm/yyyy); without a range the export holds the whole table
        for (String name : List.copyOf(fields.keySet())) {
            if (name.endsWith("$TextBoxDateStart")) {
                fields.put(name, from.format(FORM_DATE));
            } else if (name.endsWith("$TextBoxDateEnd")) {
                fields.put(name, to.format(FORM_DATE));
            } else if (name.endsWith("$TextBoxDateFrom")) {
                fields.put(name, from.format(FORM_MONTH));
            } else if (name.endsWith("$TextBoxDateTo")) {
                fields.put(name, to.format(FORM_MONTH));
            }
        }
        return fields;
    }

    /**
     * The rows of an export: under the header row that names "Period", the period in its column and the value in the
     * next one. {@code byDay}: a decision date ("23 September 2026"), else a month ("Agustus 2026", dated on the 1st).
     */
    static List<Observation> parse(byte[] workbook, boolean byDay, String code) {
        DataFormatter text = new DataFormatter(java.util.Locale.US);
        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(workbook))) {
            Sheet sheet = book.getSheetAt(0);
            int headerRow = -1;
            int periodCol = -1;
            for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 30) && headerRow < 0; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                for (Cell cell : row) {
                    String v = text.formatCellValue(cell).trim().toLowerCase(java.util.Locale.ROOT);
                    if (v.equals("period") || v.equals("periode")) {
                        headerRow = r;
                        periodCol = cell.getColumnIndex();
                        break;
                    }
                }
            }
            if (headerRow < 0) {
                throw new SourceException("Bank Indonesia " + code + ": the export has no 'Period' column");
            }
            Map<LocalDate, Observation> byDate = new TreeMap<>();
            for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String period = text.formatCellValue(row.getCell(periodCol)).trim();
                String value = text.formatCellValue(row.getCell(periodCol + 1)).trim();
                if (period.isEmpty() && value.isEmpty()) {
                    continue;
                }
                LocalDate date = byDay ? IndonesianDates.day(period) : IndonesianDates.month1st(period);
                Observation o = new Observation(date, IndonesianDates.percent(value));
                Observation before = byDate.put(date, o);
                if (before != null && before.value().compareTo(o.value()) != 0) {
                    throw new SourceException("Bank Indonesia " + code + ": " + date + " is listed twice with "
                            + before.value() + " and " + o.value());
                }
            }
            if (byDate.isEmpty()) {
                throw new SourceException("Bank Indonesia " + code + ": the export has no rows");
            }
            return new ArrayList<>(byDate.values());
        } catch (IOException e) {
            throw new SourceException("Bank Indonesia " + code + ": the export could not be read: " + e.getMessage(), e);
        }
    }
}

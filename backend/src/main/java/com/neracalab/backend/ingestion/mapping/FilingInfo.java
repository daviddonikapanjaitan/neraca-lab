package com.neracalab.backend.ingestion.mapping;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.neracalab.backend.company.Tickers;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbook;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookException;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;
import com.neracalab.backend.ingestion.xlsx.RawSheet;

/**
 * Filing metadata from sheet 1000000 "General information": company, periods, currency,
 * rounding (unit multiplier) and audit status.
 */
public record FilingInfo(
        String fileName,
        String ticker,
        String legalName,
        String sector,
        String subsector,
        String industry,
        String currency,
        BigDecimal unitMultiplier,
        String rounding,
        String submission,
        boolean audited,
        PeriodRef current,
        PeriodRef prior,
        PeriodRef priorYearEnd,
        List<String> warnings) {

    public static FilingInfo from(IdxWorkbook workbook) {
        RawSheet sheet = workbook.sheet(IdxWorkbookReader.GENERAL_INFO).orElseThrow();
        Map<String, String> info = new HashMap<>();
        for (int r = 0; r < sheet.rowCount(); r++) {
            String english = sheet.text(r, 2);
            Object value = sheet.cell(r, 1);
            if (english != null && value != null) {
                info.putIfAbsent(english, value.toString());
            }
        }
        List<String> warnings = new ArrayList<>();
        String ticker = ticker(required(info, "Entity code"));
        String submission = required(info, "Period of financial statements submissions");
        LocalDate start = date(info, "Current period start date");
        LocalDate end = date(info, "Current period end date");
        PeriodRef current;
        PeriodRef prior;
        try {
            current = PeriodRef.yearToDate(start, end);
            prior = PeriodRef.yearToDate(date(info, "Prior period start date"), date(info, "Prior period end date"));
        } catch (IllegalArgumentException e) {
            throw new IdxWorkbookException(e.getMessage());
        }
        if (!prior.periodType().equals(current.periodType())) {
            throw new IdxWorkbookException("Prior period " + prior.key() + " does not match current period " + current.key());
        }
        String expectedType = expectedPeriodType(submission);
        if (expectedType != null && !expectedType.equals(current.periodType())) {
            warnings.add("Submission '" + submission + "' suggests " + expectedType + " but the dates give " + current.periodType());
        }
        PeriodRef priorYearEnd = PeriodRef.fullYearEnding(date(info, "Prior year end date"));

        String rounding = required(info, "Level of rounding used in financial statements");
        return new FilingInfo(
                workbook.fileName(),
                ticker,
                required(info, "Entity name"),
                stripCode(info.get("Sector")),
                stripCode(info.get("Subsector")),
                stripCode(info.get("Industry")),
                currency(required(info, "Description of presentation currency")),
                multiplier(rounding),
                rounding,
                submission,
                audited(required(info, "Type of report on financial statements")),
                current, prior, priorYearEnd,
                List.copyOf(warnings));
    }

    /** "Kuartal II / Second Quarter" -> H1 (IDX quarterly filings are year-to-date). */
    private static String expectedPeriodType(String submission) {
        String s = submission.toLowerCase(Locale.ROOT);
        if (s.contains("tahunan") || s.contains("annual")) {
            return "FY";
        }
        if (s.contains("kuartal iii") || s.contains("third")) {
            return "9M";
        }
        if (s.contains("kuartal ii") || s.contains("second")) {
            return "H1";
        }
        if (s.contains("kuartal i") || s.contains("first")) {
            return "Q1";
        }
        return null;
    }

    /** "hrta" -> "HRTA": the form stored in company.ticker, the key of the company together with the exchange. */
    static String ticker(String entityCode) {
        try {
            return Tickers.normalize(entityCode);
        } catch (Tickers.InvalidTickerException e) {
            throw new IdxWorkbookException("General information 'Entity code' is not a ticker: " + e.getMessage());
        }
    }

    /** "Diaudit / Audited" -> true, "Tidak Diaudit / Unaudit" -> false. */
    static boolean audited(String value) {
        String english = englishPart(value).toLowerCase(Locale.ROOT);
        return english.equals("audited");
    }

    /** "Rupiah / IDR" -> IDR. */
    static String currency(String value) {
        String code = englishPart(value).toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z]{3}")) {
            throw new IdxWorkbookException("Unrecognised presentation currency '" + value + "'");
        }
        return code;
    }

    /** "Satuan Penuh / Full Amount" -> 1, "Ribuan / In Thousands" -> 1000, ... */
    static BigDecimal multiplier(String rounding) {
        String s = rounding.toLowerCase(Locale.ROOT);
        if (s.contains("full") || s.contains("penuh")) {
            return BigDecimal.ONE;
        }
        if (s.contains("thousand") || s.contains("ribu")) {
            return BigDecimal.valueOf(1_000L);
        }
        if (s.contains("million") || s.contains("juta")) {
            return BigDecimal.valueOf(1_000_000L);
        }
        if (s.contains("billion") || s.contains("miliar")) {
            return BigDecimal.valueOf(1_000_000_000L);
        }
        throw new IdxWorkbookException("Unrecognised rounding level '" + rounding + "'");
    }

    /** "E. Consumer Cyclicals" -> "Consumer Cyclicals", "E41. Apparel & Luxury Goods" -> "Apparel & Luxury Goods". */
    static String stripCode(String value) {
        return value == null ? null : value.replaceFirst("^[A-Z][0-9]*\\.\\s*", "").trim();
    }

    private static String englishPart(String value) {
        int slash = value.lastIndexOf('/');
        return (slash >= 0 ? value.substring(slash + 1) : value).trim();
    }

    private static String required(Map<String, String> info, String key) {
        String v = info.get(key);
        if (v == null || v.isBlank()) {
            throw new IdxWorkbookException("General information is missing '" + key + "'");
        }
        return v.trim();
    }

    private static LocalDate date(Map<String, String> info, String key) {
        String v = required(info, key);
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (RuntimeException e) {
            throw new IdxWorkbookException("General information '" + key + "' is not a date: " + v);
        }
    }
}

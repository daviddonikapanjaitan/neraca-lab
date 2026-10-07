package com.neracalab.backend.ingestion.xlsx;

/**
 * IDX XBRL taxonomy of a workbook, given by the first digit of its statement sheet codes. The reader
 * names every sheet by its General Industry code ({@link IdxSheets}); {@link #code(String)} gives the
 * code as filed, for messages.
 */
public enum IdxTaxonomy {

    /** "General Industry": 1210000 balance sheet, 1311000 profit or loss, ... */
    GENERAL('1'),
    /** "Infrastructure Industry" (e.g. SMDR): the General Industry roles and line items under 3xxxxxx. */
    INFRASTRUCTURE('3'),
    /**
     * "Financial and Sharia Industry" (banks, e.g. BNGA): balance sheet by order of liquidity (4220000),
     * profit or loss by nature (4312000 / 4322000), cash flow (4510000 / 4520000), with own line items.
     */
    FINANCIAL('4');

    private final char digit;

    IdxTaxonomy(char digit) {
        this.digit = digit;
    }

    /** "1220000" -> "4220000" for the Financial taxonomy. */
    public String code(String generalCode) {
        return digit + generalCode.substring(1);
    }

    /** Taxonomy of a sheet code ("4220000", "4410000PY"), {@code null} for other sheets. */
    static IdxTaxonomy of(String sheetCode) {
        if (!sheetCode.matches("\\d{7}(PY)?")) {
            return null;
        }
        for (IdxTaxonomy t : values()) {
            if (t.digit == sheetCode.charAt(0)) {
                return t;
            }
        }
        return null;
    }
}

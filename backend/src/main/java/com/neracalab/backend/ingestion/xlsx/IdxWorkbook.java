package com.neracalab.backend.ingestion.xlsx;

import java.util.Map;
import java.util.Optional;

/**
 * An IDX XBRL financial statement workbook (FinancialStatement-&lt;period&gt;-&lt;TICKER&gt;.xlsx)
 * read into memory. Sheets are keyed by their General Industry name, e.g. {@code 1210000} (balance
 * sheet), whatever the {@code taxonomy} of the filing.
 */
public record IdxWorkbook(String fileName, Map<String, RawSheet> sheets, IdxTaxonomy taxonomy,
                          Map<String, RawSheet> allSheets) {

    /** Without the complete sheets (tests of the mapper): the mapped sheets are all there is. */
    public IdxWorkbook(String fileName, Map<String, RawSheet> sheets, IdxTaxonomy taxonomy) {
        this(fileName, sheets, taxonomy, sheets);
    }

    public Optional<RawSheet> sheet(String name) {
        return Optional.ofNullable(sheets.get(name));
    }

    public boolean has(String name) {
        return sheets.containsKey(name);
    }
}

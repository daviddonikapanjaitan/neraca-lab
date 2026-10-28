package com.neracalab.backend.rag;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * What an Excel workbook uploaded on Ingestion > Excel Documents (RAG) is (exchange IDX). Only the available types
 * take uploads; the others are listed on the page as later features.
 */
public enum ExcelDocumentType {

    /**
     * KSEI's daily list of the holders of more than 5% of a listed company, by investor (SID), from idx.co.id
     * ("Kepemilikan Efek Diatas 5% Berdasarkan SID (Publik)", peng-2026-10-27-00104-lima-persen.xlsx): stored for
     * Companies > Ownership (>=5%) and, per stored company, in the vector store.
     */
    OWNERSHIP_5_PERCENT("Ownership (>=5%)", true,
            "KSEI's daily list of the shareholders of more than 5% (Kepemilikan Efek Diatas 5% Berdasarkan SID), e.g. "
                    + "peng-2026-10-27-00104-lima-persen.xlsx"),
    /** The holders of more than 1% with their broker: a later feature. */
    OWNERSHIP_1_PERCENT("Ownership (>1%)", false, "The holders of more than 1% with their broker (later feature)");

    private final String label;
    private final boolean available;
    private final String examples;

    ExcelDocumentType(String label, boolean available, String examples) {
        this.label = label;
        this.available = available;
        this.examples = examples;
    }

    public String label() {
        return label;
    }

    /** It takes uploads now. */
    public boolean available() {
        return available;
    }

    public String examples() {
        return examples;
    }

    /** The type of a code ("ownership-5-percent" too); empty for an unknown or blank one. */
    public static Optional<ExcelDocumentType> of(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String c = code.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return Arrays.stream(values()).filter(t -> t.name().equals(c)).findFirst();
    }
}

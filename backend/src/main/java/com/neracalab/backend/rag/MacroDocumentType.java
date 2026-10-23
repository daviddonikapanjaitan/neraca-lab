package com.neracalab.backend.rag;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * What a macro document of a country is ({@code rag_document.document_type}, checked by {@code ck_rag_document_type}).
 * The examples are those of Indonesia ({@code instruction/pdf_rag_macro.md}).
 */
public enum MacroDocumentType {

    MONETARY_POLICY("Monetary policy", "Bank Indonesia board statements, Monetary Policy Review and Report"),
    FINANCIAL_STABILITY("Financial stability", "Financial Stability Review, KSSK and OJK statements"),
    FISCAL("Fiscal and budget", "APBN KiTa, Nota Keuangan, KEM-PPKF, debt and SBN issuance plans"),
    EXTERNAL_SECTOR("External sector", "Balance of payments, external debt"),
    ECONOMIC_OUTLOOK("Economic outlook", "IMF Article IV, World Bank, ADB, OECD, AMRO, Economic Report on Indonesia"),
    STATISTICS("Statistics release", "BPS releases: GDP, inflation, trade"),
    DEVELOPMENT_PLAN("Development plan", "RPJMN, RKP"),
    RESEARCH("Research (opinion)", "Bank and think-tank research: an opinion, not an official view"),
    OTHER("Other", "Any other macro document");

    private final String label;
    private final String examples;

    MacroDocumentType(String label, String examples) {
        this.label = label;
        this.examples = examples;
    }

    public String label() {
        return label;
    }

    public String examples() {
        return examples;
    }

    /** The type of a code ("monetary_policy" too); empty for an unknown or blank one. */
    public static Optional<MacroDocumentType> of(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String c = code.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return Arrays.stream(values()).filter(t -> t.name().equals(c)).findFirst();
    }
}

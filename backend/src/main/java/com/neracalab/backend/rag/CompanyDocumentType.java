package com.neracalab.backend.rag;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * What a PDF of a company is ({@code rag_document.document_type}, checked by {@code ck_rag_document_company_type}),
 * chosen at the upload on Ingestion > PDF Documents (RAG).
 */
public enum CompanyDocumentType {

    /** The default: financial statements, annual reports (every company PDF stored before the type existed). */
    FINANCIAL_STATEMENT("Financial Statement", "Financial statements and annual reports, e.g. FinancialStatement-2025-Tahunan-HRTA.pdf"),
    /**
     * A monthly registration report of securities holders (Laporan Bulanan Registrasi Pemegang Efek) from idx.co.id:
     * stored in the vector store and read into the shares of the directors and commissioners (Companies >
     * Registration).
     */
    REGISTRATION("Registration", "Monthly registration report of securities holders (Laporan Bulanan Registrasi Pemegang Efek)");

    private final String label;
    private final String examples;

    CompanyDocumentType(String label, String examples) {
        this.label = label;
        this.examples = examples;
    }

    public String label() {
        return label;
    }

    public String examples() {
        return examples;
    }

    /** The type of a code ("registration", "financial-statement" too); empty for an unknown or blank one. */
    public static Optional<CompanyDocumentType> of(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String c = code.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return Arrays.stream(values()).filter(t -> t.name().equals(c)).findFirst();
    }
}

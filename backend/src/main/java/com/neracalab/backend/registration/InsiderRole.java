package com.neracalab.backend.registration;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** What an insider of a registration report is ({@code registration_insider.role}), from the position filed. */
public enum InsiderRole {

    DIRECTOR("Director"),
    COMMISSIONER("Commissioner"),
    /** a position that names neither (shown with the position as filed) */
    OTHER("Other");

    private final String label;

    InsiderRole(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * The role of a filed position: "Komisaris", "Komisaris Utama", "Commissioner" -> COMMISSIONER; "Direksi",
     * "Direktur Utama", "Director" -> DIRECTOR; anything else OTHER. A commissioner's title is read first ("Presiden
     * Komisaris" is a commissioner).
     */
    public static InsiderRole ofPosition(String position) {
        String p = position == null ? "" : position.toUpperCase(Locale.ROOT);
        if (p.contains("KOMISARIS") || p.contains("COMMISSIONER")) {
            return COMMISSIONER;
        }
        if (p.contains("DIREKSI") || p.contains("DIREKTUR") || p.contains("DIRECTOR")) {
            return DIRECTOR;
        }
        return OTHER;
    }

    /** The role of a code ("director" too); empty for an unknown or blank one. */
    public static Optional<InsiderRole> of(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String c = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(r -> r.name().equals(c)).findFirst();
    }
}

package com.neracalab.backend.ingestion.mapping;

/** Result of one validation rule. ERROR blocks saving, WARNING is reported only. */
public record Check(Severity severity, String rule, String message) {

    public enum Severity { OK, WARNING, ERROR }

    public static Check ok(String rule, String message) {
        return new Check(Severity.OK, rule, message);
    }

    public static Check warning(String rule, String message) {
        return new Check(Severity.WARNING, rule, message);
    }

    public static Check error(String rule, String message) {
        return new Check(Severity.ERROR, rule, message);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }
}

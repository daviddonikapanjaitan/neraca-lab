package com.neracalab.backend.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.rag.RagRepository.SourceType;

/**
 * The SQL scripts run on every start, in order, and V1.0.29 / V1.0.33 re-create the checks of {@code rag_document}
 * before V1.0.46 (the XBRL workbooks): their lists must admit every source type, or a stored document of a newer type
 * violates the narrower check and the application no longer starts.
 */
class RagSourceTypeConstraintTest {

    private static String script(String name) throws IOException {
        try (InputStream in = RagSourceTypeConstraintTest.class.getResourceAsStream("/db/" + name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theRecreatedSourceTypeCheckListsEverySourceType() throws IOException {
        Matcher m = Pattern.compile("ADD\\s+CONSTRAINT\\s+ck_rag_document_source_type\\s+CHECK\\s*\\(\\s*source_type\\s+IN\\s*\\(([^)]*)\\)",
                Pattern.CASE_INSENSITIVE).matcher(script("V1.0.33__schema_ownership.sql"));
        assertThat(m.find()).isTrue();
        List<String> listed = new ArrayList<>();
        for (String value : m.group(1).split(",")) {
            listed.add(value.trim().replace("'", ""));
        }
        assertThat(listed).containsExactlyInAnyOrderElementsOf(Arrays.stream(SourceType.values()).map(Enum::name).toList());
    }

    @Test
    void theRecreatedCompanyTypeCheckAdmitsAnXbrlFinancialStatement() throws IOException {
        String sql = script("V1.0.29__schema_registration.sql").replaceAll("\\s+", " ");
        assertThat(sql).contains("WHEN source_type = 'XBRL' THEN document_type = 'FINANCIAL_STATEMENT'");
    }
}

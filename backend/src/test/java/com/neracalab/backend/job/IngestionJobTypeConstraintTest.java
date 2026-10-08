package com.neracalab.backend.job;

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

/**
 * The SQL scripts run on every start, in order. A script that re-creates {@code ck_ingestion_job_type} with
 * ALTER TABLE must list every job type, also those added by later scripts: otherwise a stored job of a newer
 * type violates the narrower check and the application no longer starts.
 */
class IngestionJobTypeConstraintTest {

    /** Scripts that re-create the check on every start. */
    private static final List<String> SCRIPTS = List.of("V1.0.10__schema_screening.sql", "V1.0.14__schema_rag.sql");

    private static final Pattern CHECK = Pattern.compile(
            "ADD\\s+CONSTRAINT\\s+ck_ingestion_job_type\\s+CHECK\\s*\\(\\s*job_type\\s+IN\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    private static String script(String name) throws IOException {
        try (InputStream in = IngestionJobTypeConstraintTest.class.getResourceAsStream("/db/" + name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyRecreatedCheckListsEveryJobType() throws IOException {
        List<String> all = Arrays.stream(IngestionJobType.values()).map(Enum::name).toList();
        for (String name : SCRIPTS) {
            Matcher m = CHECK.matcher(script(name));
            assertThat(m.find()).as(name).isTrue();
            List<String> listed = new ArrayList<>();
            for (String value : m.group(1).split(",")) {
                listed.add(value.trim().replace("'", ""));
            }
            assertThat(listed).as(name).containsExactlyInAnyOrderElementsOf(all);
        }
    }
}

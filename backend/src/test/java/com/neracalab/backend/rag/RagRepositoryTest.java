package com.neracalab.backend.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.ingestion.file.IngestionFileRepository;
import com.neracalab.backend.ingestion.file.IngestionFileRepository.StoredFile;
import com.neracalab.backend.rag.RagRepository.Company;
import com.neracalab.backend.rag.RagRepository.DocumentRow;
import com.neracalab.backend.rag.RagRepository.Hit;
import com.neracalab.backend.rag.RagRepository.NewDocument;
import com.neracalab.backend.rag.RagRepository.SourceType;

/** The pgvector store (rag_document / rag_chunk) on the real database; every test is rolled back. */
@SpringBootTest
@Transactional
class RagRepositoryTest {

    @Autowired
    private RagRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private IngestionFileRepository files;

    /** Unit vector along one axis. */
    static float[] axis(int i) {
        float[] v = new float[RagProperties.STORE_DIMENSIONS];
        v[i] = 1f;
        return v;
    }

    private Company hrta() {
        return repository.company("IDX", "HRTA").orElseThrow();
    }

    private static List<TextChunker.Chunk> chunks(String... texts) {
        return java.util.stream.IntStream.range(0, texts.length)
                .mapToObj(i -> new TextChunker.Chunk(i, texts[i], i + 1, i + 1)).toList();
    }

    @Test
    void findsTheCompanyOfTheExchange() {
        assertThat(repository.company("IDX", "HRTA")).get().satisfies(c -> {
            assertThat(c.ticker()).isEqualTo("HRTA");
            assertThat(c.companyName()).containsIgnoringCase("Hartadinata");
        });
        assertThat(repository.company("IDX", "NOPE")).isEmpty();
    }

    @Test
    void storesSearchesAndReplacesADocument() {
        Company hrta = hrta();
        StoredFile file = files.store(("%PDF-test " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8), "test.pdf",
                "application/pdf");
        NewDocument doc = new NewDocument(hrta.companyId(), SourceType.PDF, file.checksumSha256(),
                "Test PDF", "PDF upload", null, file.fileId(), "test.pdf", null, 3, 30, "test-model", null);
        long id = repository.store(doc, chunks("emas", "laba", "utang"), List.of(axis(0), axis(1), axis(2)));
        assertThat(repository.hasDocument(hrta.companyId(), SourceType.PDF, doc.sourceKey())).isTrue();
        assertThat(repository.hasDocument(hrta.companyId(), SourceType.NEWS, doc.sourceKey())).isFalse();

        List<Hit> hits = repository.search(hrta.companyId(), SourceType.PDF, axis(1), 50);
        assertThat(hits.getFirst().content()).isEqualTo("laba");
        assertThat(hits.getFirst().distance()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(hits.getFirst().pageFrom()).isEqualTo(2);
        assertThat(hits).extracting(Hit::distance).isSorted();
        assertThat(repository.search(hrta.companyId(), SourceType.NEWS, axis(1), 50))
                .noneMatch(h -> h.documentId() == id);

        // the same source again replaces the document and its chunks
        long again = repository.store(doc, chunks("perak"), List.of(axis(5)));
        assertThat(again).isEqualTo(id);
        assertThat(jdbc.sql("SELECT count(*) FROM rag_chunk WHERE document_id = :d").param("d", id).query(Long.class).single())
                .isEqualTo(1);
        DocumentRow row = repository.documents("IDX", "HRTA", null, SourceType.PDF, 500, 0).documents().stream()
                .filter(d -> d.documentId() == id).findFirst().orElseThrow();
        assertThat(row.chunks()).isEqualTo(1);
        assertThat(row.fileName()).isEqualTo("test.pdf");
        assertThat(row.ticker()).isEqualTo("HRTA");
    }

    @Test
    void newsArticlesAreKeyedByUrlPerCompany() {
        Company hrta = hrta();
        String url = "https://example.com/news/" + UUID.randomUUID();
        Instant published = Instant.parse("2026-10-01T03:00:00Z");
        repository.store(new NewDocument(hrta.companyId(), SourceType.NEWS, url, "Berita", "EmitenNews", url, null, null,
                published, null, 10, "test-model", null), chunks("berita emas"), List.of(axis(7)));
        assertThat(repository.hasDocument(hrta.companyId(), SourceType.NEWS, url)).isTrue();
        DocumentRow row = repository.documents(null, "HRTA", null, SourceType.NEWS, 500, 0).documents().stream()
                .filter(d -> url.equals(d.sourceUrl())).findFirst().orElseThrow();

        // pages: the tickers starting with "HR", one document per page; past the last page, none
        RagRepository.DocumentPage first = repository.documents(null, null, "HR", SourceType.NEWS, 1, 0);
        assertThat(first.total()).isPositive();
        assertThat(first.documents()).singleElement().satisfies(d -> assertThat(d.ticker()).startsWith("HR"));
        assertThat(first.documents().getFirst().documentId()).isEqualTo(row.documentId());   // stored last
        RagRepository.DocumentPage past = repository.documents(null, null, "HR", SourceType.NEWS, 1, (int) first.total());
        assertThat(past.total()).isEqualTo(first.total());
        assertThat(past.documents()).isEmpty();
        assertThat(repository.documents(null, null, "HRTAX", SourceType.NEWS, 10, 0).total()).isZero();
        assertThat(row.publishedAt()).isEqualTo(published);
        assertThat(row.sourceName()).isEqualTo("EmitenNews");

        // deleting the company removes its documents and chunks (foreign keys ON DELETE CASCADE)
        assertThat(jdbc.sql("""
                        SELECT confdeltype FROM pg_constraint
                        WHERE conrelid = 'rag_document'::regclass AND confrelid = 'company'::regclass""")
                .query(String.class).single()).isEqualTo("c");
    }
}

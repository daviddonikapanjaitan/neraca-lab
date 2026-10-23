package com.neracalab.backend.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import com.neracalab.backend.rag.RagRepository.MacroHit;
import com.neracalab.backend.rag.RagRepository.NewDocument;
import com.neracalab.backend.rag.RagRepository.NewMacroDocument;
import com.neracalab.backend.rag.RagRepository.SourceType;

/**
 * The pgvector store (rag_document / rag_chunk) on the real database, company and macro documents; every test is
 * rolled back.
 */
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
        NewDocument doc = new NewDocument(hrta.companyId(), SourceType.PDF, CompanyDocumentType.FINANCIAL_STATEMENT,
                file.checksumSha256(), "Test PDF", "PDF upload", null, file.fileId(), "test.pdf", null, 3, 30,
                "test-model", null);
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
        DocumentRow row = repository.documents("IDX", "HRTA", null, SourceType.PDF, null, 500, 0).documents().stream()
                .filter(d -> d.documentId() == id).findFirst().orElseThrow();
        assertThat(row.chunks()).isEqualTo(1);
        assertThat(row.fileName()).isEqualTo("test.pdf");
        assertThat(row.ticker()).isEqualTo("HRTA");
        assertThat(row.documentType()).isEqualTo(CompanyDocumentType.FINANCIAL_STATEMENT);
        assertThat(repository.documents("IDX", "HRTA", null, SourceType.PDF, CompanyDocumentType.REGISTRATION, 500, 0)
                .documents()).noneMatch(d -> d.documentId() == id);

        // the same file again as a registration report: the type given now
        repository.store(new NewDocument(hrta.companyId(), SourceType.PDF, CompanyDocumentType.REGISTRATION,
                doc.sourceKey(), "Test PDF", "PDF upload", null, file.fileId(), "test.pdf", null, 3, 30, "test-model",
                null), chunks("perak"), List.of(axis(5)));
        assertThat(repository.documents("IDX", "HRTA", null, null, CompanyDocumentType.REGISTRATION, 500, 0).documents())
                .anyMatch(d -> d.documentId() == id && d.documentType() == CompanyDocumentType.REGISTRATION);
    }

    /** A company PDF has a company document type (V1.0.29: FINANCIAL_STATEMENT or REGISTRATION), never a macro one. */
    @Test
    void aCompanyPdfHasACompanyDocumentType() {
        Company hrta = hrta();
        StoredFile file = files.store(("%PDF-type " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8), "type.pdf",
                "application/pdf");
        long id = repository.store(new NewDocument(hrta.companyId(), SourceType.PDF,
                CompanyDocumentType.FINANCIAL_STATEMENT, file.checksumSha256(), "Type", "PDF upload", null,
                file.fileId(), "type.pdf", null, 1, 10, "test-model", null), chunks("emas"), List.of(axis(3)));
        // every company PDF stored before the types existed was given FINANCIAL_STATEMENT
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM rag_document
                        WHERE company_id IS NOT NULL AND source_type = 'PDF' AND document_type IS NULL""")
                .query(Long.class).single()).isZero();
        // the last statement: it aborts the transaction
        assertThatThrownBy(() -> jdbc.sql("UPDATE rag_document SET document_type = 'FISCAL' WHERE document_id = :d")
                .param("d", id).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void storesSearchesAndListsAMacroDocumentOfACountry() {
        StoredFile file = files.store(("%PDF-macro " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8), "mpr.pdf",
                "application/pdf");
        Instant published = LocalDate.of(2026, 8, 20).atStartOfDay(ZoneId.of("Asia/Jakarta")).toInstant();
        String title = "Monetary Policy Review " + UUID.randomUUID();
        NewMacroDocument doc = new NewMacroDocument("ID", file.checksumSha256(), title, "Bank Indonesia",
                MacroDocumentType.MONETARY_POLICY, published, file.fileId(), "mpr.pdf", 2, 40, "test-model", null);
        long id = repository.storeMacro(doc, chunks("guidance", "rupiah"), List.of(axis(20), axis(21)));

        // the country's search finds it; only documents published before the instant given (point in time)
        List<MacroHit> hits = repository.searchMacro("ID", null, axis(21), 50);
        assertThat(hits.getFirst()).satisfies(h -> {
            assertThat(h.documentId()).isEqualTo(id);
            assertThat(h.content()).isEqualTo("rupiah");
            assertThat(h.distance()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
            assertThat(h.issuer()).isEqualTo("Bank Indonesia");
            assertThat(h.documentType()).isEqualTo(MacroDocumentType.MONETARY_POLICY);
            assertThat(h.publishedAt()).isEqualTo(published);
            assertThat(h.pageFrom()).isEqualTo(2);
        });
        assertThat(hits).extracting(MacroHit::distance).isSorted();
        Instant nextDay = published.plus(java.time.Duration.ofDays(1));
        assertThat(repository.searchMacro("ID", nextDay, axis(21), 50)).anyMatch(h -> h.documentId() == id);
        assertThat(repository.searchMacro("ID", published, axis(21), 50)).noneMatch(h -> h.documentId() == id);
        assertThat(repository.searchMacro("US", null, axis(21), 50)).noneMatch(h -> h.documentId() == id);
        assertThat(repository.macroDocumentsPublishedBefore("ID", nextDay, 500)).anyMatch(d -> d.documentId() == id);
        assertThat(repository.macroDocumentsPublishedBefore("ID", published, 500)).noneMatch(d -> d.documentId() == id);

        // never mixed with the company documents
        assertThat(repository.search(null, null, axis(21), 50)).noneMatch(h -> h.documentId() == id);
        assertThat(repository.documents(null, null, null, SourceType.PDF, null, 500, 0).documents())
                .noneMatch(d -> d.documentId() == id);

        // the listing, by country and type
        RagRepository.MacroDocumentRow row = repository.macroDocuments("ID", null, 500, 0).documents().stream()
                .filter(d -> d.documentId() == id).findFirst().orElseThrow();
        assertThat(row.title()).isEqualTo(title);
        assertThat(row.chunks()).isEqualTo(2);
        assertThat(row.fileName()).isEqualTo("mpr.pdf");
        assertThat(repository.macroDocuments("ID", MacroDocumentType.FISCAL, 500, 0).documents())
                .noneMatch(d -> d.documentId() == id);
        assertThat(repository.macroDocuments("CN", null, 500, 0).documents()).noneMatch(d -> d.documentId() == id);
        assertThat(repository.macroDocumentCounts().get("ID")).isPositive();
        assertThat(repository.counts().macroDocuments()).isPositive();

        // the same file again for the country replaces the document, its details and its chunks
        long again = repository.storeMacro(new NewMacroDocument("ID", file.checksumSha256(), "Corrected title", null,
                MacroDocumentType.ECONOMIC_OUTLOOK, nextDay, file.fileId(), "mpr.pdf", 1, 10, "test-model", null),
                chunks("outlook"), List.of(axis(22)));
        assertThat(again).isEqualTo(id);
        RagRepository.MacroDocumentRow replaced = repository.macroDocuments("ID", MacroDocumentType.ECONOMIC_OUTLOOK, 500, 0)
                .documents().stream().filter(d -> d.documentId() == id).findFirst().orElseThrow();
        assertThat(replaced.title()).isEqualTo("Corrected title");
        assertThat(replaced.issuer()).isNull();
        assertThat(replaced.publishedAt()).isEqualTo(nextDay);
        assertThat(replaced.chunks()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM rag_chunk WHERE document_id = :d AND country = 'ID' AND company_id IS NULL")
                .param("d", id).query(Long.class).single()).isEqualTo(1);

        // a document belongs to a company or to a country, never both (the last statement: it aborts the transaction)
        assertThatThrownBy(() -> jdbc.sql("UPDATE rag_document SET company_id = :c WHERE document_id = :d")
                .param("c", hrta().companyId()).param("d", id).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void newsArticlesAreKeyedByUrlPerCompany() {
        Company hrta = hrta();
        String url = "https://example.com/news/" + UUID.randomUUID();
        Instant published = Instant.parse("2026-10-01T03:00:00Z");
        repository.store(new NewDocument(hrta.companyId(), SourceType.NEWS, null, url, "Berita", "EmitenNews", url, null,
                null, published, null, 10, "test-model", null), chunks("berita emas"), List.of(axis(7)));
        assertThat(repository.hasDocument(hrta.companyId(), SourceType.NEWS, url)).isTrue();
        DocumentRow row = repository.documents(null, "HRTA", null, SourceType.NEWS, null, 500, 0).documents().stream()
                .filter(d -> url.equals(d.sourceUrl())).findFirst().orElseThrow();

        // pages: the tickers starting with "HR", one document per page; past the last page, none
        RagRepository.DocumentPage first = repository.documents(null, null, "HR", SourceType.NEWS, null, 1, 0);
        assertThat(first.total()).isPositive();
        assertThat(first.documents()).singleElement().satisfies(d -> assertThat(d.ticker()).startsWith("HR"));
        assertThat(first.documents().getFirst().documentId()).isEqualTo(row.documentId());   // stored last
        RagRepository.DocumentPage past = repository.documents(null, null, "HR", SourceType.NEWS, null, 1, (int) first.total());
        assertThat(past.total()).isEqualTo(first.total());
        assertThat(past.documents()).isEmpty();
        assertThat(repository.documents(null, null, "HRTAX", SourceType.NEWS, null, 10, 0).total()).isZero();
        assertThat(row.publishedAt()).isEqualTo(published);
        assertThat(row.sourceName()).isEqualTo("EmitenNews");
        assertThat(row.documentType()).isNull();

        // deleting the company removes its documents and chunks (foreign keys ON DELETE CASCADE)
        assertThat(jdbc.sql("""
                        SELECT confdeltype FROM pg_constraint
                        WHERE conrelid = 'rag_document'::regclass AND confrelid = 'company'::regclass""")
                .query(String.class).single()).isEqualTo("c");
    }
}

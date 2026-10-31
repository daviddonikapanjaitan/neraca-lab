package com.neracalab.backend.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.ingestion.file.IngestionFileRepository;
import com.neracalab.backend.ingestion.file.IngestionFileRepository.StoredFile;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbook;
import com.neracalab.backend.rag.RagRepository.Company;
import com.neracalab.backend.rag.RagRepository.DocumentPage;
import com.neracalab.backend.rag.RagRepository.Hit;
import com.neracalab.backend.rag.RagRepository.SourceType;
import com.neracalab.backend.rag.XbrlRagService.Outcome;
import com.neracalab.backend.rag.XbrlRagService.Result;

import tools.jackson.databind.json.JsonMapper;

/**
 * HRTA's FY2025 workbook into the vector store on the real database (source type XBRL, document type
 * FINANCIAL_STATEMENT), with stub embeddings (no provider call); every test is rolled back.
 */
@SpringBootTest
@Transactional
class XbrlRagServiceTest {

    private static final Path FILE = Path.of("..", "data", "IDX_XBRL", "HRTA", "xlsx", "FinancialStatement-2025-Tahunan-HRTA.xlsx");

    @Autowired
    private RagRepository repository;

    @Autowired
    private IngestionFileRepository files;

    @Autowired
    private RagProperties properties;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcClient jdbc;

    /** Texts embedded by the stub, every call. */
    private final List<String> embedded = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    /** One axis per text (its hash), so a search for a chunk's own text finds it first. */
    private EmbeddingClient stub(String model, boolean failing) {
        return new EmbeddingClient(properties, json, "http://localhost", "unused") {
            @Override
            public List<float[]> embed(List<String> texts) {
                calls.incrementAndGet();
                if (failing) {
                    throw new EmbeddingException("The embedding API answered HTTP 401: no key");
                }
                embedded.addAll(texts);
                return texts.stream().map(XbrlRagServiceTest::vector).toList();
            }

            @Override
            public String model() {
                return model;
            }
        };
    }

    static float[] vector(String text) {
        float[] v = new float[RagProperties.STORE_DIMENSIONS];
        v[Math.floorMod(text.hashCode(), v.length)] = 1;
        return v;
    }

    private XbrlRagService service(String model, boolean failing) {
        return new XbrlRagService(repository, stub(model, failing), properties, true);
    }

    @Test
    void storesTheWorkbookAsAFinancialStatementOfTheCompany() throws Exception {
        byte[] content = content();
        StoredFile file = files.store(content, FILE.getFileName().toString(), null);
        IdxWorkbook workbook = XbrlDocumentTextTest.workbook();
        UUID job = UUID.randomUUID();
        List<String> stages = new ArrayList<>();

        Result result = service("stub-model", false).store(job, "HRTA", file.fileId(), FILE.getFileName().toString(),
                file.checksumSha256(), workbook, stages::add);

        List<TextChunker.Chunk> expected = XbrlDocumentText.chunks(
                XbrlDocumentText.sheets(workbook, "FinancialStatement-2025-Tahunan-HRTA"),
                new TextChunker(properties.chunkChars(), properties.chunkOverlap()));
        assertThat(result.outcome()).isEqualTo(Outcome.STORED);
        assertThat(result.chunks()).isEqualTo(expected.size());
        assertThat(result.characters()).isEqualTo(expected.stream().mapToInt(c -> c.text().length()).sum());
        assertThat(result.embeddingModel()).isEqualTo("stub-model");
        assertThat(embedded).isEqualTo(expected.stream().map(TextChunker.Chunk::text).toList());
        assertThat(stages).singleElement().asString().startsWith("Embedding the text of FinancialStatement-2025-Tahunan-HRTA.xlsx");

        var row = jdbc.sql("""
                        SELECT c.ticker, d.source_type, d.document_type, d.source_key, d.title, d.source_name, d.file_id,
                               d.file_name, d.pages, d.published_at, d.chunks, d.characters, d.embedding_model, d.job_id
                        FROM rag_document d JOIN company c ON c.company_id = d.company_id
                        WHERE d.document_id = :id""")
                .param("id", result.documentId()).query().singleRow();
        assertThat(row.get("ticker")).isEqualTo("HRTA");
        assertThat(row.get("source_type")).isEqualTo("XBRL");
        assertThat(row.get("document_type")).isEqualTo("FINANCIAL_STATEMENT");
        assertThat(row.get("source_key")).isEqualTo(IngestionFileRepository.sha256(content));
        assertThat(row.get("title")).isEqualTo("FinancialStatement-2025-Tahunan-HRTA");
        assertThat(row.get("source_name")).isEqualTo(XbrlRagService.SOURCE_NAME);
        assertThat(((Number) row.get("file_id")).longValue()).isEqualTo(file.fileId());
        assertThat(row.get("file_name")).isEqualTo("FinancialStatement-2025-Tahunan-HRTA.xlsx");
        assertThat(row.get("pages")).isNull();
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("chunks")).isEqualTo(expected.size());
        assertThat(row.get("embedding_model")).isEqualTo("stub-model");
        assertThat(row.get("job_id")).isEqualTo(job);
        List<String> stored = jdbc.sql("SELECT content FROM rag_chunk WHERE document_id = :id ORDER BY chunk_index")
                .param("id", result.documentId()).query(String.class).list();
        assertThat(stored).isEqualTo(expected.stream().map(TextChunker.Chunk::text).toList());
        assertThat(jdbc.sql("SELECT count(*) FROM rag_chunk WHERE document_id = :id AND page_from IS NULL AND country IS NULL")
                .param("id", result.documentId()).query(Long.class).single()).isEqualTo(expected.size());

        // listed and searched as the company's XBRL financial statements, apart from its PDFs and ownership documents
        Company hrta = repository.company("IDX", "HRTA").orElseThrow();
        DocumentPage page = repository.documents("IDX", "HRTA", null, SourceType.XBRL, CompanyDocumentType.FINANCIAL_STATEMENT, 10, 0);
        assertThat(page.documents()).anySatisfy(d -> {
            assertThat(d.documentId()).isEqualTo(result.documentId());
            assertThat(d.sourceType()).isEqualTo(SourceType.XBRL);
            assertThat(d.documentType()).isEqualTo(CompanyDocumentType.FINANCIAL_STATEMENT);
        });
        String revenue = expected.stream().map(TextChunker.Chunk::text)
                .filter(t -> t.contains("Penjualan dan pendapatan usaha | 44548424151152")).findFirst().orElseThrow();
        // (the stub's axes may repeat: the chunk is among the closest, at distance 0)
        List<Hit> hits = repository.search(hrta.companyId(), SourceType.XBRL, vector(revenue), 50);
        assertThat(hits).allSatisfy(h -> assertThat(h.sourceType()).isEqualTo(SourceType.XBRL));
        assertThat(hits).anySatisfy(h -> {
            assertThat(h.content()).isEqualTo(revenue);
            assertThat(h.distance()).isLessThan(1e-6);
            assertThat(h.fileName()).isEqualTo("FinancialStatement-2025-Tahunan-HRTA.xlsx");
        });
        assertThat(repository.search(hrta.companyId(), SourceType.XLSX, vector(revenue), 50))
                .noneMatch(h -> h.documentId() == result.documentId());
        assertThat(repository.counts().xbrlDocuments()).isPositive();
    }

    /** The same file again: not embedded again; with another embedding model it is. */
    @Test
    void aWorkbookStoredAlreadyIsNotEmbeddedAgain() throws Exception {
        StoredFile file = files.store(content(), FILE.getFileName().toString(), null);
        IdxWorkbook workbook = XbrlDocumentTextTest.workbook();
        Result first = service("stub-model", false).store(UUID.randomUUID(), "HRTA", file.fileId(),
                FILE.getFileName().toString(), file.checksumSha256(), workbook, s -> { });
        int afterFirst = calls.get();

        Result again = service("stub-model", false).store(UUID.randomUUID(), "HRTA", file.fileId(),
                "renamed.xlsx", file.checksumSha256(), workbook, s -> { });

        assertThat(again.outcome()).isEqualTo(Outcome.ALREADY_STORED);
        assertThat(again.documentId()).isEqualTo(first.documentId());
        assertThat(again.chunks()).isEqualTo(first.chunks());
        assertThat(calls.get()).isEqualTo(afterFirst);

        Result otherModel = service("stub-model-2", false).store(UUID.randomUUID(), "HRTA", file.fileId(),
                FILE.getFileName().toString(), file.checksumSha256(), workbook, s -> { });
        assertThat(otherModel.outcome()).isEqualTo(Outcome.STORED);
        assertThat(otherModel.documentId()).isEqualTo(first.documentId());     // the same document, replaced
        assertThat(calls.get()).isGreaterThan(afterFirst);
    }

    @Test
    void aCompanyNotStoredOrAFailingProviderStoresNothing() throws Exception {
        StoredFile file = files.store(content(), FILE.getFileName().toString(), null);
        IdxWorkbook workbook = XbrlDocumentTextTest.workbook();

        Result unknown = service("stub-model", false).store(UUID.randomUUID(), "ZZXQ", file.fileId(),
                FILE.getFileName().toString(), file.checksumSha256(), workbook, s -> { });
        assertThat(unknown.outcome()).isEqualTo(Outcome.NO_COMPANY);
        assertThat(unknown.message()).contains("ZZXQ");
        assertThat(service("stub-model", false).store(UUID.randomUUID(), null, file.fileId(),
                FILE.getFileName().toString(), file.checksumSha256(), workbook, s -> { }).outcome())
                .isEqualTo(Outcome.NO_COMPANY);

        jdbc.sql("DELETE FROM rag_document WHERE source_type = 'XBRL' AND source_key = :k")
                .param("k", file.checksumSha256()).update();     // rolled back with the test
        Result failed = service("stub-model", true).store(UUID.randomUUID(), "HRTA", file.fileId(),
                FILE.getFileName().toString(), file.checksumSha256(), workbook, s -> { });
        assertThat(failed.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(failed.message()).contains("HTTP 401");
        assertThat(documents(file.checksumSha256())).isZero();
        assertThat(calls.get()).isEqualTo(1);
    }

    private long documents(String checksum) {
        return jdbc.sql("SELECT count(*) FROM rag_document WHERE source_type = 'XBRL' AND source_key = :k")
                .param("k", checksum).query(Long.class).single();
    }

    private static byte[] content() throws Exception {
        assumeTrue(Files.exists(FILE), "HRTA source data not available: " + FILE);
        return Files.readAllBytes(FILE);
    }
}

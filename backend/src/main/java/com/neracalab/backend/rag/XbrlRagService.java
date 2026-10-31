package com.neracalab.backend.rag;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbook;
import com.neracalab.backend.rag.RagRepository.Company;
import com.neracalab.backend.rag.RagRepository.NewDocument;
import com.neracalab.backend.rag.RagRepository.SourceType;
import com.neracalab.backend.rag.RagRepository.StoredDocument;

/**
 * Stores an uploaded IDX XBRL financial statement workbook in the RAG vector store, as a PDF financial statement is
 * stored on Ingestion > PDF Documents (RAG): its text ({@link XbrlDocumentText}: every sheet) is chunked, embedded and
 * stored as a document of the company - source type {@link SourceType#XBRL}, document type
 * {@link CompanyDocumentType#FINANCIAL_STATEMENT}, keyed by the file's SHA-256 (one document per file and company),
 * linked to the stored file. Called by the upload job once the statements are stored ({@code FinancialStatementQueue});
 * switch off with {@code neracalab.ingestion.rag-after-upload: false}.
 * <p>
 * A workbook whose document is already stored with the current embedding model is not embedded again (the uploads of
 * a fix are uploads of the same files). The company must be stored: an upload that stored nothing of a new company
 * leaves no document.
 */
@Service
public class XbrlRagService {

    /** {@code rag_document.source_name} of an uploaded workbook. */
    public static final String SOURCE_NAME = "IDX XBRL upload";

    /** What became of the workbook's document. */
    public enum Outcome {
        /** chunked, embedded and stored (a document of the file stored before is replaced) */
        STORED,
        /** the document of this file is stored already, embedded with the current model: nothing done */
        ALREADY_STORED,
        /** the workbook names no company stored in the database */
        NO_COMPANY,
        /** no sheet of the workbook has a line of text */
        NO_TEXT,
        /** chunking, embedding or storing failed ({@code message}) */
        FAILED
    }

    /**
     * The vector store part of an upload's result ({@code IngestionResponse.rag}).
     *
     * @param documentId the company's document of the file (STORED, ALREADY_STORED)
     * @param sheets     sheets with text (STORED)
     * @param message    why nothing was stored (NO_COMPANY, NO_TEXT, FAILED)
     */
    public record Result(Outcome outcome, Long documentId, String title, int sheets, int chunks, int characters,
                         String embeddingModel, String message) {

        static Result of(Outcome outcome, String title, String message) {
            return new Result(outcome, null, title, 0, 0, 0, null, message);
        }
    }

    private final RagRepository repository;
    private final EmbeddingClient embeddings;
    private final RagProperties properties;
    private final boolean enabled;

    public XbrlRagService(RagRepository repository, EmbeddingClient embeddings, RagProperties properties,
                          @Value("${neracalab.ingestion.rag-after-upload:true}") boolean enabled) {
        this.repository = repository;
        this.embeddings = embeddings;
        this.properties = properties;
        this.enabled = enabled;
    }

    /** The upload job stores its workbook in the vector store ({@code neracalab.ingestion.rag-after-upload}). */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Chunks, embeds and stores the workbook as a document of the company {@code ticker} (IDX). Never throws for a
     * failure of the store: it is returned as FAILED.
     *
     * @param jobId    the upload job ({@code rag_document.job_id})
     * @param checksum SHA-256 of the file, as {@code ingestion_file.checksum_sha256}
     * @param progress told what is being done (the job stage)
     */
    public Result store(UUID jobId, String ticker, long fileId, String fileName, String checksum, IdxWorkbook workbook,
                        Consumer<String> progress) {
        String title = XbrlDocumentText.title(fileName);
        try {
            Optional<Company> company = ticker == null || ticker.isBlank() ? Optional.empty()
                    : repository.company(Exchange.IDX.code(), ticker);
            if (company.isEmpty()) {
                return Result.of(Outcome.NO_COMPANY, title, "No stored company " + Exchange.IDX.code() + " " + ticker
                        + ": the workbook's text is not stored in the vector store");
            }
            long companyId = company.get().companyId();
            Optional<StoredDocument> stored = repository.storedDocument(companyId, SourceType.XBRL, checksum);
            if (stored.isPresent() && embeddings.model().equals(stored.get().embeddingModel())) {
                StoredDocument d = stored.get();
                return new Result(Outcome.ALREADY_STORED, d.documentId(), title, 0, d.chunks(), d.characters(),
                        d.embeddingModel(), null);
            }
            List<XbrlDocumentText.Sheet> sheets = XbrlDocumentText.sheets(workbook, title);
            List<TextChunker.Chunk> chunks = XbrlDocumentText.chunks(sheets,
                    new TextChunker(properties.chunkChars(), properties.chunkOverlap()));
            if (chunks.isEmpty()) {
                return Result.of(Outcome.NO_TEXT, title, "No sheet of the workbook has text to store");
            }
            int characters = chunks.stream().mapToInt(c -> c.text().length()).sum();
            progress.accept("Embedding the text of " + fileName + " for the vector store: " + chunks.size()
                    + " chunks of " + sheets.size() + " sheets (" + embeddings.model() + ")");
            List<float[]> vectors = embeddings.embed(chunks.stream().map(TextChunker.Chunk::text).toList());
            long id = repository.store(new NewDocument(companyId, SourceType.XBRL, CompanyDocumentType.FINANCIAL_STATEMENT,
                    checksum, title, SOURCE_NAME, null, fileId, fileName, null, null, characters, embeddings.model(),
                    jobId), chunks, vectors);
            return new Result(Outcome.STORED, id, title, sheets.size(), chunks.size(), characters, embeddings.model(), null);
        } catch (RuntimeException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            return Result.of(Outcome.FAILED, title, message);
        }
    }
}

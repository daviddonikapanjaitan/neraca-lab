package com.neracalab.backend.ownership.onepercent;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.neracalab.backend.ownership.onepercent.OnePercentDigest.Digest;
import com.neracalab.backend.ownership.onepercent.OnePercentParser.Report;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.MovementRow;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.Outcome;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.ReportRow;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.Stored;
import com.neracalab.backend.rag.EmbeddingClient;
import com.neracalab.backend.rag.ExcelDocumentType;
import com.neracalab.backend.rag.RagProperties;
import com.neracalab.backend.rag.RagRepository;
import com.neracalab.backend.rag.RagRepository.Company;
import com.neracalab.backend.rag.RagRepository.NewDocument;
import com.neracalab.backend.rag.RagRepository.SourceType;
import com.neracalab.backend.rag.TextChunker;

/**
 * One upload of Ingestion > Excel Documents (RAG), Ownership (>=1%): KSEI's list of a month is read
 * ({@link OnePercentParser}), stored and compared with the stored month before it ({@link OnePercentRepository},
 * Companies > Ownership (>=1%)); then, for every company of the list that is stored in {@code company}, its document
 * in the RAG vector store is written again from every stored month ({@link OnePercentDigest#text}): the holders of 1%
 * or more in the latest month with their types, every monthly movement. One document per company (source XLSX, key
 * {@code OWNERSHIP_1_PERCENT}). When a month is stored before a month already stored, the later month's movements
 * change too; the documents are written from every month, so they show it.
 */
@Service
public class OnePercentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(OnePercentIngestionService.class);
    static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    static final ExcelDocumentType TYPE = ExcelDocumentType.OWNERSHIP_1_PERCENT;
    /** The source name of the RAG documents. */
    public static final String SOURCE_NAME = "KSEI list of the shareholders of more than 1%";

    /**
     * What an upload stored (the job result).
     *
     * @param previousDate       the stored list it was compared with; null when none is stored before it
     * @param exited             investors of that list no longer above 1%; null when none is stored before it
     * @param nextDate           the stored list after it, compared with it again; null when none
     * @param ragDocuments       ownership documents written in the vector store (stored companies of the list)
     * @param companiesNotStored companies of the list that are not stored in {@code company}
     * @param ragError           why the vector store could not be written; null when it was
     */
    public record OnePercentResult(ExcelDocumentType documentType, String exchange, String fileName,
                                   LocalDate reportDate, LocalDate previousDate, LocalDate nextDate,
                                   String announcementNumber, int issuers, int holders, int sourceRows, int buys,
                                   int sells, int unchanged, int entered, Integer exited, int renamed, int moved,
                                   Outcome outcome, int ragDocuments, int chunks, int companiesNotStored,
                                   String embeddingModel, String ragError, List<String> warnings,
                                   List<String> notes) {
    }

    private final OnePercentRepository repository;
    private final RagRepository rag;
    private final EmbeddingClient embeddings;
    private final RagProperties properties;

    public OnePercentIngestionService(OnePercentRepository repository, RagRepository rag, EmbeddingClient embeddings,
                                      RagProperties properties) {
        this.repository = repository;
        this.rag = rag;
        this.embeddings = embeddings;
        this.properties = properties;
    }

    /**
     * Reads, stores, compares and embeds the list of one month.
     *
     * @throws OnePercentParser.OnePercentFormatException not KSEI's list of the shareholders of more than 1%
     */
    public OnePercentResult ingest(UUID jobId, String exchange, long fileId, String fileName, byte[] content,
                                   Consumer<String> progress) {
        progress.accept("Reading the list " + fileName);
        Report report = OnePercentParser.parse(content);
        List<String> tickers = report.tickers();
        progress.accept("Storing the list of " + report.reportDate() + ": " + report.holders().size() + " investors of "
                + tickers.size() + " companies, compared with the month before");
        Stored stored = repository.store(exchange, report, fileId, fileName, jobId);
        ReportRow row = repository.report(exchange, report.reportDate()).orElseThrow();

        Map<String, Company> companies = repository.companies(exchange, tickers);
        int documents = 0;
        int chunks = 0;
        String ragError = null;
        try {
            int[] written = embed(jobId, exchange, fileId, fileName, companies, progress);
            documents = written[0];
            chunks = written[1];
        } catch (EmbeddingClient.EmbeddingException e) {
            ragError = e.getMessage();
            log.warn("1% ownership list {} stored, its RAG documents not written: {}", report.reportDate(),
                    e.getMessage());
        }
        return new OnePercentResult(TYPE, exchange, fileName, report.reportDate(), stored.previousDate(),
                stored.nextDate(), OnePercentRepository.announcementNumber(fileName), tickers.size(),
                report.holders().size(), report.sourceRows(), row.buys(), row.sells(), row.unchanged(), row.entered(),
                stored.exited(), row.renamed(), row.moved(), stored.outcome(), documents, chunks,
                tickers.size() - companies.size(), embeddings.model(), ragError, report.warnings(), report.notes());
    }

    /** The companies' 1% ownership documents, written again from every stored month: [documents, chunks]. */
    private int[] embed(UUID jobId, String exchange, long fileId, String fileName, Map<String, Company> companies,
                        Consumer<String> progress) {
        progress.accept("Writing the 1% ownership documents of " + companies.size() + " stored companies");
        TextChunker chunker = new TextChunker(properties.chunkChars(), properties.chunkOverlap());
        record Pending(Company company, Digest digest, String text, List<TextChunker.Chunk> chunks) {
        }
        List<Pending> pending = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (Company company : companies.values()) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            List<MovementRow> rows = repository.holders(exchange, company.ticker(), null, null);
            Digest digest = OnePercentDigest.of(company.ticker(), rows);
            if (digest == null) {
                continue;
            }
            String text = OnePercentDigest.text(digest);
            List<TextChunker.Chunk> parts = chunker.chunk(List.of(new TextChunker.Part(text, null)));
            if (parts.isEmpty()) {
                continue;
            }
            String header = company.ticker() + " " + company.companyName() + " - shareholders of more than 1% (KSEI), "
                    + "as of " + digest.latestDate() + "\n";
            parts.forEach(c -> texts.add(header + c.text()));
            pending.add(new Pending(company, digest, text, parts));
        }
        if (pending.isEmpty()) {
            return new int[] {0, 0};
        }
        progress.accept("Embedding " + texts.size() + " chunks of " + pending.size() + " 1% ownership documents ("
                + embeddings.model() + ")");
        List<float[]> vectors = embeddings.embed(texts);
        progress.accept("Storing " + pending.size() + " 1% ownership documents in the vector store");
        int at = 0;
        int chunks = 0;
        for (Pending p : pending) {
            List<float[]> own = vectors.subList(at, at + p.chunks().size());
            at += p.chunks().size();
            String title = p.company().ticker() + " shareholders of more than 1% (" + p.digest().firstDate() + " to "
                    + p.digest().latestDate() + ", " + p.digest().months()
                    + (p.digest().months() == 1 ? " month)" : " months)");
            rag.store(new NewDocument(p.company().companyId(), SourceType.XLSX, null, TYPE.name(), title, SOURCE_NAME,
                    null, fileId, fileName, p.digest().latestDate().atStartOfDay(JAKARTA).toInstant(), null,
                    p.text().length(), embeddings.model(), jobId), p.chunks(), own);
            chunks += p.chunks().size();
        }
        return new int[] {pending.size(), chunks};
    }
}

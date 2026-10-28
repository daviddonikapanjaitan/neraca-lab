package com.neracalab.backend.ownership;

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

import com.neracalab.backend.ownership.OwnershipDigest.Digest;
import com.neracalab.backend.ownership.OwnershipParser.Movement;
import com.neracalab.backend.ownership.OwnershipParser.Report;
import com.neracalab.backend.ownership.OwnershipRepository.HolderDay;
import com.neracalab.backend.ownership.OwnershipRepository.Outcome;
import com.neracalab.backend.ownership.OwnershipRepository.Stored;
import com.neracalab.backend.rag.EmbeddingClient;
import com.neracalab.backend.rag.ExcelDocumentType;
import com.neracalab.backend.rag.RagProperties;
import com.neracalab.backend.rag.RagRepository;
import com.neracalab.backend.rag.RagRepository.Company;
import com.neracalab.backend.rag.RagRepository.NewDocument;
import com.neracalab.backend.rag.RagRepository.SourceType;
import com.neracalab.backend.rag.TextChunker;

/**
 * One upload of Ingestion > Excel Documents (RAG), Ownership (>=5%): KSEI's list of a day is read
 * ({@link OwnershipParser}) and stored ({@link OwnershipRepository}, Companies > Ownership (>=5%)); then, for every
 * company of the list that is stored in {@code company}, its document in the RAG vector store is written again from
 * every stored day ({@link OwnershipDigest#text}): the holders of 5% or more on the latest day, their accounts'
 * brokers and custodians, every movement. One document per company (source XLSX, key {@code OWNERSHIP_5_PERCENT}), so
 * the AI analysis retrieves the whole picture rather than seven near-identical days. Each chunk is embedded with the
 * company and the date in front of it; the stored text is the chunk alone.
 */
@Service
public class OwnershipIngestionService {

    private static final Logger log = LoggerFactory.getLogger(OwnershipIngestionService.class);
    static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    /** The source name of the RAG documents. */
    public static final String SOURCE_NAME = "KSEI list of the shareholders of more than 5%";

    /**
     * What an upload stored (the job result).
     *
     * @param exited             investors of the stored list of the day before that are not in this one (fell below
     *                           5%); null when the list of the day before is not stored
     * @param ragDocuments       ownership documents written in the vector store (stored companies of the list)
     * @param companiesNotStored companies of the list that are not stored in {@code company} (no RAG document; still on
     *                           Companies > Ownership (>=5%))
     * @param ragError           why the vector store could not be written; null when it was
     */
    public record OwnershipResult(ExcelDocumentType documentType, String exchange, String fileName,
                                  LocalDate reportDate, LocalDate previousDate, String title,
                                  String announcementNumber, int issuers, int holders, int accounts, int aliases,
                                  int buys, int sells, int unchanged, int entered, Integer exited, Outcome outcome,
                                  int ragDocuments, int chunks, int companiesNotStored, String embeddingModel,
                                  String ragError, List<String> warnings, List<String> notes) {
    }

    private final OwnershipRepository repository;
    private final RagRepository rag;
    private final EmbeddingClient embeddings;
    private final RagProperties properties;

    public OwnershipIngestionService(OwnershipRepository repository, RagRepository rag, EmbeddingClient embeddings,
                                     RagProperties properties) {
        this.repository = repository;
        this.rag = rag;
        this.embeddings = embeddings;
        this.properties = properties;
    }

    /**
     * Reads, stores and embeds the list of one day.
     *
     * @throws OwnershipParser.OwnershipFormatException not KSEI's list of the shareholders of more than 5%
     */
    public OwnershipResult ingest(UUID jobId, String exchange, ExcelDocumentType type, long fileId, String fileName,
                                  byte[] content, Consumer<String> progress) {
        progress.accept("Reading the list " + fileName);
        Report report = OwnershipParser.parse(content);
        List<String> tickers = report.tickers();
        progress.accept("Storing the list of " + report.reportDate() + ": " + report.holders().size() + " investors, "
                + report.accounts() + " accounts, " + tickers.size() + " companies");
        Stored stored = repository.store(exchange, type, report, fileId, fileName, jobId);

        Map<String, Company> companies = repository.companies(exchange, tickers);
        int documents = 0;
        int chunks = 0;
        String ragError = null;
        try {
            int[] written = embed(jobId, exchange, type, fileId, fileName, companies, progress);
            documents = written[0];
            chunks = written[1];
        } catch (EmbeddingClient.EmbeddingException e) {
            ragError = e.getMessage();
            log.warn("ownership list {} stored, its RAG documents not written: {}", report.reportDate(), e.getMessage());
        }
        return new OwnershipResult(type, exchange, fileName, report.reportDate(), report.previousDate(), report.title(),
                OwnershipRepository.announcementNumber(fileName), tickers.size(), report.holders().size(),
                report.accounts(), report.aliases(), report.count(Movement.BUY), report.count(Movement.SELL),
                report.count(Movement.UNCHANGED), report.count(Movement.ENTERED), stored.exited(), stored.outcome(),
                documents, chunks, tickers.size() - companies.size(), embeddings.model(), ragError, report.warnings(),
                report.notes());
    }

    /** The companies' ownership documents, written again from every stored day: [documents, chunks]. */
    private int[] embed(UUID jobId, String exchange, ExcelDocumentType type, long fileId, String fileName,
                        Map<String, Company> companies, Consumer<String> progress) {
        progress.accept("Writing the ownership documents of " + companies.size() + " stored companies");
        TextChunker chunker = new TextChunker(properties.chunkChars(), properties.chunkOverlap());
        record Pending(Company company, Digest digest, String text, List<TextChunker.Chunk> chunks) {
        }
        List<Pending> pending = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (Company company : companies.values()) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            List<HolderDay> days = repository.holders(exchange, type, company.ticker(), null, null);
            Digest digest = OwnershipDigest.of(company.ticker(), days);
            if (digest == null) {
                continue;
            }
            String text = OwnershipDigest.text(digest);
            List<TextChunker.Chunk> parts = chunker.chunk(List.of(new TextChunker.Part(text, null)));
            if (parts.isEmpty()) {
                continue;
            }
            String header = company.ticker() + " " + company.companyName() + " - shareholders of more than 5% (KSEI), "
                    + "as of " + digest.latestDate() + "\n";
            parts.forEach(c -> texts.add(header + c.text()));
            pending.add(new Pending(company, digest, text, parts));
        }
        if (pending.isEmpty()) {
            return new int[] {0, 0};
        }
        progress.accept("Embedding " + texts.size() + " chunks of " + pending.size() + " ownership documents ("
                + embeddings.model() + ")");
        List<float[]> vectors = embeddings.embed(texts);
        progress.accept("Storing " + pending.size() + " ownership documents in the vector store");
        int at = 0;
        int chunks = 0;
        for (Pending p : pending) {
            List<float[]> own = vectors.subList(at, at + p.chunks().size());
            at += p.chunks().size();
            String title = p.company().ticker() + " shareholders of more than 5% (" + p.digest().firstDate() + " to "
                    + p.digest().latestDate() + ", " + p.digest().days() + (p.digest().days() == 1 ? " day)" : " days)");
            rag.store(new NewDocument(p.company().companyId(), SourceType.XLSX, null, type.name(), title, SOURCE_NAME,
                    null, fileId, fileName, p.digest().latestDate().atStartOfDay(JAKARTA).toInstant(), null,
                    p.text().length(), embeddings.model(), jobId), p.chunks(), own);
            chunks += p.chunks().size();
        }
        return new int[] {pending.size(), chunks};
    }
}

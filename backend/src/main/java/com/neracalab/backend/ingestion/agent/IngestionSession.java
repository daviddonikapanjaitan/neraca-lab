package com.neracalab.backend.ingestion.agent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.neracalab.backend.ingestion.mapping.FilingInfo;
import com.neracalab.backend.ingestion.mapping.FilingMapper;
import com.neracalab.backend.ingestion.mapping.IncomeLineCategory;
import com.neracalab.backend.ingestion.mapping.MappedStatement;
import com.neracalab.backend.ingestion.mapping.SegmentExtraction;
import com.neracalab.backend.ingestion.mapping.ShareCapital;
import com.neracalab.backend.ingestion.mapping.StatementColumn;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.CompanyRow;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.WriteResult;

/**
 * State of one upload. The parsed workbook and every amount stay here, on the server: the model
 * only refers to columns, names and categories, so it can never alter a number.
 */
public final class IngestionSession {

    private final String id = UUID.randomUUID().toString();
    private final FilingMapper mapper;
    private volatile ShareCapital shareCapital;

    private volatile CompanyRow company;
    private volatile boolean companyLookedUp;
    private final Map<StatementColumn, Map<String, IncomeLineCategory>> classifications = new ConcurrentHashMap<>();
    private final Set<StatementColumn> extracted = ConcurrentHashMap.newKeySet();
    private final Set<StatementColumn> segmentsExtracted = ConcurrentHashMap.newKeySet();
    private final Map<StatementColumn, List<WriteResult>> savedStatements = new ConcurrentHashMap<>();
    private final Map<StatementColumn, List<String>> savedSegments = new ConcurrentHashMap<>();
    private volatile boolean sharesSaved;
    private volatile JobDeadline deadline = JobDeadline.NONE;
    private volatile Instant lastWrite;
    private volatile Instant derivedRefreshedAt;
    private final List<String> notes = new CopyOnWriteArrayList<>();

    public IngestionSession(FilingMapper mapper) {
        this.mapper = mapper;
        this.shareCapital = mapper.shareCapital();
        mapper.warnings().forEach(notes::add);
    }

    public String id() {
        return id;
    }

    public FilingMapper mapper() {
        return mapper;
    }

    public FilingInfo info() {
        return mapper.info();
    }

    public ShareCapital shareCapital() {
        return shareCapital;
    }

    /** The time limit of the job this session runs in ({@link JobDeadline#NONE} outside a job). */
    public JobDeadline deadline() {
        return deadline;
    }

    public void deadline(JobDeadline deadline) {
        this.deadline = deadline;
    }

    /** Replaces the share counts, e.g. with checked counts from a website when the filing gives none. */
    public void shareCapital(ShareCapital shares) {
        this.shareCapital = shares;
    }

    // ---- company
    public Optional<CompanyRow> company() {
        return Optional.ofNullable(company);
    }

    public void company(CompanyRow row) {
        this.company = row;
        this.companyLookedUp = true;
    }

    public void companyLookedUp() {
        this.companyLookedUp = true;
    }

    public boolean isCompanyLookedUp() {
        return companyLookedUp;
    }

    // ---- extraction (always re-mapped from the workbook, with the classifications given so far)
    public Map<String, IncomeLineCategory> classifications(StatementColumn column) {
        return classifications.computeIfAbsent(column, c -> new ConcurrentHashMap<>());
    }

    public Optional<MappedStatement> income(StatementColumn column) {
        return mapper.incomeStatement(column, classifications(column), shareCapital);
    }

    public Optional<MappedStatement> balance(StatementColumn column) {
        return mapper.balanceSheet(column, shareCapital);
    }

    public Optional<MappedStatement> cashFlow(StatementColumn column) {
        return mapper.cashFlow(column);
    }

    public List<MappedStatement> statements(StatementColumn column) {
        return java.util.stream.Stream.of(income(column), balance(column), cashFlow(column))
                .flatMap(Optional::stream).toList();
    }

    public Optional<SegmentExtraction> segments(StatementColumn column) {
        var revenue = income(column).map(s -> s.values().get("revenue")).orElse(null);
        return mapper.segments(column, revenue);
    }

    public void markExtracted(StatementColumn column) {
        extracted.add(column);
    }

    public boolean isExtracted(StatementColumn column) {
        return extracted.contains(column);
    }

    public void markSegmentsExtracted(StatementColumn column) {
        segmentsExtracted.add(column);
    }

    public boolean isSegmentsExtracted(StatementColumn column) {
        return segmentsExtracted.contains(column);
    }

    public boolean hasUnclassifiedLines() {
        return extracted.stream().anyMatch(c -> income(c).map(s -> !s.unclassified().isEmpty()).orElse(false));
    }

    /**
     * Whether a classification of the agent may be revised: an extracted column whose income statement still fails
     * a check after the agent classified some of its lines (BMRI FY2025: the tool was withdrawn after the first
     * classification, and the agent could not correct it).
     */
    public boolean hasRevisableClassifications() {
        return extracted.stream().anyMatch(this::isRevisable);
    }

    public boolean isRevisable(StatementColumn column) {
        return !classifications(column).isEmpty() && income(column).map(MappedStatement::hasErrors).orElse(false);
    }

    // ---- writes
    public void statementsSaved(StatementColumn column, List<WriteResult> results) {
        savedStatements.put(column, results);
        touched();
    }

    public Map<StatementColumn, List<WriteResult>> savedStatements() {
        return savedStatements;
    }

    public void segmentsSaved(StatementColumn column, List<String> names) {
        savedSegments.put(column, names);
        touched();
    }

    public Map<StatementColumn, List<String>> savedSegments() {
        return savedSegments;
    }

    public void sharesSaved() {
        sharesSaved = true;
        touched();
    }

    public boolean isSharesSaved() {
        return sharesSaved;
    }

    public void touched() {
        lastWrite = Instant.now();
    }

    public boolean hasWrites() {
        return lastWrite != null;
    }

    public void derivedRefreshed() {
        derivedRefreshedAt = Instant.now();
    }

    /** Derived tables are current when they were refreshed after the last write. */
    public boolean isDerivedCurrent() {
        return derivedRefreshedAt != null && lastWrite != null && !derivedRefreshedAt.isBefore(lastWrite);
    }

    public void note(String note) {
        notes.add(note);
    }

    public List<String> notes() {
        return notes;
    }
}

package com.neracalab.backend.ingestion.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.neracalab.backend.ingestion.mapping.SegmentExtraction;
import com.neracalab.backend.ingestion.mapping.StatementColumn;
import com.neracalab.backend.ingestion.persistence.IngestionRepository;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.SegmentRow;

/**
 * Finishes an ingestion without the model when the model fails mid-run after its retries (a provider that keeps
 * stalling: CEKA FY2025 had its statements saved, then one call stalled three times and the job failed). The
 * remaining work of a standard filing is mechanical and is done with the agent's own tools, so every validation
 * and lock applies: register the company (display name from the legal name), save every column that extracts
 * ready, save revenue segments under the filing's names and slot types (an existing segment's English name is
 * reused), save share counts, refresh derived data. Anything that needs judgement - classifying an unknown income
 * line, a column that fails its checks - stays open, and the deterministic verification decides the outcome.
 */
final class DeterministicFinisher {

    private DeterministicFinisher() {
    }

    /** Runs the remaining steps; returns what was done (and what could not be), for the job notes. */
    static List<String> finish(IngestionTools tools, IngestionSession session, IngestionRepository repository,
                               JobDeadline deadline) {
        return finish(tools, session, repository, deadline, Map.of());
    }

    /**
     * As {@link #finish(IngestionTools, IngestionSession, IngestionRepository, JobDeadline)}, with the English names
     * (and the type of a residual-slot line) the model gave the company's new segments ({@link IngestionAgent}'s fast
     * path); a segment it did not name keeps the rules of {@link #namings(SegmentExtraction, List)}.
     */
    static List<String> finish(IngestionTools tools, IngestionSession session, IngestionRepository repository,
                               JobDeadline deadline, Map<String, IngestionTools.SegmentNaming> named) {
        List<String> log = new ArrayList<>();
        if (session.company().isEmpty()) {
            boolean found = step(log, deadline, "findCompany", () -> tools.findCompany().found());
            if (Boolean.FALSE.equals(found)) {
                String name = displayName(session.info().legalName(), session.info().ticker());
                step(log, deadline, "registerCompany(" + name + ")", () -> tools.registerCompany(name));
            }
        }
        if (session.company().isEmpty()) {
            log.add("company not available: nothing can be saved");
            return log;
        }
        for (StatementColumn column : session.mapper().columns()) {
            if (session.savedStatements().containsKey(column)) {
                continue;
            }
            var extraction = step(log, deadline, "extractStatements(" + column + ")", () -> tools.extractStatements(column));
            if (extraction == null || extraction.statements().isEmpty()) {
                continue;
            }
            if (extraction.readyToSave()) {
                step(log, deadline, "saveStatements(" + column + ")", () -> tools.saveStatements(column));
            } else {
                log.add("saveStatements(" + column + ") left open: " + extraction.next());
            }
        }
        long companyId = session.company().get().companyId();
        for (StatementColumn column : session.mapper().columns()) {
            var segments = session.segments(column);
            if (segments.isEmpty() || !session.savedStatements().containsKey(column) || session.savedSegments().containsKey(column)) {
                continue;
            }
            var result = step(log, deadline, "extractRevenueSegments(" + column + ")", () -> tools.extractRevenueSegments(column));
            if (result == null || !result.available() || !result.failedChecks().isEmpty()) {
                log.add("saveRevenueSegments(" + column + ") left open: segments do not reconcile");
                continue;
            }
            List<IngestionTools.SegmentNaming> namings = namings(segments.get(), repository.segments(companyId), named);
            step(log, deadline, "saveRevenueSegments(" + column + ")", () -> tools.saveRevenueSegments(column, namings));
        }
        if (session.shareCapital().resolved() && !session.isSharesSaved()) {
            step(log, deadline, "saveShareSnapshots", tools::saveShareSnapshots);
        }
        if (session.hasWrites() && !session.isDerivedCurrent()) {
            step(log, deadline, "refreshDerivedData", tools::refreshDerivedData);
        }
        return log;
    }

    /** Each segment under its filed name and slot type; the English name of an existing segment of the same name, else the filed name. */
    static List<IngestionTools.SegmentNaming> namings(SegmentExtraction extraction, List<SegmentRow> existing) {
        return namings(extraction, existing, Map.of());
    }

    /**
     * As {@link #namings(SegmentExtraction, List)}, but a segment the company does not have yet takes the model's
     * English name from {@code named}, and its type where the filing allows it (the slot's type, or OTHER for a
     * residual-slot line - what saveRevenueSegments accepts).
     */
    static List<IngestionTools.SegmentNaming> namings(SegmentExtraction extraction, List<SegmentRow> existing,
                                                      Map<String, IngestionTools.SegmentNaming> named) {
        Map<String, String> english = existing.stream()
                .collect(Collectors.toMap(SegmentRow::segmentName, SegmentRow::segmentNameEn, (a, b) -> a));
        return extraction.lines().stream()
                .map(l -> {
                    IngestionTools.SegmentType filed = IngestionTools.SegmentType.valueOf(l.filingType());
                    IngestionTools.SegmentNaming model = english.containsKey(l.name()) ? null : named.get(l.name());
                    if (model == null || model.segmentNameEn() == null || model.segmentNameEn().isBlank()) {
                        return new IngestionTools.SegmentNaming(l.name(), english.getOrDefault(l.name(), l.name()), filed);
                    }
                    boolean allowed = model.segmentType() == filed
                            || (l.residualSlot() && model.segmentType() == IngestionTools.SegmentType.OTHER);
                    return new IngestionTools.SegmentNaming(l.name(), model.segmentNameEn().trim(),
                            allowed ? model.segmentType() : filed);
                })
                .toList();
    }

    /** "PT Wilmar Cahaya Indonesia Tbk" -> "Wilmar Cahaya Indonesia" (the ticker when nothing is left). */
    static String displayName(String legalName, String ticker) {
        String name = legalName == null ? "" : legalName.trim();
        name = name.replaceFirst("(?i)^PT\\.?\\s+", "").replaceFirst("(?i)(^|[,\\s]+)Tbk\\.?$", "").trim();
        if (name.isEmpty()) {
            return ticker;
        }
        return name.length() > 120 ? name.substring(0, 120).trim() : name;
    }

    /** One step: never past the deadline; a tool's refusal is recorded and the next step runs. */
    private static <T> T step(List<String> log, JobDeadline deadline, String what, Supplier<T> action) {
        deadline.check(what);
        try {
            T result = action.get();
            log.add(what + " done");
            return result;
        } catch (JobDeadline.JobTimeoutException e) {
            throw e;
        } catch (RuntimeException e) {
            log.add(what + " failed: " + ToolExecutor.rootMessage(e));
            return null;
        }
    }
}

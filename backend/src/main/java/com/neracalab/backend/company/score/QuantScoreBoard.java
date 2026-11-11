package com.neracalab.backend.company.score;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.company.score.ScorecardService.AgentCard;
import com.neracalab.backend.company.score.ScorecardService.Scorecards;
import com.neracalab.backend.screening.data.FundamentalRepository;
import com.neracalab.backend.screening.data.FundamentalRepository.StockSnapshot;

/**
 * The overall Quant Score of every company of an exchange, for the sortable column of the company list
 * ({@code GET /api/v1/companies/quant-scores?exchange=IDX}). Each company's score is the one its Quant Score tab shows:
 * {@link ScorecardService#compute} on the same data, so the list and the tab never differ. No model call, nothing stored.
 * <p>
 * Computing ~940 companies takes a few seconds (8 at a time), so the result is kept in memory per exchange and computed
 * again when {@link ScorecardRepository#fingerprint()} changes (a filing, a valuation, a company, a screening snapshot,
 * an exchange rate) or after {@code neracalab.companies.quant-score-ttl} (default 10 minutes: prices and metrics
 * rewritten in place). Concurrent requests of one exchange wait for one computation.
 */
@Service
public class QuantScoreBoard {

    private static final Logger log = LoggerFactory.getLogger(QuantScoreBoard.class);

    /** Companies computed at once (each runs its own queries; the pool has 40 connections). */
    static final int PARALLEL = 8;

    /**
     * @param overall       null when no agent has enough of its scorecard known (as on the tab)
     * @param verdict       band of the overall score
     * @param agentsCounted agents in the overall (of 6)
     */
    public record Score(String ticker, Double overall, String verdict, boolean financial, int agentsCounted) {
    }

    /**
     * @param computedAt when the scores were computed
     * @param millis     how long that took
     * @param cached     served from memory
     * @param scored     companies with an overall score
     */
    public record Board(String exchange, OffsetDateTime computedAt, long millis, boolean cached, int companies,
                        int scored, double minCoverage, double riskWeight, List<Score> scores) {
    }

    private record Entry(String fingerprint, Instant at, Board board) {
    }

    private final ScorecardService scorecards;
    private final ScorecardRepository repository;
    private final FundamentalRepository fundamentals;
    private final Duration ttl;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public QuantScoreBoard(ScorecardService scorecards, ScorecardRepository repository,
                           FundamentalRepository fundamentals,
                           @Value("${neracalab.companies.quant-score-ttl:PT10M}") Duration ttl) {
        this.scorecards = scorecards;
        this.repository = repository;
        this.fundamentals = fundamentals;
        this.ttl = ttl;
    }

    public Board board(String exchangeCode) {
        Exchange exchange = Exchange.of(exchangeCode);
        String key = exchange.code();
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            String fingerprint = repository.fingerprint();
            Entry entry = cache.get(key);
            if (entry != null && entry.fingerprint().equals(fingerprint)
                    && entry.at().plus(ttl).isAfter(Instant.now())) {
                Board b = entry.board();
                return new Board(b.exchange(), b.computedAt(), b.millis(), true, b.companies(), b.scored(),
                        b.minCoverage(), b.riskWeight(), b.scores());
            }
            Board board = compute(exchange);
            cache.put(key, new Entry(fingerprint, Instant.now(), board));
            return board;
        }
    }

    /** Forgets the computed scores (tests). */
    void clear() {
        cache.clear();
    }

    private Board compute(Exchange exchange) {
        long start = System.nanoTime();
        List<ScorecardRepository.CompanyRow> companies = repository.companies(exchange.code());
        Map<String, StockSnapshot> snapshots = new HashMap<>();
        for (StockSnapshot s : fundamentals.latestSnapshots(exchange)) {
            snapshots.put(s.ticker(), s);
        }
        List<Score> scores = new ArrayList<>(companies.size());
        Semaphore slots = new Semaphore(PARALLEL);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Score>> futures = new ArrayList<>(companies.size());
            for (ScorecardRepository.CompanyRow company : companies) {
                futures.add(pool.submit(() -> {
                    slots.acquire();
                    try {
                        return score(scorecards.compute(exchange, company, snapshots.get(company.ticker())));
                    } finally {
                        slots.release();
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    scores.add(futures.get(i).get());
                } catch (ExecutionException e) {
                    // one company's data never hides the others: its score is unknown
                    log.warn("Quant score of {} {} failed: {}", exchange.code(), companies.get(i).ticker(),
                            e.getCause() == null ? e.toString() : e.getCause().toString());
                    scores.add(new Score(companies.get(i).ticker(), null, null, false, 0));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while computing the quant scores", e);
                }
            }
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        int scored = (int) scores.stream().filter(s -> s.overall() != null).count();
        log.info("Quant scores of {}: {} companies, {} scored, {} ms", exchange.code(), scores.size(), scored, millis);
        return new Board(exchange.code(), OffsetDateTime.now(ZoneOffset.UTC), millis, false, scores.size(), scored,
                scorecards.minCoverage(), scorecards.riskWeight(), scores);
    }

    static Score score(Scorecards s) {
        int counted = (int) s.agents().stream().filter(AgentCard::inOverall).count();
        return new Score(s.ticker(), s.overall(), s.verdict(), s.financial(), counted);
    }
}

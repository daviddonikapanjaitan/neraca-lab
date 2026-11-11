package com.neracalab.backend.company.score;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.technical.TechnicalService;
import com.neracalab.backend.technical.TechnicalService.CompanySummary;
import com.neracalab.backend.technical.engine.TechnicalScore;

/**
 * The overall Technical Score of every company of an exchange, for the sortable column of the company list
 * ({@code GET /api/v1/companies/technical-scores?exchange=IDX}). Each company's score is the one its Technical (Score)
 * tab shows: {@link TechnicalScore} of the same preset summaries of Companies &gt; Technicals, so the list and the tab
 * never differ. No model call, nothing stored besides the indicator snapshots the summaries already keep.
 * <p>
 * Kept in memory per exchange and computed again when the exchange's prices, the exchange rates or the benchmark index
 * change ({@link TechnicalService#pricesFingerprint}) or after {@code neracalab.companies.technical-score-ttl} (default
 * 1 hour: corporate events, adjusted closes rewritten in place). The first computation of ~940 companies takes
 * 15 - 40 seconds. Concurrent requests of one exchange wait for one computation.
 */
@Service
public class TechnicalScoreBoard {

    private static final Logger log = LoggerFactory.getLogger(TechnicalScoreBoard.class);

    /** Companies read at once (each runs its own queries; the pool has 40 connections). */
    static final int PARALLEL = 12;

    /**
     * @param overall      0-100, the average of the four agents; null without a summary
     * @param stance       of the overall: BUY, ACCUMULATE, WAIT, AVOID
     * @param agentsBuying agents whose own stance is Buy or Accumulate (of {@code agents})
     * @param asOfDate     the last daily bar read
     * @param error        why there is no score (no prices, too little history)
     */
    public record Score(String ticker, Double overall, String stance, int agentsBuying, int agents, String asOfDate,
                        String error) {
    }

    /**
     * @param computedAt when the scores were computed
     * @param millis     how long that took
     * @param cached     served from memory
     * @param scored     companies with an overall score
     */
    public record Board(String exchange, OffsetDateTime computedAt, long millis, boolean cached, int companies,
                       int scored, List<Score> scores) {
    }

    private record Entry(String fingerprint, Instant at, Board board) {
    }

    private final TechnicalService technical;
    private final Duration ttl;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public TechnicalScoreBoard(TechnicalService technical,
                               @Value("${neracalab.companies.technical-score-ttl:PT1H}") Duration ttl) {
        this.technical = technical;
        this.ttl = ttl;
    }

    public Board board(String exchangeCode) {
        Exchange exchange = Exchange.of(exchangeCode);
        String key = exchange.code();
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            String fingerprint = technical.pricesFingerprint(exchange);
            Entry entry = cache.get(key);
            if (entry != null && entry.fingerprint().equals(fingerprint)
                    && entry.at().plus(ttl).isAfter(Instant.now())) {
                Board b = entry.board();
                return new Board(b.exchange(), b.computedAt(), b.millis(), true, b.companies(), b.scored(), b.scores());
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
        List<CompanySummary> summaries = technical.companySummaries(exchange, PARALLEL);
        List<Score> scores = new ArrayList<>(summaries.size());
        for (CompanySummary s : summaries) {
            scores.add(score(s));
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        int scored = (int) scores.stream().filter(s -> s.overall() != null).count();
        log.info("Technical scores of {}: {} companies, {} scored, {} ms", exchange.code(), scores.size(), scored, millis);
        return new Board(exchange.code(), OffsetDateTime.now(ZoneOffset.UTC), millis, false, scores.size(), scored,
                scores);
    }

    static Score score(CompanySummary s) {
        if (s.summary() == null) {
            return new Score(s.ticker(), null, null, 0, 0, null, s.error());
        }
        TechnicalScore.Score t = TechnicalScore.of(s.summary());
        int buying = t.stances().getOrDefault("BUY", 0) + t.stances().getOrDefault("ACCUMULATE", 0);
        Object asOf = s.summary().get("asOfDate");
        return new Score(s.ticker(), t.overall(), t.stance(), buying, t.agents().size(),
                asOf == null ? null : asOf.toString(), t.overall() == null ? t.summary() : null);
    }
}

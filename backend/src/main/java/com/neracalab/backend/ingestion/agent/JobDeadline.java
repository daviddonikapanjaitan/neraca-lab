package com.neracalab.backend.ingestion.agent;

import java.time.Duration;
import java.time.Instant;

/**
 * The time limit of one upload ingestion job ({@code neracalab.ingestion.job-timeout}, 5 minutes), counted
 * from the moment the job starts running. The agent stops at it: a model call waits at most until the
 * deadline and no tool (in particular no write) starts after it, so a timed-out job really stops instead of
 * writing on in the background.
 */
public final class JobDeadline {

    /** No limit (tests, direct calls). */
    public static final JobDeadline NONE = new JobDeadline(null, null);

    private final Instant at;
    private final Duration limit;

    private JobDeadline(Instant at, Duration limit) {
        this.at = at;
        this.limit = limit;
    }

    /** A deadline {@code limit} from now. */
    public static JobDeadline after(Duration limit) {
        return new JobDeadline(Instant.now().plus(limit), limit);
    }

    public boolean isNone() {
        return at == null;
    }

    public Duration limit() {
        return limit;
    }

    /** Time left, zero when passed; {@code null} without a limit. */
    public Duration remaining() {
        if (at == null) {
            return null;
        }
        Duration left = Duration.between(Instant.now(), at);
        return left.isNegative() ? Duration.ZERO : left;
    }

    public boolean passed() {
        return at != null && !Instant.now().isBefore(at);
    }

    /** @throws JobTimeoutException when the deadline has passed */
    public void check(String before) {
        if (passed()) {
            throw new JobTimeoutException(limit, before);
        }
    }

    /** The job ran out of time; never caught by the agent's fallbacks. */
    public static final class JobTimeoutException extends RuntimeException {

        public JobTimeoutException(Duration limit, String before) {
            super("The ingestion exceeded its time limit of " + format(limit) + " (stopped before " + before
                    + "); what was saved before the limit is kept. Submit the file again to retry.");
        }

        private static String format(Duration d) {
            return describe(d);
        }
    }

    /** "5 minutes", "1 minute", "90 seconds", "1 second" */
    public static String describe(Duration d) {
        if (d.toSecondsPart() == 0 && d.toMinutes() > 0) {
            return d.toMinutes() + (d.toMinutes() == 1 ? " minute" : " minutes");
        }
        long seconds = Math.max(1, d.toSeconds());
        return seconds + (seconds == 1 ? " second" : " seconds");
    }
}

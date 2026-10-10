package com.neracalab.backend.job;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The worker threads of a job queue: {@code count} daemon threads that all run the same loop (take a job,
 * process it) until they are interrupted by {@link #stop}.
 */
public final class WorkerThreads {

    /** Longest {@link #stop} waits for the workers to end. */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    private final String name;
    private final int count;
    private final Runnable loop;
    private volatile List<Thread> threads;

    /**
     * @param name  thread name; with several workers "name-1", "name-2", ...
     * @param count number of threads (at least 1)
     * @param loop  the worker loop; it ends when its thread is interrupted
     */
    public WorkerThreads(String name, int count, Runnable loop) {
        if (count < 1) {
            throw new IllegalArgumentException(name + ": at least one worker is needed, not " + count);
        }
        this.name = name;
        this.count = count;
        this.loop = loop;
    }

    public int count() {
        return count;
    }

    public synchronized void start() {
        if (threads != null) {
            return;
        }
        List<Thread> started = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            Thread t = new Thread(loop, count == 1 ? name : name + "-" + i);
            t.setDaemon(true);
            started.add(t);
        }
        threads = started;
        started.forEach(Thread::start);
    }

    /** Interrupts every worker and waits (10 s for all together) until they have ended. */
    public synchronized void stop() {
        List<Thread> running = threads;
        threads = null;
        if (running == null) {
            return;
        }
        running.forEach(Thread::interrupt);
        Instant deadline = Instant.now().plus(STOP_TIMEOUT);
        try {
            for (Thread t : running) {
                Duration left = Duration.between(Instant.now(), deadline);
                if (!left.isPositive()) {
                    break;
                }
                t.join(left);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isRunning() {
        return threads != null;
    }
}

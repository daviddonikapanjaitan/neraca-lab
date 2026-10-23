package com.neracalab.backend.ingestion.agent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Everything the agent did, returned to the caller for transparency. */
public final class AgentTrace {

    /**
     * @param parallelGroup calls with the same group number ran concurrently (0 = ran alone)
     */
    public record ToolInvocation(int round, int iteration, String tool, String arguments, String result,
                                 boolean error, long durationMs, int parallelGroup) {
    }

    /** One model turn of the execution loop: its "Thought" (ReAct) and the tools it requested. */
    public record Step(int round, int iteration, String thought, List<String> requestedTools, List<String> offeredTools) {
    }

    private final List<Step> steps = new CopyOnWriteArrayList<>();
    private final List<ToolInvocation> invocations = new CopyOnWriteArrayList<>();
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger parallelGroups = new AtomicInteger();
    private final AtomicInteger modelRetries = new AtomicInteger();
    private final java.util.Set<String> modelsUsed = new java.util.concurrent.ConcurrentSkipListSet<>();
    private final JobDeadline deadline;
    private final ModelCallListener listener;
    private volatile String stage = ModelCallListener.EXECUTE;

    public AgentTrace() {
        this(JobDeadline.NONE);
    }

    /** @param deadline the time limit of the run: model calls and tools stop at it */
    public AgentTrace(JobDeadline deadline) {
        this(deadline, ModelCallListener.NONE);
    }

    /** @param listener told every model call of the run (tokens and cost of the job) */
    public AgentTrace(JobDeadline deadline, ModelCallListener listener) {
        this.deadline = deadline;
        this.listener = listener == null ? ModelCallListener.NONE : listener;
    }

    /** What the next model calls are for ({@link ModelCallListener#PLAN}, ...); the model calls of a run are sequential. */
    public void stage(String stage) {
        this.stage = stage;
    }

    public String stage() {
        return stage;
    }

    public ModelCallListener listener() {
        return listener;
    }

    public JobDeadline deadline() {
        return deadline;
    }

    public void step(Step step) {
        steps.add(step);
    }

    public void invocation(ToolInvocation invocation) {
        invocations.add(invocation);
    }

    public void modelCall() {
        modelCalls.incrementAndGet();
    }

    public void modelRetry() {
        modelRetries.incrementAndGet();
    }

    public int nextParallelGroup() {
        return parallelGroups.incrementAndGet();
    }

    public List<Step> steps() {
        return List.copyOf(steps);
    }

    public List<ToolInvocation> invocations() {
        return List.copyOf(invocations);
    }

    public int modelCalls() {
        return modelCalls.get();
    }

    public int parallelGroups() {
        return parallelGroups.get();
    }

    public int modelRetries() {
        return modelRetries.get();
    }

    /** Records the model a response reports (the provider's id, e.g. deepseek/deepseek-v4.1-flash-1017). */
    public void modelUsed(String model) {
        if (model != null && !model.isBlank()) {
            modelsUsed.add(model);
        }
    }

    /** Distinct models the responses of this run came from, sorted. */
    public List<String> modelsUsed() {
        return List.copyOf(modelsUsed);
    }
}

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
}

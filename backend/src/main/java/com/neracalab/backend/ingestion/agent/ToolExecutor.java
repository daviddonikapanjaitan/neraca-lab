package com.neracalab.backend.ingestion.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.tool.ToolCallback;

/**
 * Executes the tool calls of one model turn.
 * <ul>
 *   <li>Parallel Tool Calling: consecutive read-only calls run concurrently on virtual threads.</li>
 *   <li>Sequential Tool Calling: write calls run one after another, in the order the model asked.</li>
 * </ul>
 * Responses keep the order of the calls. A failing tool returns its error message to the model
 * (so it can react) instead of aborting the loop.
 */
final class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);
    private static final int MAX_RESULT_CHARS = 12_000;

    private ToolExecutor() {
    }

    static List<ToolResponse> execute(List<ToolCall> calls, Map<String, ToolCallback> offered, AgentTrace trace,
                                      int round, int iteration) {
        List<ToolResponse> responses = new ArrayList<>(calls.size());
        int i = 0;
        while (i < calls.size()) {
            int j = i;
            while (j < calls.size() && IngestionTools.READ_ONLY.contains(calls.get(j).name())) {
                j++;
            }
            if (j - i > 1) {
                responses.addAll(parallel(calls.subList(i, j), offered, trace, round, iteration));
                i = j;
            } else {
                responses.add(run(calls.get(i), offered, trace, round, iteration, 0));
                i++;
            }
        }
        return responses;
    }

    private static List<ToolResponse> parallel(List<ToolCall> calls, Map<String, ToolCallback> offered, AgentTrace trace,
                                               int round, int iteration) {
        int group = trace.nextParallelGroup();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ToolResponse>> futures = calls.stream()
                    .map(c -> pool.submit(() -> run(c, offered, trace, round, iteration, group)))
                    .toList();
            List<ToolResponse> out = new ArrayList<>();
            for (Future<ToolResponse> f : futures) {
                out.add(f.get());
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Parallel tool execution failed", e);
        }
    }

    private static ToolResponse run(ToolCall call, Map<String, ToolCallback> offered, AgentTrace trace,
                                    int round, int iteration, int group) {
        long start = System.nanoTime();
        String result;
        boolean error = false;
        ToolCallback tool = offered.get(call.name());
        if (tool == null) {
            error = true;
            result = "{\"error\":\"Tool '" + call.name() + "' is not available at this point. Available tools: "
                    + offered.keySet() + "\"}";
        } else {
            try {
                String args = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
                result = tool.call(args);
            } catch (RuntimeException e) {
                error = true;
                result = "{\"error\":" + quote(rootMessage(e)) + "}";
            }
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        log.info("tool {} {} -> {}{} ({} ms{})", call.name(), call.arguments(), error ? "ERROR " : "",
                abbreviate(result, 300), ms, group > 0 ? ", parallel group " + group : "");
        trace.invocation(new AgentTrace.ToolInvocation(round, iteration, call.name(), call.arguments(),
                abbreviate(result, 2_000), error, ms, group));
        return new ToolResponse(call.id(), call.name(), abbreviate(result, MAX_RESULT_CHARS));
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    static String abbreviate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }
}

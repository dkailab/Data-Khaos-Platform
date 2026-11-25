package com.datakhaos.workflow.flink;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Vertex counters returned in one JobManager request. */
public record FlinkPressureSample(String jobState, long sampledAtMs, Map<String, Counter> counters) {
    public static final Set<String> TERMINAL_STATES = Set.of("FINISHED", "FAILED", "CANCELED");

    static FlinkPressureSample from(JsonNode job, long sampledAtMs) {
        var counters = new HashMap<String, Counter>();
        // Retain entries with unknown readings so they prevent false recovery.
        for (JsonNode vertex : job.path("vertices")) {
            String id = vertex.path("id").asText();
            if (!id.matches("[a-f0-9]{32}") || "FINISHED".equals(vertex.path("status").asText())) continue;
            int parallelism = vertex.path("parallelism").asInt(0);
            long started = integer(vertex.path("start-time"));
            long accumulated = integer(vertex.path("metrics").path("accumulated-backpressured-time"));
            boolean running = "RUNNING".equals(vertex.path("status").asText());
            counters.put(id, new Counter(parallelism, started, accumulated, running));
        }
        return new FlinkPressureSample(job.path("state").asText(), sampledAtMs, Map.copyOf(counters));
    }

    private static long integer(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() ? node.asLong() : -1;
    }

    public record Counter(int parallelism, long startedAt, long totalMs, boolean running) {
        public boolean usable() {
            return running && parallelism > 0 && startedAt > 0 && totalMs >= 0;
        }
    }
}

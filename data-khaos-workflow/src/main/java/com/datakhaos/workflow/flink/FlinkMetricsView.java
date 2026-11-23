package com.datakhaos.workflow.flink;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.regex.Pattern;

public record FlinkMetricsView(List<Vertex> vertices, String selectedVertexId, Sample sample) {
    private static final Pattern ID = Pattern.compile("[a-f0-9]{32}");
    private static final String INPUT = "numRecordsInPerSecond";
    private static final String OUTPUT = "numRecordsOutPerSecond";
    private static final String PRESSURE = "backPressuredTimeMsPerSecond";
    private static final String BUSY = "busyTimeMsPerSecond";
    private static final String IDLE = "idleTimeMsPerSecond";
    private static final String WATERMARK = "currentInputWatermark";
    private static final List<String> METRICS = List.of(INPUT, OUTPUT, PRESSURE, BUSY, IDLE, WATERMARK);

    static List<Vertex> vertices(JsonNode job) {
        var vertices = new ArrayList<Vertex>();
        for (JsonNode row : job.path("vertices")) {
            String id = row.path("id").asText();
            if (!ID.matcher(id).matches()) continue;
            String name = row.path("name").asText().strip();
            String status = row.path("status").asText();
            int parallelism = row.path("parallelism").asInt();
            vertices.add(new Vertex(id, name.isEmpty() ? id : name.substring(0, Math.min(name.length(), 240)),
                    status.matches("[A-Z_]{1,24}") ? status : "UNKNOWN", Math.max(parallelism, 0)));
        }
        return List.copyOf(vertices);
    }

    static List<String> availableMetrics(JsonNode response) {
        if (!response.isArray()) {
            throw new FlinkRuntimeClient.RuntimeFailure("Flink 未返回有效指标列表");
        }
        Set<String> available = new HashSet<>();
        response.forEach(metric -> available.add(metric.path("id").asText()));
        return METRICS.stream().filter(available::contains).toList();
    }

    static Sample sample(JsonNode response) {
        if (!response.isArray()) {
            throw new FlinkRuntimeClient.RuntimeFailure("Flink 未返回有效指标样本");
        }
        Double in = null;
        Double out = null;
        Double pressure = null;
        Double busy = null;
        Double idle = null;
        Instant watermark = null;
        for (JsonNode metric : response) {
            switch (metric.path("id").asText()) {
                case INPUT -> in = bounded(metric.path("sum"), Double.MAX_VALUE);
                case OUTPUT -> out = bounded(metric.path("sum"), Double.MAX_VALUE);
                case PRESSURE -> pressure = bounded(metric.path("max"), 1000);
                case BUSY -> busy = bounded(metric.path("avg"), 1000);
                case IDLE -> idle = bounded(metric.path("avg"), 1000);
                case WATERMARK -> watermark = watermark(metric.path("min"));
                default -> { }
            }
        }
        if (in == null && out == null && pressure == null && busy == null && idle == null && watermark == null) {
            return null;
        }
        return new Sample(Instant.now(), in, out, pressure, busy, idle, watermark);
    }

    private static Double bounded(JsonNode node, double limit) {
        double value = numeric(node);
        return Double.isFinite(value) && value >= 0 && value <= limit ? value : null;
    }

    private static double numeric(JsonNode node) {
        return node.isNumber() || node.isTextual() ? node.asDouble(Double.NaN) : Double.NaN;
    }

    private static Instant watermark(JsonNode node) {
        double millis = numeric(node);
        // Flink uses Long.MIN_VALUE before a watermark and Long.MAX_VALUE at end of input.
        return Double.isFinite(millis) && Math.abs(millis) <= 8_640_000_000_000_000d
                ? Instant.ofEpochMilli((long) millis) : null;
    }

    public record Vertex(String id, String name, String status, int parallelism) {}

    public record Sample(Instant sampledAt, Double recordsInPerSecond, Double recordsOutPerSecond,
                         Double maxBackpressureMsPerSecond, Double avgBusyMsPerSecond,
                         Double avgIdleMsPerSecond, Instant inputWatermark) {}
}

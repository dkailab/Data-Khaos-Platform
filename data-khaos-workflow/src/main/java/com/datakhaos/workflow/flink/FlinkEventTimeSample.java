package com.datakhaos.workflow.flink;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** A point-in-time observation for one execution vertex. */
public record FlinkEventTimeSample(String vertexId, String status, Instant sampledAt, Instant vertexStartedAt,
                                   int parallelism, Long inputRecords, Instant inputWatermark) {
    private static final long MAX_SAFE_COUNTER = 9_007_199_254_740_991L;

    static FlinkEventTimeSample from(JsonNode vertex, JsonNode metrics, Instant sampledAt) {
        JsonNode io = vertex.path("metrics");
        JsonNode count = io.path("read-records");
        Long inputRecords = "RUNNING".equals(vertex.path("status").asText())
                && io.path("read-records-complete").asBoolean(false) && count.isIntegralNumber()
                && count.canConvertToLong() && count.asLong() >= 0 && count.asLong() <= MAX_SAFE_COUNTER
                ? count.asLong() : null;
        JsonNode started = vertex.path("start-time");
        Instant startedAt = started.isIntegralNumber() && started.canConvertToLong() && started.asLong() > 0
                ? Instant.ofEpochMilli(started.asLong()) : null;
        Instant watermark = null;
        for (JsonNode metric : metrics) {
            if ("currentInputWatermark".equals(metric.path("id").asText())) {
                watermark = FlinkMetricsView.watermark(metric.path("min"));
                break;
            }
        }
        return new FlinkEventTimeSample(vertex.path("id").asText(), vertex.path("status").asText(), sampledAt,
                startedAt, vertex.path("parallelism").asInt(0), inputRecords, watermark);
    }
}

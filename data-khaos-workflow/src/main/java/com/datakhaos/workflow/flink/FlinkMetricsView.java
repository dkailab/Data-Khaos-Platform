package com.datakhaos.workflow.flink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

public record FlinkMetricsView(List<Vertex> vertices, String selectedVertexId, Sample sample) {
    private static final Pattern ID = Pattern.compile("[a-f0-9]{32}");
    private static final String INPUT = "numRecordsInPerSecond";
    private static final String OUTPUT = "numRecordsOutPerSecond";
    private static final String PRESSURE = "backPressuredTimeMsPerSecond";
    private static final String BUSY = "busyTimeMsPerSecond";
    private static final String IDLE = "idleTimeMsPerSecond";
    private static final String WATERMARK = "currentInputWatermark";
    private static final String OPERATOR_INPUT = "." + WATERMARK;
    private static final String OPERATOR_OUTPUT = ".currentOutputWatermark";
    private static final String KAFKA_LAG = ".KafkaSourceReader.KafkaConsumer.records-lag-max";
    private static final String PENDING = ".pendingRecords";
    private static final List<String> OPERATOR_SUFFIXES = List.of(OPERATOR_INPUT, OPERATOR_OUTPUT, KAFKA_LAG, PENDING);
    // Leave room for the Flink REST path and HTTP request line.
    private static final int MAX_QUERY_LENGTH = 2800;
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

    static MetricQuery availableMetrics(JsonNode response) {
        return selectMetrics(response, false);
    }

    static MetricQuery kafkaMetrics(JsonNode response) {
        return selectMetrics(response, true);
    }

    private static MetricQuery selectMetrics(JsonNode response, boolean kafkaOnly) {
        if (!response.isArray()) {
            throw new FlinkRuntimeClient.RuntimeFailure("Flink 未返回有效指标列表");
        }
        Set<String> available = new HashSet<>();
        response.forEach(metric -> available.add(metric.path("id").asText()));
        var query = new ArrayList<String>();
        if (!kafkaOnly) METRICS.stream().filter(available::contains).forEach(query::add);
        int length = String.join(",", query).length();
        boolean truncated = false;
        for (String id : available.stream().filter(metric -> kafkaOnly ? KAFKA_LAG.equals(operatorSuffix(metric)) : operatorSuffix(metric) != null).sorted().toList()) {
            if (id.indexOf(',') >= 0 || id.codePoints().anyMatch(Character::isISOControl)) {
                truncated = true;
                continue;
            }
            String encoded = URLEncoder.encode(id, StandardCharsets.UTF_8);
            if (length + encoded.length() + 1 > MAX_QUERY_LENGTH) {
                truncated = true;
                continue;
            }
            query.add(encoded);
            length += encoded.length() + 1;
        }
        return new MetricQuery(String.join(",", query), truncated);
    }

    static Long kafkaLag(JsonNode response, MetricQuery query) {
        if (!response.isArray() || query.operatorMetricsTruncated() || query.get().isEmpty()) return null;
        Set<String> expected = new HashSet<>(List.of(query.get().split(",")));
        long peak = 0;
        for (JsonNode entry : response) {
            if (!expected.remove(URLEncoder.encode(entry.path("id").asText(), StandardCharsets.UTF_8))) continue;
            Double value = bounded(entry.path("max"), 9_007_199_254_740_991d);
            if (value == null || value != Math.floor(value)) return null;
            peak = Math.max(peak, value.longValue());
        }
        return expected.isEmpty() ? peak : null;
    }

    static Sample sample(JsonNode response, boolean operatorMetricsTruncated) {
        if (!response.isArray()) {
            throw new FlinkRuntimeClient.RuntimeFailure("Flink 未返回有效指标样本");
        }
        Map<String, Map<String, JsonNode>> operatorGauges = new TreeMap<>();
        Double in = null;
        Double out = null;
        Double pressure = null;
        Double busy = null;
        Double idle = null;
        Instant watermark = null;
        for (JsonNode metric : response) {
            String id = metric.path("id").asText();
            switch (id) {
                case INPUT -> in = bounded(metric.path("sum"), Double.MAX_VALUE);
                case OUTPUT -> out = bounded(metric.path("sum"), Double.MAX_VALUE);
                case PRESSURE -> pressure = bounded(metric.path("max"), 1000);
                case BUSY -> busy = bounded(metric.path("avg"), 1000);
                case IDLE -> idle = bounded(metric.path("avg"), 1000);
                case WATERMARK -> watermark = watermark(metric.path("min"));
                default -> {
                    String suffix = operatorSuffix(id);
                    if (suffix != null) {
                        String operator = id.substring(0, id.length() - suffix.length());
                        operatorGauges.computeIfAbsent(operator, ignored -> new HashMap<>()).put(suffix, metric);
                    }
                }
            }
        }
        var operators = operatorGauges.entrySet().stream()
                .map(entry -> operator(entry.getKey(), entry.getValue()))
                .toList();
        if (in == null && out == null && pressure == null && busy == null && idle == null && watermark == null
                && operators.isEmpty() && !operatorMetricsTruncated) {
            return null;
        }
        return new Sample(Instant.now(), in, out, pressure, busy, idle, watermark, operators, operatorMetricsTruncated);
    }

    private static String operatorSuffix(String id) {
        for (String suffix : OPERATOR_SUFFIXES) {
            if (id.length() > suffix.length() && id.endsWith(suffix)) return suffix;
        }
        return null;
    }

    private static OperatorMetrics operator(String name, Map<String, JsonNode> gauges) {
        JsonNode unknown = MissingNode.getInstance();
        JsonNode pending = gauges.getOrDefault(PENDING, unknown);
        Double pendingRecords = bounded(pending.path("min"), Double.MAX_VALUE) == null
                ? null : bounded(pending.path("sum"), Double.MAX_VALUE);
        return new OperatorMetrics(name,
                watermark(gauges.getOrDefault(OPERATOR_INPUT, unknown).path("min")),
                watermark(gauges.getOrDefault(OPERATOR_OUTPUT, unknown).path("min")),
                bounded(gauges.getOrDefault(KAFKA_LAG, unknown).path("max"), Double.MAX_VALUE), pendingRecords);
    }

    private static Double bounded(JsonNode node, double limit) {
        double value = numeric(node);
        return Double.isFinite(value) && value >= 0 && value <= limit ? value : null;
    }

    private static double numeric(JsonNode node) {
        return node.isNumber() || node.isTextual() ? node.asDouble(Double.NaN) : Double.NaN;
    }

    static Instant watermark(JsonNode node) {
        double millis = numeric(node);
        // Flink uses Long.MIN_VALUE before a watermark and Long.MAX_VALUE at end of input.
        return Double.isFinite(millis) && Math.abs(millis) <= 8_640_000_000_000_000d
                ? Instant.ofEpochMilli((long) millis) : null;
    }

    record MetricQuery(String get, boolean operatorMetricsTruncated) {}

    public record OperatorMetrics(String name, Instant inputWatermark, Instant outputWatermark,
                                  Double kafkaMaxLag, Double pendingRecords) {}

    public record Vertex(String id, String name, String status, int parallelism) {}

    public record Sample(Instant sampledAt, Double recordsInPerSecond, Double recordsOutPerSecond,
                         Double maxBackpressureMsPerSecond, Double avgBusyMsPerSecond,
                         Double avgIdleMsPerSecond, Instant inputWatermark, List<OperatorMetrics> operators,
                         boolean operatorMetricsTruncated) {}
}

package com.datakhaos.workflow.flink;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class FlinkRuntimeClient {
    private static final Pattern JOB_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern JAR_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,250}\\.jar");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;
    private final String projectId;
    private final String sqlGateway;
    private final String jobManager;
    private final String clusterKey;
    private final Set<String> allowedJars;

    public FlinkRuntimeClient(ObjectMapper json, @Value("${FLINK_RUN_PROJECT_ID:}") String projectId,
                              @Value("${FLINK_RUN_SQL_GATEWAY:}") String sqlGateway,
                              @Value("${FLINK_RUN_JOBMANAGER:}") String jobManager,
                              @Value("${FLINK_RUN_ALLOWED_JARS:}") String allowedJars) {
        this.json = json;
        this.projectId = projectId.strip();
        this.sqlGateway = endpoint(sqlGateway);
        this.jobManager = endpoint(jobManager);
        this.allowedJars = Arrays.stream(allowedJars.split(",")).map(String::strip).filter(id -> !id.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (!this.allowedJars.stream().allMatch(FlinkRuntimeClient::validJarId)) {
            throw new IllegalArgumentException("FLINK_RUN_ALLOWED_JARS 包含无效的 JAR ID");
        }
        this.clusterKey = DigestUtil.sha256Hex(this.projectId + "\n" + this.sqlGateway + "\n" + this.jobManager);
    }

    public boolean enabled(String project) {
        return bound(project) && !sqlGateway.isEmpty();
    }

    public boolean bound(String project) {
        return projectId.equals(project) && !projectId.isEmpty() && !jobManager.isEmpty();
    }

    public boolean jarEnabled(String project) {
        return bound(project) && !allowedJars.isEmpty();
    }

    public static boolean validJarId(String value) {
        return value != null && JAR_ID.matcher(value).matches();
    }

    public boolean jarAllowed(String id) {
        return allowedJars.contains(id);
    }

    public void requireJar(String id) {
        if (!jarAllowed(id)) throw unavailable("JAR 未获准在当前集群运行");
        JsonNode files = call(jobManager, "GET", "/jars", null).path("files");
        if (!files.isArray()) throw unavailable("Flink 未返回 JAR 制品列表");
        for (JsonNode file : files) {
            if (id.equals(file.path("id").asText())) return;
        }
        throw unavailable("集群上找不到指定 JAR，请核对制品 ID");
    }

    public void submitJar(FlinkDefinition definition, String expectedJobId, String recoveryPath) {
        var jar = definition.jarJob();
        if (!jarAllowed(jar.jarId())) throw unavailable("JAR 未获准在当前集群运行");
        checkJobId(expectedJobId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("jobId", expectedJobId);
        payload.put("entryClass", jar.entryClass());
        payload.put("programArgsList", jar.arguments());
        payload.put("parallelism", definition.parallelism());
        payload.put("flinkConfiguration", Map.of("execution.runtime-mode",
                definition.mode().name().toLowerCase(Locale.ROOT)));
        if (recoveryPath != null) {
            payload.put("savepointPath", recoveryPath);
            payload.put("allowNonRestoredState", false);
        }
        String accepted = call(jobManager, "POST", "/jars/" + jar.jarId() + "/run", payload, Duration.ofSeconds(22))
                .path("jobid").asText();
        if (!expectedJobId.equals(accepted)) throw unavailable("Flink 返回的运行 ID 不匹配");
    }

    public Optional<EngineJob> findById(String id) {
        checkJobId(id);
        JsonNode jobs = call(jobManager, "GET", "/jobs/overview", null).path("jobs");
        if (!jobs.isArray()) throw unavailable("Flink 未返回作业列表");
        for (JsonNode row : jobs) {
            if (id.equals(row.path("jid").asText())) return Optional.of(engine(id, row.path("state").asText()));
        }
        return Optional.empty();
    }

    public String clusterKey() {
        return clusterKey;
    }

    public String open(String name, String recoveryPath) {
        Map<String, String> properties = recoveryPath == null ? Map.of("table.dml-sync", "false")
                : Map.of("table.dml-sync", "false", "execution.state-recovery.path", recoveryPath);
        return handle(call(sqlGateway, "POST", "/v2/sessions",
                Map.of("sessionName", name, "properties", properties)), "sessionHandle");
    }

    public String execute(String session, String statement) {
        return handle(call(sqlGateway, "POST", sessionPath(session) + "/statements",
                Map.of("statement", statement)), "operationHandle");
    }

    public void awaitSetup(String session, String statement, long deadline) {
        String operation = execute(session, statement);
        String path = sessionPath(session) + "/operations/" + operation + "/status";
        while (before(deadline)) {
            String status = call(sqlGateway, "GET", path, null).path("status").asText();
            if ("FINISHED".equals(status)) return;
            if (!"RUNNING".equals(status) && !"PENDING".equals(status) && !"INITIALIZED".equals(status)) {
                throw unavailable("Flink 未通过准备语句校验");
            }
            pause();
        }
        throw unavailable("Flink 准备语句处理超时");
    }

    public Optional<String> insert(String session, String statement, long deadline) {
        String operation = execute(session, statement);
        String path = sessionPath(session) + "/operations/" + operation + "/result/0?rowFormat=JSON";
        while (before(deadline)) {
            JsonNode result = call(sqlGateway, "GET", path, null);
            String id = result.path("jobID").asText();
            if (!JOB_ID.matcher(id).matches()) {
                id = result.path("results").path("data").path(0).path("fields").path(0).asText();
            }
            if (JOB_ID.matcher(id).matches()) return Optional.of(id);
            if (!"NOT_READY".equals(result.path("resultType").asText())) return Optional.empty();
            pause();
        }
        return Optional.empty();
    }

    public void close(String session) {
        call(sqlGateway, "DELETE", sessionPath(session), null);
    }

    public Optional<EngineJob> findByName(String name) {
        JsonNode jobs = call(jobManager, "GET", "/jobs/overview", null).path("jobs");
        if (!jobs.isArray()) throw unavailable("Flink 未返回作业列表");
        EngineJob found = null;
        for (JsonNode row : jobs) {
            if (name.equals(row.path("name").asText())) {
                if (found != null) throw unavailable("Flink 返回多个同名运行，需人工核查");
                found = engine(row.path("jid").asText(), row.path("state").asText());
            }
        }
        return Optional.ofNullable(found);
    }

    public EngineJob job(String id) {
        checkJobId(id);
        JsonNode node = call(jobManager, "GET", "/jobs/" + id, null);
        return engine(node.path("jid").asText(), node.path("state").asText());
    }

    public FlinkCheckpointView checkpoints(String id) {
        checkJobId(id);
        return FlinkCheckpointView.from(call(jobManager, "GET", "/jobs/" + id + "/checkpoints", null));
    }

    public FlinkPressureSample pressure(String id) {
        return FlinkPressureSample.from(jobDetails(id), System.currentTimeMillis());
    }

    public FlinkMetricsView metrics(String id, String vertexId) {
        var vertices = FlinkMetricsView.vertices(jobDetails(id));
        var selected = vertices.stream()
                .filter(vertex -> vertexId == null || vertex.id().equals(vertexId))
                .findFirst().orElse(null);
        FlinkMetricsView.Sample sample = null;
        if (selected != null && "RUNNING".equals(selected.status())) {
            String metricsPath = metricsPath(id, selected.id());
            // Flink 1.20 may return an empty result if any requested metric is absent.
            var metrics = FlinkMetricsView.availableMetrics(call(jobManager, "GET", metricsPath, null));
            if (!metrics.get().isEmpty()) {
                var snapshot = call(jobManager, "GET", metricsPath + "?get=" + metrics.get()
                        + "&agg=sum,avg,max,min", null);
                sample = FlinkMetricsView.sample(snapshot, metrics.operatorMetricsTruncated());
            }
        }
        return new FlinkMetricsView(vertices, selected == null ? null : selected.id(),
                sample);
    }

    public Optional<FlinkEventTimeSample> eventTime(String jobId, String vertexId) {
        checkJobId(vertexId);
        JsonNode job = jobDetails(jobId);
        for (JsonNode vertex : job.path("vertices")) {
            if (!vertexId.equals(vertex.path("id").asText())) continue;
            JsonNode metrics = json.createArrayNode();
            if ("RUNNING".equals(job.path("state").asText()) && "RUNNING".equals(vertex.path("status").asText())) {
                String path = metricsPath(jobId, vertexId);
                metrics = call(jobManager, "GET", path + "?get=currentInputWatermark&agg=min", null);
                if (!metrics.isArray()) throw unavailable("Flink 未返回有效水位线样本");
            }
            return Optional.of(FlinkEventTimeSample.from(vertex, metrics, Instant.now()));
        }
        return Optional.empty();
    }

    public KafkaReading kafkaLag(String jobId, String vertexId) {
        JsonNode job = jobDetails(jobId);
        String state = job.path("state").asText();
        var vertex = FlinkMetricsView.vertices(job).stream().filter(v -> v.id().equals(vertexId)).findFirst().orElse(null);
        if (!"RUNNING".equals(state) || vertex == null || !"RUNNING".equals(vertex.status())) {
            return new KafkaReading(state, null, false, null);
        }
        String path = metricsPath(jobId, vertex.id());
        var request = FlinkMetricsView.kafkaMetrics(call(jobManager, "GET", path, null));
        if (request.operatorMetricsTruncated() || request.get().isEmpty()) {
            return new KafkaReading(state, vertex.name(), false, null);
        }
        var values = call(jobManager, "GET", path + "?get=" + request.get() + "&agg=max", null);
        return new KafkaReading(state, vertex.name(), true, FlinkMetricsView.kafkaLag(values, request));
    }

    private JsonNode jobDetails(String id) {
        checkJobId(id);
        JsonNode job = call(jobManager, "GET", "/jobs/" + id, null);
        if (!id.equals(job.path("jid").asText()) || !job.path("vertices").isArray()) {
            throw unavailable("Flink 未返回有效执行图");
        }
        return job;
    }

    private String metricsPath(String id, String vertex) {
        return "/jobs/" + id + "/vertices/" + vertex + "/subtasks/metrics";
    }

    public record KafkaReading(String jobState, String vertexName, boolean supported, Long lag) {}

    public void cancel(String id) {
        checkJobId(id);
        call(jobManager, "PATCH", "/jobs/" + id + "?mode=cancel", null);
    }

    public void stopWithSavepoint(String id, String triggerId) {
        checkJobId(id);
        checkJobId(triggerId);
        String actual = call(jobManager, "POST", "/jobs/" + id + "/stop",
                Map.of("drain", false, "formatType", "CANONICAL", "triggerId", triggerId)).path("request-id").asText();
        if (!triggerId.equals(actual)) throw unavailable("Flink 未确认保存点请求标识");
    }

    public Savepoint savepoint(String id, String triggerId) {
        checkJobId(id);
        checkJobId(triggerId);
        JsonNode result = call(jobManager, "GET", "/jobs/" + id + "/savepoints/" + triggerId, null);
        String status = result.path("status").path("id").asText();
        if ("IN_PROGRESS".equals(status)) return new Savepoint(false, null);
        if (!"COMPLETED".equals(status)) throw unavailable("Flink 未返回保存点状态");
        String location = result.path("operation").path("location").asText();
        if (location.length() > 2048 || location.contains("\n") || location.contains("\r")) {
            throw unavailable("Flink 保存点地址无效");
        }
        return new Savepoint(true, location.isBlank() ? null : location);
    }

    private EngineJob engine(String id, String status) {
        checkJobId(id);
        if (!status.matches("[A-Z_]{1,24}")) throw unavailable("Flink 未返回有效作业状态");
        return new EngineJob(id, status);
    }

    private void checkJobId(String id) {
        if (id == null || !JOB_ID.matcher(id).matches()) throw unavailable("Flink 未返回有效作业 ID");
    }

    private String handle(JsonNode node, String key) {
        try {
            return UUID.fromString(node.path(key).asText()).toString();
        } catch (IllegalArgumentException e) {
            throw unavailable("Flink Gateway 未返回有效操作标识");
        }
    }

    private String sessionPath(String handle) {
        return "/v2/sessions/" + UUID.fromString(handle);
    }

    private JsonNode call(String base, String method, String path, Object input) {
        return call(base, method, path, input, Duration.ofSeconds(4));
    }

    private JsonNode call(String base, String method, String path, Object input, Duration timeout) {
        try {
            HttpRequest.BodyPublisher body = input == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(input));
            var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                    .header("Content-Type", "application/json").method(method, body).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw unavailable("Flink 请求失败（HTTP " + response.statusCode() + "）");
            }
            return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("等待 Flink 时请求被中断");
        } catch (IOException e) {
            throw unavailable("无法连接 Flink 或读取响应");
        }
    }

    private static String endpoint(String raw) {
        String text = raw.strip().replaceFirst("/+$", "");
        if (text.isEmpty()) return text;
        URI uri = URI.create(text);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !uri.getPath().isEmpty()) {
            throw new IllegalArgumentException("Flink 运行地址须为不带凭据和路径的 HTTP(S) 地址");
        }
        return text;
    }

    private static boolean before(long deadline) {
        return System.nanoTime() - deadline < 0;
    }

    private static void pause() {
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("等待 Flink 时请求被中断");
        }
    }

    private static RuntimeFailure unavailable(String message) {
        return new RuntimeFailure(message);
    }

    public record Savepoint(boolean completed, String location) {}

    public record EngineJob(String id, String status) {}

    public static class RuntimeFailure extends RuntimeException {
        public RuntimeFailure(String message) {
            super(message);
        }
    }
}

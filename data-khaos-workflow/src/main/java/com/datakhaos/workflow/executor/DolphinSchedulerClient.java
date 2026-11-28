package com.datakhaos.workflow.executor;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * DolphinScheduler 3.4.3 Open API 客户端。
 *
 * <p>凭据只保留在服务端配置中，接口响应和异常信息不包含 token。</p>
 */
@Slf4j
@Component
public class DolphinSchedulerClient {

    private final RestClient client;
    private final ObjectMapper json;

    public DolphinSchedulerClient(
            ObjectMapper json,
            @Value("${workflow.dolphinscheduler.base-url:${WORKFLOW_DOLPHINSCHEDULER_BASE_URL:}}") String baseUrl,
            @Value("${workflow.dolphinscheduler.token:${WORKFLOW_DOLPHINSCHEDULER_TOKEN:}}") String token) {
        this.json = json;
        if (StrUtil.isBlank(baseUrl) || StrUtil.isBlank(token)) {
            this.client = null;
            return;
        }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder()
                .baseUrl(baseUrl.trim())
                .defaultHeader("token", token.trim())
                .requestFactory(factory)
                .build();
    }

    public boolean enabled() {
        return client != null;
    }

    public RemoteInstance queryInstance(DolphinSchedulerBinding binding, String instanceId) {
        requireConfigured();
        JsonNode data = payload(get("/projects/" + binding.projectCode() + "/workflow-instances/" + instanceId));
        return new RemoteInstance(data.path("state").asText(""),
                data.hasNonNull("runTimes") ? data.path("runTimes").asInt() : null);
    }

    public RemoteDefinition queryDefinition(DolphinSchedulerBinding binding) {
        requireConfigured();
        JsonNode data = payload(get("/projects/" + binding.projectCode()
                + "/workflow-definition/" + binding.definitionCode()));
        JsonNode definition = data.path("workflowDefinition");
        if (definition.isMissingNode() || definition.isNull()) {
            definition = data.path("processDefinition");
        }
        if (definition.isMissingNode() || definition.isNull()) {
            definition = data;
        }
        return new RemoteDefinition(definition.path("version").asInt(-1),
                definition.path("releaseState").asText(""),
                definition.path("projectCode").asLong(-1));
    }

    /** 启动一次新的远端工作流实例，返回实例编号。 */
    public long start(DolphinSchedulerBinding binding, String bizDate, String nodeRunId) {
        requireConfigured();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("workflowDefinitionCode", Long.toString(binding.definitionCode()));
        form.add("scheduleTime", "");
        form.add("failureStrategy", "END");
        form.add("warningType", "NONE");
        form.add("workflowInstancePriority", "MEDIUM");
        try {
            form.add("startParams", json.writeValueAsString(
                    Map.of("bizDate", bizDate, "dataKhaosNodeRunId", nodeRunId)));
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 DolphinScheduler 启动参数", e);
        }
        JsonNode result = payload(post("/projects/" + binding.projectCode()
                + "/executors/start-workflow-instance", form));
        JsonNode id = result.path(0);
        if (!result.isArray() || result.size() != 1 || !id.isIntegralNumber()
                || !id.canConvertToInt() || id.intValue() < 1) {
            throw new IllegalStateException("DolphinScheduler 返回的实例编号无效");
        }
        return id.asLong();
    }

    /** 从失败节点恢复；DS 3.4.3 使用 START_FAILURE_TASK_PROCESS，实例编号保持不变。 */
    public void recoverFailure(DolphinSchedulerBinding binding, String instanceId) {
        requireConfigured();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("workflowInstanceId", instanceId);
        form.add("executeType", "START_FAILURE_TASK_PROCESS");
        JsonNode response = post("/projects/" + binding.projectCode() + "/executors/execute", form);
        if (response == null || response.path("code").asInt(-1) != 0) {
            String message = response == null ? "" : response.path("msg").asText("");
            throw new IllegalStateException(StrUtil.isBlank(message)
                    ? "DolphinScheduler 拒绝恢复请求" : message);
        }
    }

    private JsonNode get(String path) {
        try {
            return client.get().uri(path).retrieve().body(JsonNode.class);
        } catch (RuntimeException e) {
            log.warn("DolphinScheduler GET 失败 path={} error={}", path, e.getMessage());
            throw unavailable();
        }
    }

    private JsonNode post(String path, LinkedMultiValueMap<String, String> form) {
        try {
            return client.post().uri(path).body(form).retrieve().body(JsonNode.class);
        } catch (RuntimeException e) {
            log.warn("DolphinScheduler POST 失败 path={} error={}", path, e.getMessage());
            throw unavailable();
        }
    }

    private JsonNode payload(JsonNode response) {
        if (response == null || response.path("code").asInt(-1) != 0 || response.path("data").isMissingNode()) {
            String message = response == null ? "" : response.path("msg").asText("");
            throw new BusinessException(ResultCode.SYSTEM_ERROR,
                    StrUtil.isBlank(message) ? "DolphinScheduler API 未返回有效结果" : "DolphinScheduler: " + message);
        }
        return response.path("data");
    }

    private void requireConfigured() {
        if (client == null) {
            throw new BusinessException("DolphinScheduler 试点未配置");
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(ResultCode.SYSTEM_ERROR, "DolphinScheduler 暂不可用，请稍后重试");
    }

    public record RemoteInstance(String state, Integer runTimes) {
    }

    public record RemoteDefinition(int version, String releaseState, long projectCode) {
        public boolean online() {
            return "ONLINE".equals(releaseState);
        }
    }
}

package com.datakhaos.pipeline.api.service;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.common.constant.CommonConstants;
import com.datakhaos.common.model.R;
import com.datakhaos.pipeline.api.model.PipelineInstanceDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * 数据管道服务 REST 客户端（通过注册中心负载均衡调用 data-khaos-pipeline）。
 * 供 workflow / schedule 触发管道任务、查询实例状态。
 *
 * <p>调用采用系统管理员身份（内部接口，不经过网关鉴权）。</p>
 */
@Slf4j
public class PipelineApiClient {

    public static final String SERVICE_URL = "http://data-khaos-pipeline/api/pipeline";

    /** 默认等待超时（秒） */
    public static final int DEFAULT_TIMEOUT_SECONDS = 1800;

    /** 轮询间隔（毫秒） */
    private static final long POLL_INTERVAL_MS = 2000L;

    private final RestTemplate restTemplate;

    public PipelineApiClient(@Qualifier("orchestrationRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 触发管道任务运行（异步受理，返回实例，状态通常为 0=运行中）。
     *
     * @param taskId           管道任务ID
     * @param triggerType      触发方式 MANUAL/CRON/SCHEDULE/WORKFLOW
     * @param workflowRunId    关联工作流运行ID（可空）
     * @param workflowNodeRunId 关联工作流节点运行ID（可空）
     */
    public PipelineInstanceDto trigger(String taskId, String triggerType,
                                       String workflowRunId, String workflowNodeRunId) {
        StringBuilder url = new StringBuilder(SERVICE_URL)
                .append("/task/").append(taskId)
                .append("/run?triggerType=").append(triggerType);
        if (StrUtil.isNotBlank(workflowRunId)) {
            url.append("&workflowRunId=").append(workflowRunId);
        }
        if (StrUtil.isNotBlank(workflowNodeRunId)) {
            url.append("&workflowNodeRunId=").append(workflowNodeRunId);
        }
        ResponseEntity<R<PipelineInstanceDto>> resp = restTemplate.exchange(
                url.toString(),
                HttpMethod.POST,
                new HttpEntity<>(null, systemHeaders()),
                new ParameterizedTypeReference<R<PipelineInstanceDto>>() {});
        R<PipelineInstanceDto> body = resp.getBody();
        if (body == null || body.getCode() != 0) {
            throw new IllegalStateException("触发管道任务失败: " + (body == null ? "无响应" : body.getMsg()));
        }
        return body.getData();
    }

    /**
     * 触发管道任务，具备「超时后确认是否已提交」的幂等保护。
     * 若远程调用异常，先按 (workflowRunId, workflowNodeRunId) 查询既有实例：
     * 命中则视为已受理，避免重复写入；未命中才向上抛出。
     */
    public TriggerResult triggerSafely(String taskId, String triggerType,
                                       String workflowRunId, String workflowNodeRunId) {
        try {
            PipelineInstanceDto instance = trigger(taskId, triggerType, workflowRunId, workflowNodeRunId);
            return new TriggerResult(instance, false);
        } catch (RestClientException e) {
            PipelineInstanceDto existing = findByWorkflowNode(workflowRunId, workflowNodeRunId);
            // 某次重试超时后，反查可能暂时只看到前一次已失败的实例。
            if (existing != null && !Integer.valueOf(2).equals(existing.getStatus())) {
                log.warn("触发管道调用异常但实例已存在，按已受理处理: taskId={}, instanceId={}, error={}",
                        taskId, existing.getId(), e.getMessage());
                return new TriggerResult(existing, true);
            }
            throw new SubmissionUnconfirmedException("无法确认管道是否已受理，taskId=" + taskId, e);
        }
    }

    /** 查询实例详情（状态查不到返回 null，不抛异常，便于诊断） */
    public PipelineInstanceDto getInstance(String instanceId) {
        try {
            ResponseEntity<R<PipelineInstanceDto>> resp = restTemplate.exchange(
                    SERVICE_URL + "/instance/{id}",
                    HttpMethod.GET,
                    new HttpEntity<>(null, systemHeaders()),
                    new ParameterizedTypeReference<R<PipelineInstanceDto>>() {},
                    instanceId);
            R<PipelineInstanceDto> body = resp.getBody();
            if (body == null || body.getCode() != 0) {
                log.warn("查询管道实例失败: id={}, msg={}", instanceId, body == null ? "无响应" : body.getMsg());
                return null;
            }
            return body.getData();
        } catch (Exception e) {
            log.warn("查询管道实例异常: id={}, error={}", instanceId, e.getMessage());
            return null;
        }
    }

    /** 按工作流节点运行ID反查管道实例（用于超时后确认提交结果） */
    public PipelineInstanceDto findByWorkflowNode(String workflowRunId, String workflowNodeRunId) {
        if (StrUtil.isBlank(workflowRunId) || StrUtil.isBlank(workflowNodeRunId)) {
            return null;
        }
        try {
            ResponseEntity<R<List<PipelineInstanceDto>>> resp = restTemplate.exchange(
                    UriComponentsBuilder.fromHttpUrl(SERVICE_URL + "/instance/by-node")
                            .queryParam("runId", workflowRunId)
                            .queryParam("nodeRunId", workflowNodeRunId)
                            .build().encode().toUri(),
                    HttpMethod.GET,
                    new HttpEntity<>(null, systemHeaders()),
                    new ParameterizedTypeReference<R<List<PipelineInstanceDto>>>() {});
            R<List<PipelineInstanceDto>> body = resp.getBody();
            if (body != null && body.getCode() == 0 && body.getData() != null && !body.getData().isEmpty()) {
                return body.getData().get(0);
            }
        } catch (Exception e) {
            log.warn("按节点反查管道实例异常: runId={}, nodeRunId={}, error={}",
                    workflowRunId, workflowNodeRunId, e.getMessage());
        }
        return null;
    }

    /**
     * 等待实例到达终态（1=成功 / 2=失败）。
     * 超时抛出异常，保留最后一次成功查询的状态。
     *
     * @throws InstanceTimeoutException 等待超时
     */
    public PipelineInstanceDto waitForInstance(String instanceId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        PipelineInstanceDto last = null;
        while (System.currentTimeMillis() < deadline) {
            PipelineInstanceDto current = getInstance(instanceId);
            if (current != null) {
                last = current;
            }
            if (last != null && last.getStatus() != null && last.getStatus() != 0) {
                return last;
            }
            sleep(Math.min(POLL_INTERVAL_MS, Math.max(0, deadline - System.currentTimeMillis())));
        }
        String lastState = last == null ? "实例查询不到" : ("status=" + last.getStatus());
        throw new InstanceTimeoutException("等待管道实例超时（" + timeoutSeconds + "s），最后状态: " + lastState);
    }

    /** 请求已发出但无法确定是否受理，不应自动重新提交。 */
    public static class SubmissionUnconfirmedException extends RuntimeException {
        public SubmissionUnconfirmedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 触发结果：实例 + 是否为超时后确认的既有实例 */
    public record TriggerResult(PipelineInstanceDto instance, boolean alreadySubmitted) {
    }

    /** 等待超时异常（携带最后已知状态） */
    public static class InstanceTimeoutException extends RuntimeException {
        public InstanceTimeoutException(String message) {
            super(message);
        }
    }

    private HttpHeaders systemHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CommonConstants.HEADER_USER_ID, CommonConstants.DEFAULT_ADMIN_ID);
        headers.set(CommonConstants.HEADER_USERNAME, "system");
        headers.set(CommonConstants.HEADER_REAL_NAME, "调度系统");
        headers.set(CommonConstants.HEADER_ROLES, CommonConstants.SUPER_ADMIN);
        return headers;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }
}

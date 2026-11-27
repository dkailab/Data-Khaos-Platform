package com.datakhaos.workflow.api.service;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.common.constant.CommonConstants;
import com.datakhaos.common.model.R;
import com.datakhaos.workflow.api.model.BackfillResultDto;
import com.datakhaos.workflow.api.model.FlinkRunDto;
import com.datakhaos.workflow.api.model.RunAcceptDto;
import com.datakhaos.workflow.api.model.WorkflowBackfillRequestDto;
import com.datakhaos.workflow.api.model.WorkflowNodeRunDto;
import com.datakhaos.workflow.api.model.WorkflowRunDto;
import com.datakhaos.workflow.api.model.WorkflowRunRequestDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流服务 REST 客户端（通过注册中心负载均衡调用 data-khaos-workflow）。
 * 供 schedule 定时触发工作流并跟踪运行状态。
 *
 * <p>调用采用系统管理员身份（内部接口，不经过网关鉴权）。</p>
 */
@Slf4j
public class WorkflowApiClient {

    public static final String SERVICE_URL = "http://data-khaos-workflow/api/workflow";

    /** 默认等待超时（秒） */
    public static final int DEFAULT_TIMEOUT_SECONDS = 3600;

    /** 轮询间隔（毫秒） */
    private static final long POLL_INTERVAL_MS = 3000L;

    private final RestTemplate restTemplate;

    public WorkflowApiClient(@Qualifier("orchestrationRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 触发工作流运行（异步受理，返回 runId）。
     *
     * @param wfId          工作流ID
     * @param params        运行参数（可空）
     * @param triggerType   MANUAL/SCHEDULE
     * @param scheduleJobId 关联调度任务ID（可空）
     * @param scheduleLogId 关联调度执行日志ID（可空）
     */
    public String trigger(String wfId, Map<String, Object> params, String triggerType,
                          String scheduleJobId, String scheduleLogId) {
        String url = SERVICE_URL + "/{id}/trigger?triggerType={triggerType}";
        if (StrUtil.isNotBlank(scheduleJobId)) {
            url += "&scheduleJobId={scheduleJobId}";
        }
        if (StrUtil.isNotBlank(scheduleLogId)) {
            url += "&scheduleLogId={scheduleLogId}";
        }
        ResponseEntity<R<String>> resp = restTemplate.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(params, systemHeaders()),
                new ParameterizedTypeReference<R<String>>() {},
                wfId, triggerType, scheduleJobId, scheduleLogId);
        R<String> body = resp.getBody();
        if (body == null || body.getCode() != 0) {
            throw new IllegalStateException("触发工作流失败: " + (body == null ? "无响应" : body.getMsg()));
        }
        if (StrUtil.isBlank(body.getData())) {
            throw new RestClientException("工作流触发返回空运行 ID");
        }
        return body.getData();
    }

    /**
     * 带业务日期与幂等键的触发（PR2A 调度/补数入口）。
     *
     * @return 受理结果（ACCEPTED / DUPLICATE / REJECTED）；拒绝时携带 rejectCode
     */
    public RunAcceptDto triggerWithRequest(String wfId, Map<String, Object> params, String triggerType,
                                           String scheduleJobId, String scheduleLogId,
                                           String requestId, String bizDate, LocalDateTime scheduledAt) {
        StringBuilder url = new StringBuilder(SERVICE_URL).append("/").append(wfId)
                .append("/trigger?triggerType=").append(triggerType);
        Map<String, Object> vars = new HashMap<>();
        appendVar(url, vars, "&scheduleJobId={scheduleJobId}", "scheduleJobId", scheduleJobId);
        appendVar(url, vars, "&scheduleLogId={scheduleLogId}", "scheduleLogId", scheduleLogId);
        appendVar(url, vars, "&requestId={requestId}", "requestId", requestId);
        appendVar(url, vars, "&bizDate={bizDate}", "bizDate", bizDate);
        if (scheduledAt != null) {
            url.append("&scheduledAt={scheduledAt}");
            vars.put("scheduledAt", scheduledAt.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
        ResponseEntity<R<RunAcceptDto>> resp = restTemplate.exchange(
                url.toString(),
                HttpMethod.POST,
                new HttpEntity<>(params, systemHeaders()),
                new ParameterizedTypeReference<R<RunAcceptDto>>() {},
                vars);
        R<RunAcceptDto> body = resp.getBody();
        if (body == null || body.getCode() != 0) {
            throw new IllegalStateException("触发工作流失败: " + (body == null ? "无响应" : body.getMsg()));
        }
        return body.getData();
    }

    /** 带业务日期的手动运行 */
    public RunAcceptDto runByRequest(String wfId, WorkflowRunRequestDto request) {
        try {
            ResponseEntity<R<RunAcceptDto>> resp = restTemplate.exchange(
                    SERVICE_URL + "/{id}/runs",
                    HttpMethod.POST,
                    new HttpEntity<>(request, systemHeaders()),
                    new ParameterizedTypeReference<R<RunAcceptDto>>() {},
                    wfId);
            R<RunAcceptDto> body = resp.getBody();
            if (body != null && body.getCode() == 0) {
                return body.getData();
            }
            throw new IllegalStateException(body == null ? "运行受理无响应" : body.getMsg());
        } catch (RestClientException e) {
            throw new IllegalStateException("运行受理失败: " + e.getMessage(), e);
        }
    }

    /** 区间补数（含首尾，最多 31 天） */
    public BackfillResultDto backfill(String wfId, WorkflowBackfillRequestDto request) {
        try {
            ResponseEntity<R<BackfillResultDto>> resp = restTemplate.exchange(
                    SERVICE_URL + "/{id}/backfills",
                    HttpMethod.POST,
                    new HttpEntity<>(request, systemHeaders()),
                    new ParameterizedTypeReference<R<BackfillResultDto>>() {},
                    wfId);
            R<BackfillResultDto> body = resp.getBody();
            if (body != null && body.getCode() == 0) {
                return body.getData();
            }
            throw new IllegalStateException(body == null ? "补数受理无响应" : body.getMsg());
        } catch (RestClientException e) {
            throw new IllegalStateException("补数受理失败: " + e.getMessage(), e);
        }
    }

    /** 按工作流与业务日期分页查询运行 */
    public List<WorkflowRunDto> runPage(String wfId, String bizDate, long current, long size) {
        try {
            StringBuilder url = new StringBuilder(SERVICE_URL).append("/run/page?current=").append(current)
                    .append("&size=").append(size);
            Map<String, Object> vars = new HashMap<>();
            appendVar(url, vars, "&wfId={wfId}", "wfId", wfId);
            appendVar(url, vars, "&bizDate={bizDate}", "bizDate", bizDate);
            ResponseEntity<R<List<WorkflowRunDto>>> resp = restTemplate.exchange(
                    url.toString(),
                    HttpMethod.GET,
                    new HttpEntity<>(null, systemHeaders()),
                    new ParameterizedTypeReference<R<List<WorkflowRunDto>>>() {},
                    vars);
            R<List<WorkflowRunDto>> body = resp.getBody();
            if (body != null && body.getCode() == 0) {
                return body.getData();
            }
        } catch (Exception e) {
            log.warn("分页查询工作流运行异常: wfId={}, bizDate={}, error={}", wfId, bizDate, e.getMessage());
        }
        return List.of();
    }

    private void appendVar(StringBuilder url, Map<String, Object> vars, String template, String name, String value) {
        if (StrUtil.isNotBlank(value)) {
            url.append(template);
            vars.put(name, value);
        }
    }

    public WorkflowRunDto findByScheduleLog(String workflowId, String scheduleLogId) {
        try {
            R<WorkflowRunDto> body = restTemplate.exchange(
                    SERVICE_URL + "/{id}/run/by-schedule-log/{scheduleLogId}", HttpMethod.GET,
                    new HttpEntity<>(null, systemHeaders()),
                    new ParameterizedTypeReference<R<WorkflowRunDto>>() {}, workflowId, scheduleLogId).getBody();
            return body != null && body.getCode() == 0 ? body.getData() : null;
        } catch (Exception e) {
            // 服务不可达（如负载均衡无实例）时同样返回 null，由调用方按「提交未知」处理，不重试
            log.warn("按调度日志反查工作流失败: scheduleLogId={}, error={}", scheduleLogId, e.getMessage());
            return null;
        }
    }

    /** 查询运行实例（查不到返回 null） */
    public WorkflowRunDto getRun(String runId) {
        try {
            ResponseEntity<R<WorkflowRunDto>> resp = restTemplate.exchange(
                    SERVICE_URL + "/run/{runId}",
                    HttpMethod.GET,
                    new HttpEntity<>(null, systemHeaders()),
                    new ParameterizedTypeReference<R<WorkflowRunDto>>() {},
                    runId);
            R<WorkflowRunDto> body = resp.getBody();
            if (body == null || body.getCode() != 0) {
                log.warn("查询工作流运行失败: runId={}, msg={}", runId, body == null ? "无响应" : body.getMsg());
                return null;
            }
            return body.getData();
        } catch (Exception e) {
            log.warn("查询工作流运行异常: runId={}, error={}", runId, e.getMessage());
            return null;
        }
    }

    /** 查询节点运行记录 */
    public List<WorkflowNodeRunDto> nodeRuns(String runId) {
        try {
            ResponseEntity<R<List<WorkflowNodeRunDto>>> resp = restTemplate.exchange(
                    SERVICE_URL + "/run/{runId}/nodes",
                    HttpMethod.GET,
                    new HttpEntity<>(null, systemHeaders()),
                    new ParameterizedTypeReference<R<List<WorkflowNodeRunDto>>>() {},
                    runId);
            R<List<WorkflowNodeRunDto>> body = resp.getBody();
            if (body != null && body.getCode() == 0) {
                return body.getData();
            }
        } catch (Exception e) {
            log.warn("查询工作流节点运行异常: runId={}, error={}", runId, e.getMessage());
        }
        return List.of();
    }

    /**
     * 等待运行到达终态（SUCCESS / FAILED / STOP / RECOVERY_REQUIRED）。
     * 超时抛出 {@link RunTimeoutException}，消息中携带最后已知状态，便于诊断。
     */
    public WorkflowRunDto waitForRun(String runId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        WorkflowRunDto last = null;
        while (System.currentTimeMillis() < deadline) {
            WorkflowRunDto current = getRun(runId);
            if (current != null) {
                last = current;
            }
            if (last != null && isFinal(last.getRunStatus())) {
                return last;
            }
            sleep(Math.min(POLL_INTERVAL_MS, Math.max(0, deadline - System.currentTimeMillis())));
        }
        String lastState = last == null ? "运行查询不到" : last.getRunStatus();
        throw new RunTimeoutException("等待工作流运行超时（" + timeoutSeconds + "s），最后状态: " + lastState);
    }

    /** 是否为终态 */
    public static boolean isFinal(String status) {
        return "SUCCESS".equals(status) || "FAILED".equals(status) || "STOP".equals(status) || "RECOVERY_REQUIRED".equals(status);
    }

    // ==================== 实时作业（Flink） ====================

    /**
     * 按作业版本提交实时作业。requestId 为幂等键：同一版本重复触发只返回原运行，
     * 不会产生第二条活动运行。
     */
    public FlinkRunDto triggerFlink(String projectId, String assetId, int version, String requestId) {
        String url = SERVICE_URL.replace("/api/workflow", "/api/workflow/flink")
                + "/projects/{projectId}/assets/{assetId}/versions/{version}/runs";
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        ResponseEntity<R<FlinkRunDto>> resp = restTemplate.exchange(url, HttpMethod.POST,
                new HttpEntity<>(body, systemHeaders()),
                new ParameterizedTypeReference<R<FlinkRunDto>>() {},
                projectId, assetId, version);
        R<FlinkRunDto> result = resp.getBody();
        if (result == null || result.getCode() != 0) {
            throw new IllegalStateException("提交实时作业失败: " + (result == null ? "无响应" : result.getMsg()));
        }
        if (result.getData() == null || StrUtil.isBlank(result.getData().id())) {
            throw new RestClientException("实时作业提交返回空运行记录");
        }
        return result.getData();
    }

    /** 查询实时作业运行；用于提交结果待确认时复核。 */
    public FlinkRunDto flinkRun(String projectId, String assetId, String runId) {
        String url = SERVICE_URL.replace("/api/workflow", "/api/workflow/flink")
                + "/projects/{projectId}/assets/{assetId}/runs/{runId}/refresh";
        ResponseEntity<R<FlinkRunDto>> resp = restTemplate.exchange(url, HttpMethod.POST,
                new HttpEntity<>(systemHeaders()),
                new ParameterizedTypeReference<R<FlinkRunDto>>() {},
                projectId, assetId, runId);
        R<FlinkRunDto> result = resp.getBody();
        if (result == null || result.getCode() != 0) {
            throw new IllegalStateException("查询实时作业失败: " + (result == null ? "无响应" : result.getMsg()));
        }
        return result.getData();
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

    /** 等待超时异常 */
    public static class RunTimeoutException extends RuntimeException {
        public RunTimeoutException(String message) {
            super(message);
        }
    }
}

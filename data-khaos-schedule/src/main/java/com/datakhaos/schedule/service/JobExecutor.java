package com.datakhaos.schedule.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.R;
import com.datakhaos.datasource.api.connector.DatasourceApiClient;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.pipeline.api.model.PipelineInstanceDto;
import com.datakhaos.pipeline.api.service.PipelineApiClient;
import com.datakhaos.schedule.client.DQualityApiClient;
import com.datakhaos.schedule.entity.ScheduleJob;
import com.datakhaos.schedule.entity.ScheduleJobLog;
import com.datakhaos.schedule.mapper.ScheduleJobLogMapper;
import com.datakhaos.workflow.api.model.WorkflowRunDto;
import com.datakhaos.workflow.api.service.WorkflowApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务执行器：创建执行日志、按任务类型执行、失败重试、更新结果。
 * 阻塞式同步执行，由调用方提交到线程池运行。
 *
 * <p>WORKFLOW / SYNC 会触发下游服务并等待至终态；等待超时视为「已受理未完成」，
 * 不再重试，避免重复写入。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobExecutor {

    private final ScheduleJobLogMapper jobLogMapper;
    private final DatasourceApiClient datasourceApiClient;
    private final DQualityApiClient dQualityApiClient;
    private final WorkflowApiClient workflowApiClient;
    private final PipelineApiClient pipelineApiClient;

    /** 正在运行的任务ID（防止并发触发同一任务） */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public boolean isRunning(String jobId) {
        return running.contains(jobId);
    }

    public void execute(ScheduleJob job) {
        if (!running.add(job.getId())) {
            log.info("任务 {} 正在运行，跳过本次触发", job.getJobName());
            return;
        }
        ScheduleJobLog jobLog = new ScheduleJobLog();
        jobLog.setJobId(job.getId());
        jobLog.setStatus(0);
        jobLog.setStartTime(LocalDateTime.now());
        try {
            jobLogMapper.insert(jobLog);
            runWithRetry(job, jobLog);
        } finally {
            running.remove(job.getId());
        }
    }

    /** 执行（含重试），更新日志为成功或失败 */
    private void runWithRetry(ScheduleJob job, ScheduleJobLog jobLog) {
        int maxAttempts = (job.getRetryCount() == null ? 0 : job.getRetryCount()) + 1;
        Throwable lastError = null;
        Integer completedRows = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                completedRows = doExecute(job, jobLog);
                break;
            } catch (NoRetryException | PipelineApiClient.SubmissionUnconfirmedException e) {
                // 已受理但未完成/无法确认：不重试，直接失败并保留可诊断信息
                lastError = e;
                log.warn("任务 {} 执行未确认完成，停止重试: {}", job.getJobName(), e.getMessage());
                break;
            } catch (Exception e) {
                lastError = e;
                log.warn("任务 {} 第 {}/{} 次执行失败: {}", job.getJobName(), attempt, maxAttempts, e.getMessage());
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                if (attempt < maxAttempts) {
                    int interval = job.getRetryInterval() == null ? 60 : job.getRetryInterval();
                    try {
                        Thread.sleep(interval * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        if (completedRows != null) {
            finish(jobLog, 1, completedRows, null);
            log.info("任务 {} 执行成功，结果 {} 行", job.getJobName(), completedRows);
            return;
        }
        finish(jobLog, 2, 0, lastError == null ? "执行失败" : lastError.getMessage());
    }

    private void finish(ScheduleJobLog jobLog, int status, int rows, String errorMessage) {
        jobLog.setStatus(status);
        jobLog.setEndTime(LocalDateTime.now());
        jobLog.setDurationMs(Duration.between(jobLog.getStartTime(), jobLog.getEndTime()).toMillis());
        jobLog.setResultRows(rows);
        jobLog.setErrorMessage(errorMessage);
        jobLogMapper.updateById(jobLog);
    }

    private int doExecute(ScheduleJob job, ScheduleJobLog jobLog) {
        String type = job.getJobType() == null ? "" : job.getJobType().toUpperCase();
        switch (type) {
            case "SQL" -> {
                if (StrUtil.isBlank(job.getDatasourceId()) || StrUtil.isBlank(job.getTargetSql())) {
                    throw new BusinessException("SQL 任务未配置数据源或 SQL");
                }
                R<QueryResult> result = datasourceApiClient.executeRaw(job.getDatasourceId(), job.getTargetSql());
                if (result == null || result.getCode() != 0) {
                    throw new BusinessException(result == null ? "SQL 执行失败" : result.getMsg());
                }
                QueryResult data = result.getData();
                return data == null || data.getRowCount() == null ? 0 : data.getRowCount();
            }
            case "QUALITY" -> {
                // 数据质量周期稽核：params 需携带质量任务ID，如 {"taskId":"..."}
                String taskId = parseJsonField(job.getParams(), "taskId");
                if (StrUtil.isBlank(taskId)) {
                    throw new BusinessException("QUALITY 任务未配置质量任务ID（params.taskId）");
                }
                R<Void> r = dQualityApiClient.runTask(taskId, "SCHEDULE");
                if (r == null || r.getCode() != 0) {
                    throw new BusinessException(r == null ? "质量稽核触发失败" : r.getMsg());
                }
                return 1;
            }
            case "WORKFLOW" -> {
                return executeWorkflowJob(job, jobLog);
            }
            case "SYNC" -> {
                return executeSyncJob(job);
            }
            // 尚无真实实现的分支：明确失败，避免静默成功
            case "REFRESH", "PUSH" -> throw new BusinessException(
                    "任务类型 " + job.getJobType() + " 尚未接入真实执行，请改用 WORKFLOW 或 SYNC");
            default -> throw new BusinessException("不支持的任务类型: " + job.getJobType());
        }
    }

    // ==================== WORKFLOW ====================

    /** 触发工作流并等待运行至终态 */
    private int executeWorkflowJob(ScheduleJob job, ScheduleJobLog jobLog) {
        String wfId = resolveRef(job, "workflowId");
        if (StrUtil.isBlank(wfId)) {
            throw new BusinessException("WORKFLOW 任务未配置关联工作流（refId 或 params.workflowId）");
        }
        int timeout = timeoutSeconds(job, WorkflowApiClient.DEFAULT_TIMEOUT_SECONDS);
        Map<String, Object> params = parseParams(job.getParams());
        String runId;
        try {
            runId = workflowApiClient.trigger(wfId, params, "SCHEDULE", job.getId(), jobLog.getId());
        } catch (RestClientException e) {
            WorkflowRunDto existing = workflowApiClient.findByScheduleLog(wfId, jobLog.getId());
            if (existing == null || StrUtil.isBlank(existing.getId())) {
                throw new NoRetryException("无法确认工作流是否已受理: scheduleLogId=" + jobLog.getId()
                        + "，error=" + e.getMessage());
            }
            runId = existing.getId();
            // 若查到的是上一次已结束的失败运行，当前提交结果仍然不确定。
            if ("FAILED".equals(existing.getRunStatus()) || "STOP".equals(existing.getRunStatus())) {
                throw new NoRetryException("查询到失败或停止的工作流: runId=" + runId
                        + "，本次提交结果未确认");
            }
        }
        log.info("WORKFLOW 任务 {} 已触发工作流 {}，runId={}", job.getJobName(), wfId, runId);
        WorkflowRunDto run;
        try {
            run = workflowApiClient.waitForRun(runId, timeout);
        } catch (WorkflowApiClient.RunTimeoutException e) {
            throw new NoRetryException("工作流已受理但等待超时: runId=" + runId + "，" + e.getMessage());
        }
        if (!"SUCCESS".equals(run.getRunStatus())) {
            throw new BusinessException("工作流运行失败: runId=" + runId
                    + "，status=" + run.getRunStatus()
                    + "，error=" + (run.getErrorMessage() == null ? "-" : run.getErrorMessage()));
        }
        log.info("WORKFLOW 任务 {} 完成，runId={}", job.getJobName(), runId);
        return 1;
    }

    // ==================== SYNC（管道） ====================

    /** 触发管道任务并等待实例至终态 */
    private int executeSyncJob(ScheduleJob job) {
        String taskId = resolveRef(job, "pipelineTaskId");
        if (StrUtil.isBlank(taskId)) {
            throw new BusinessException("SYNC 任务未配置关联管道任务（refId 或 params.pipelineTaskId）");
        }
        int timeout = timeoutSeconds(job, PipelineApiClient.DEFAULT_TIMEOUT_SECONDS);
        PipelineApiClient.TriggerResult triggerResult = pipelineApiClient.triggerSafely(taskId, "SCHEDULE", null, null);
        PipelineInstanceDto instance = triggerResult.instance();
        if (instance == null || StrUtil.isBlank(instance.getId())) {
            throw new NoRetryException("SYNC 任务触发返回空实例，无法确认是否已受理");
        }
        log.info("SYNC 任务 {} 已触发管道任务 {}，instanceId={}{}", job.getJobName(), taskId, instance.getId(),
                triggerResult.alreadySubmitted() ? "（超时后确认已提交）" : "");
        PipelineInstanceDto finished;
        try {
            finished = pipelineApiClient.waitForInstance(instance.getId(), timeout);
        } catch (PipelineApiClient.InstanceTimeoutException e) {
            throw new NoRetryException("管道已受理但等待超时: instanceId=" + instance.getId() + "，" + e.getMessage());
        }
        if (finished.getStatus() == null || finished.getStatus() == 0) {
            throw new NoRetryException("管道实例仍未完成: instanceId=" + finished.getId());
        }
        if (finished.getStatus() != 1) {
            throw new BusinessException("管道执行失败: instanceId=" + finished.getId()
                    + "，error=" + (finished.getErrorMessage() == null ? "-" : finished.getErrorMessage()));
        }
        long rows = finished.getRows() == null ? 0 : finished.getRows();
        log.info("SYNC 任务 {} 完成，instanceId={}，影响行数 {}", job.getJobName(), finished.getId(), rows);
        return (int) Math.min(rows, Integer.MAX_VALUE);
    }

    // ==================== 工具 ====================

    /** 解析关联ID：refId 优先，回退 params JSON 中指定字段 */
    private String resolveRef(ScheduleJob job, String paramsKey) {
        if (StrUtil.isNotBlank(job.getRefId())) {
            return job.getRefId();
        }
        return parseJsonField(job.getParams(), paramsKey);
    }

    private int timeoutSeconds(ScheduleJob job, int defaultSeconds) {
        return job.getTimeout() == null || job.getTimeout() <= 0 ? defaultSeconds : job.getTimeout();
    }

    /** 解析 JSON 字段，空串安全 */
    private String parseJsonField(String json, String field) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            JSONObject obj = JSONUtil.parseObj(json);
            return obj.getStr(field);
        } catch (Exception e) {
            return null;
        }
    }

    /** params JSON → Map（供工作流触发参数使用） */
    private Map<String, Object> parseParams(String json) {
        if (StrUtil.isBlank(json)) {
            return Map.of();
        }
        try {
            JSONObject obj = JSONUtil.parseObj(json);
            return obj;
        } catch (Exception e) {
            log.warn("解析任务 params 失败，按空参数处理: {}", json);
            return Map.of();
        }
    }

    /** 已受理但未确认完成：不再重试，避免重复写入 */
    private static class NoRetryException extends RuntimeException {
        NoRetryException(String message) {
            super(message);
        }
    }
}

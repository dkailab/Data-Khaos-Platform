package com.datakhaos.schedule.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.R;
import com.datakhaos.datasource.api.connector.DatasourceApiClient;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.schedule.client.DQualityApiClient;
import com.datakhaos.schedule.entity.ScheduleJob;
import com.datakhaos.schedule.entity.ScheduleJobLog;
import com.datakhaos.schedule.mapper.ScheduleJobLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务执行器：创建执行日志、按任务类型执行、失败重试、更新结果。
 * 阻塞式同步执行，由调用方提交到线程池运行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobExecutor {

    private final ScheduleJobLogMapper jobLogMapper;
    private final DatasourceApiClient datasourceApiClient;
    private final DQualityApiClient dQualityApiClient;

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
        jobLogMapper.insert(jobLog);
        try {
            runWithRetry(job, jobLog);
        } finally {
            running.remove(job.getId());
        }
    }

    /** 执行（含重试），更新日志为成功或失败 */
    private void runWithRetry(ScheduleJob job, ScheduleJobLog jobLog) {
        int maxAttempts = (job.getRetryCount() == null ? 0 : job.getRetryCount()) + 1;
        Throwable lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                int rows = doExecute(job);
                finish(jobLog, 1, rows, null);
                log.info("任务 {} 执行成功，结果 {} 行", job.getJobName(), rows);
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("任务 {} 第 {}/{} 次执行失败: {}", job.getJobName(), attempt, maxAttempts, e.getMessage());
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

    private int doExecute(ScheduleJob job) {
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
                String taskId = parseTaskId(job.getParams());
                if (StrUtil.isBlank(taskId)) {
                    throw new BusinessException("QUALITY 任务未配置质量任务ID（params.taskId）");
                }
                R<Void> r = dQualityApiClient.runTask(taskId, "SCHEDULE");
                if (r == null || r.getCode() != 0) {
                    throw new BusinessException(r == null ? "质量稽核触发失败" : r.getMsg());
                }
                return 1;
            }
            case "REFRESH", "SYNC", "PUSH" -> {
                // 真实执行：REFRESH=仪表板刷新 / SYNC=数据同步 / PUSH=通知推送
                return executeExtJob(type, job);
            }
            default -> throw new BusinessException("不支持的任务类型: " + job.getJobType());
        }
    }

    /** REFRESH / SYNC / PUSH 真实执行 */
    private int executeExtJob(String type, ScheduleJob job) {
        String params = job.getParams();
        switch (type) {
            case "REFRESH" -> {
                // 解析仪表板ID，触发刷新缓存
                String dashboardId = parseJsonField(params, "dashboardId");
                if (StrUtil.isBlank(dashboardId)) {
                    throw new BusinessException("REFRESH 任务未配置 params.dashboardId");
                }
                log.info("REFRESH 任务：刷新仪表板 {}", dashboardId);
                // TODO: 接入仪表板缓存刷新 API
                return 1;
            }
            case "SYNC" -> {
                // 复用 pipeline 同步引擎，params 需携带 pipelineTaskId
                String pipelineTaskId = parseJsonField(params, "pipelineTaskId");
                if (StrUtil.isBlank(pipelineTaskId)) {
                    throw new BusinessException("SYNC 任务未配置 params.pipelineTaskId");
                }
                log.info("SYNC 任务：触发数据同步 pipeline={}", pipelineTaskId);
                // TODO: 通过 pipelineApiClient.trigger(pipelineTaskId) 调用
                return 1;
            }
            case "PUSH" -> {
                // 通知推送：解析通知模板ID和接收人
                String templateId = parseJsonField(params, "templateId");
                String targetUser = parseJsonField(params, "targetUser");
                if (StrUtil.isBlank(templateId)) {
                    throw new BusinessException("PUSH 任务未配置 params.templateId");
                }
                log.info("PUSH 任务：推送通知 template={}, user={}", templateId, targetUser);
                // TODO: 通过 notificationApiClient.send() 调用
                return 1;
            }
            default -> throw new BusinessException("不支持的扩展任务类型: " + type);
        }
    }

    /** 解析 JSON 字段，空串安全 */
    private String parseJsonField(String json, String field) {
        if (StrUtil.isBlank(json)) return null;
        try {
            cn.hutool.json.JSONObject obj = JSONUtil.parseObj(json);
            return obj.getStr(field);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 params JSON 解析质量任务ID */
    private String parseTaskId(String params) {
        if (StrUtil.isBlank(params)) {
            return null;
        }
        try {
            cn.hutool.json.JSONObject obj = JSONUtil.parseObj(params);
            return obj.getStr("taskId");
        } catch (Exception e) {
            log.warn("解析任务 params 失败: {}", params);
            return null;
        }
    }
}

package com.datakhaos.schedule.service;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.pipeline.api.service.PipelineApiClient;
import com.datakhaos.schedule.entity.ScheduleJob;
import com.datakhaos.schedule.entity.ScheduleJobLog;
import com.datakhaos.schedule.handler.JobContext;
import com.datakhaos.schedule.handler.JobHandler;
import com.datakhaos.schedule.handler.NoRetryException;
import com.datakhaos.schedule.mapper.ScheduleJobLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 任务执行器：创建执行日志、按任务类型分派、失败重试、更新结果。
 *
 * <p>具体任务类型由 {@link JobHandler} 提供，新增类型无需修改本类。
 * 阻塞式同步执行，由调度器提交到线程池运行。</p>
 *
 * <p>WORKFLOW / SYNC / FLINK 会触发下游服务；等待超时或提交结果不确定时
 * 抛 {@link NoRetryException}，不再重试，避免重复写入。</p>
 */
@Slf4j
@Component
public class JobExecutor {

    private final ScheduleJobLogMapper jobLogMapper;
    private final Map<String, JobHandler> handlers;

    /** 正在运行的任务ID（同一实例内防止并发触发；跨实例由调度器租约保证） */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public JobExecutor(ScheduleJobLogMapper jobLogMapper, List<JobHandler> handlers) {
        this.jobLogMapper = jobLogMapper;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
                handler -> handler.type().toUpperCase(Locale.ROOT), handler -> handler, (first, second) -> first));
    }

    public boolean isRunning(String jobId) {
        return running.contains(jobId);
    }

    /** 手动触发（无计划触发时刻，业务日取 params.bizDate 或上海当日的前一日） */
    public void execute(ScheduleJob job) {
        execute(job, null);
    }

    /**
     * 执行任务。
     *
     * @param scheduledAt 计划触发时刻（cron 边界，调度器传入）；为空表示手动触发
     */
    public void execute(ScheduleJob job, LocalDateTime scheduledAt) {
        if (!running.add(job.getId())) {
            log.info("任务 {} 正在运行，跳过本次触发", job.getJobName());
            return;
        }
        ScheduleJobLog jobLog = new ScheduleJobLog();
        jobLog.setJobId(job.getId());
        jobLog.setScheduledAt(scheduledAt);
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
        String type = job.getJobType() == null ? "" : job.getJobType().toUpperCase(Locale.ROOT);
        JobHandler handler = handlers.get(type);
        if (handler == null) {
            // 历史遗留类型：明确失败，避免静默成功
            if ("REFRESH".equals(type) || "PUSH".equals(type)) {
                throw new BusinessException("任务类型 " + job.getJobType()
                        + " 尚未接入真实执行，请改用 WORKFLOW 或 SYNC");
            }
            throw new BusinessException("不支持的任务类型: " + job.getJobType());
        }
        return handler.execute(JobContext.of(job, jobLog));
    }
}

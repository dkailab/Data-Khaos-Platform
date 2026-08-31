package com.datakhaos.schedule.component;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.schedule.entity.ScheduleJob;
import com.datakhaos.schedule.mapper.ScheduleJobLogMapper;
import com.datakhaos.schedule.service.JobExecutor;
import com.datakhaos.schedule.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cron 调度器：周期扫描启用的定时任务，到点后提交到线程池异步执行。
 * 基于「最近一次实际执行时间」推导下一次触发时刻，避免重启后重复执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobScheduler {

    private final ScheduleService scheduleService;
    private final JobExecutor jobExecutor;
    private final ScheduleJobLogMapper jobLogMapper;
    private final @Qualifier("jobTaskExecutor") ThreadPoolTaskExecutor taskExecutor;

    /** jobId -> 最近一次实际执行时间 */
    private final Map<String, LocalDateTime> lastRun = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 5000L, initialDelay = 10000L)
    public void scan() {
        List<ScheduleJob> jobs = scheduleService.listEnabledWithCron();
        for (ScheduleJob job : jobs) {
            if (jobExecutor.isRunning(job.getId()) || StrUtil.isBlank(job.getCronExpression())) {
                continue;
            }
            // 校验依赖是否全部成功
            if (!scheduleService.allUpstreamSuccess(job.getId())) {
                log.debug("任务 {} 上游依赖未全部成功，跳过本轮", job.getJobName());
                continue;
            }
            try {
                CronExpression expression = CronExpression.parse(job.getCronExpression());
                LocalDateTime base = lastRun.computeIfAbsent(job.getId(), this::lastStartTime);
                LocalDateTime next = expression.next(base);
                if (next != null && !LocalDateTime.now().isBefore(next)) {
                    log.info("触发任务 {} (cron={})", job.getJobName(), job.getCronExpression());
                    taskExecutor.execute(() -> jobExecutor.execute(job));
                    lastRun.put(job.getId(), LocalDateTime.now());
                }
            } catch (Exception e) {
                log.warn("任务 {} cron 解析失败: {}", job.getJobName(), e.getMessage());
            }
        }
    }

    /** 最近一次执行时间；从未执行则以当前时间为基准（等待下一个 cron 时刻） */
    private LocalDateTime lastStartTime(String jobId) {
        LocalDateTime time = jobLogMapper.selectLastStartTime(jobId);
        return time == null ? LocalDateTime.now() : time;
    }
}

package com.datakhaos.schedule.component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.datakhaos.schedule.entity.ScheduleJob;
import com.datakhaos.schedule.mapper.ScheduleJobMapper;
import com.datakhaos.schedule.service.JobExecutor;
import com.datakhaos.schedule.service.ScheduleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Cron 调度器：按 {@code next_fire_time} 索引扫描到期任务，用原子 UPDATE 领取并加租约。
 *
 * <p>领取模型参考 DolphinScheduler 的 command/trigger 设计，但不引入额外组件：
 * 只依赖 MySQL，单副本直接可用，多副本靠 {@code owner + lease_until} 自动互斥。
 * 实例失联后租约到期，其他实例可接管；同一任务不重叠执行，错过的那次跳过。</p>
 *
 * <p>执行器组：任务配置了 {@code executorGroup} 时只由同组实例领取；
 * 为空表示任意实例可执行，适合中小团队的单实例部署。</p>
 */
@Slf4j
@Component
public class JobScheduler {

    private static final int MAX_OWNER_LENGTH = 64;

    private final ScheduleJobMapper jobMapper;
    private final JobExecutor jobExecutor;
    private final ScheduleService scheduleService;
    private final ThreadPoolTaskExecutor taskExecutor;
    private final String owner;
    private final String group;
    private final int leaseSeconds;
    private final int batchSize;

    public JobScheduler(ScheduleJobMapper jobMapper, JobExecutor jobExecutor, ScheduleService scheduleService,
                        @Qualifier("jobTaskExecutor") ThreadPoolTaskExecutor taskExecutor,
                        @Value("${SCHEDULE_INSTANCE_ID:}") String instanceId,
                        @Value("${SCHEDULE_EXECUTOR_GROUP:default}") String group,
                        @Value("${SCHEDULE_LEASE_SECONDS:600}") int leaseSeconds,
                        @Value("${SCHEDULE_SCAN_BATCH:20}") int batchSize) {
        this.jobMapper = jobMapper;
        this.jobExecutor = jobExecutor;
        this.scheduleService = scheduleService;
        this.taskExecutor = taskExecutor;
        this.owner = truncate(blank(instanceId) ? host() + ":" + UUID.randomUUID().toString().substring(0, 8)
                : instanceId.strip());
        this.group = blank(group) ? "default" : group.strip();
        this.leaseSeconds = Math.max(30, leaseSeconds);
        this.batchSize = Math.min(Math.max(1, batchSize), 200);
    }

    @Scheduled(fixedDelay = 5000L, initialDelay = 10000L)
    public void scan() {
        try {
            normalizeMissingNextFire();
            LocalDateTime now = LocalDateTime.now();
            List<ScheduleJob> due = jobMapper.selectList(Wrappers.<ScheduleJob>lambdaQuery()
                    .eq(ScheduleJob::getStatus, 1)
                    .isNotNull(ScheduleJob::getNextFireTime)
                    .le(ScheduleJob::getNextFireTime, now)
                    .and(w -> w.isNull(ScheduleJob::getOwner).or().lt(ScheduleJob::getLeaseUntil, now))
                    .and(w -> w.isNull(ScheduleJob::getExecutorGroup)
                            .or().eq(ScheduleJob::getExecutorGroup, "")
                            .or().eq(ScheduleJob::getExecutorGroup, group))
                    .orderByDesc(ScheduleJob::getPriority)
                    .orderByAsc(ScheduleJob::getNextFireTime)
                    .last("LIMIT " + batchSize));
            for (ScheduleJob job : due) {
                // 同实例正在执行（手动触发或上一轮）时不重复领取；跨实例由 owner/lease 保证
                if (jobExecutor.isRunning(job.getId())) {
                    continue;
                }
                // 上游依赖未全部成功时跳过本轮，避免下游空跑
                if (!scheduleService.allUpstreamSuccess(job.getId())) {
                    log.debug("任务 {} 上游依赖未全部成功，跳过本轮", job.getJobName());
                    continue;
                }
                claim(job);
            }
        } catch (Exception e) {
            // 扫描失败不能终止调度线程，下一轮继续
            log.warn("调度扫描失败: {}", e.getMessage());
        }
    }

    /** 续租：运行中的任务定期延长租约，进程崩溃后由其他实例接管。 */
    @Scheduled(fixedDelay = 30000L)
    public void renewLeases() {
        try {
            jobMapper.update(null, Wrappers.<ScheduleJob>lambdaUpdate()
                    .eq(ScheduleJob::getOwner, owner)
                    .isNotNull(ScheduleJob::getLeaseUntil)
                    .set(ScheduleJob::getLeaseUntil, LocalDateTime.now().plusSeconds(leaseSeconds)));
        } catch (Exception e) {
            log.warn("调度租约续期失败: {}", e.getMessage());
        }
    }

    private void claim(ScheduleJob job) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime due = job.getNextFireTime();
        LocalDateTime next = nextFire(job.getCronExpression(), now);
        int updated = jobMapper.update(null, Wrappers.<ScheduleJob>lambdaUpdate()
                .eq(ScheduleJob::getId, job.getId())
                .eq(ScheduleJob::getStatus, 1)
                .le(ScheduleJob::getNextFireTime, now)
                .and(w -> w.isNull(ScheduleJob::getOwner).or().lt(ScheduleJob::getLeaseUntil, now))
                .and(w -> w.isNull(ScheduleJob::getExecutorGroup)
                        .or().eq(ScheduleJob::getExecutorGroup, "")
                        .or().eq(ScheduleJob::getExecutorGroup, group))
                .set(ScheduleJob::getOwner, owner)
                .set(ScheduleJob::getLeaseUntil, now.plusSeconds(leaseSeconds))
                .set(ScheduleJob::getNextFireTime, next)
                .set(ScheduleJob::getUpdateTime, now));
        if (updated != 1) {
            return;
        }
        if (next == null) {
            log.warn("任务 {} 的 cron 无法计算下次触发，已停止自动调度: {}",
                    job.getJobName(), job.getCronExpression());
        }
        job.setClaimedFireTime(due);
        try {
            taskExecutor.execute(() -> {
                try {
                    jobExecutor.execute(job, due);
                } finally {
                    release(job.getId(), job.getCronExpression());
                }
            });
        } catch (RuntimeException e) {
            // 线程池拒绝：立即释放，避免任务被占住到租约过期
            release(job.getId(), job.getCronExpression());
            log.error("任务 {} 提交线程池失败: {}", job.getJobName(), e.getMessage());
        }
    }

    /**
     * 释放领取。若本次运行跨过了下一个触发点，把 next_fire_time 推到下一个周期，
     * 保证同一任务不重叠执行（错过的那次跳过）。
     */
    private void release(String jobId, String cron) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        var update = Wrappers.<ScheduleJob>lambdaUpdate()
                .eq(ScheduleJob::getId, jobId)
                .eq(ScheduleJob::getOwner, owner)
                .set(ScheduleJob::getOwner, null)
                .set(ScheduleJob::getLeaseUntil, null);
        ScheduleJob current = jobMapper.selectById(jobId);
        if (current != null && current.getNextFireTime() != null && current.getNextFireTime().isBefore(now)) {
            update.set(ScheduleJob::getNextFireTime, nextFire(cron, now));
        }
        jobMapper.update(null, update);
    }

    /** 存量任务或首次部署时补齐 next_fire_time；只在缺失时写，避免覆盖正在进行的调度。 */
    private void normalizeMissingNextFire() {
        List<ScheduleJob> missing = jobMapper.selectList(Wrappers.<ScheduleJob>lambdaQuery()
                .eq(ScheduleJob::getStatus, 1)
                .isNull(ScheduleJob::getNextFireTime)
                .last("LIMIT " + batchSize));
        if (missing.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        for (ScheduleJob job : missing) {
            LocalDateTime next = nextFire(job.getCronExpression(), now);
            if (next == null) {
                continue;
            }
            jobMapper.update(null, Wrappers.<ScheduleJob>lambdaUpdate()
                    .eq(ScheduleJob::getId, job.getId())
                    .isNull(ScheduleJob::getNextFireTime)
                    .set(ScheduleJob::getNextFireTime, next)
                    .set(ScheduleJob::getUpdateTime, now));
        }
    }

    private LocalDateTime nextFire(String cron, LocalDateTime base) {
        if (blank(cron)) {
            return null;
        }
        try {
            return CronExpression.parse(cron).next(base);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String host() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "schedule";
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value) {
        return value.length() <= MAX_OWNER_LENGTH ? value : value.substring(0, MAX_OWNER_LENGTH);
    }
}

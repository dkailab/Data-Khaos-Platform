package com.datakhaos.workflow.runner;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.datakhaos.workflow.entity.FlinkDeployment;
import com.datakhaos.workflow.entity.FlinkStageCount;
import com.datakhaos.workflow.flink.FlinkRuntimeClient;
import com.datakhaos.workflow.mapper.FlinkDeploymentMapper;
import com.datakhaos.workflow.mapper.FlinkStageCountMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.datakhaos.workflow.entity.FlinkDeployment.State.RECONCILING;
import static com.datakhaos.workflow.entity.FlinkDeployment.State.SUBMITTED;

/**
 * 周期采集运行中作业的算子累计进出记录数，落共享库供质量域做管道阶段计数对账。
 *
 * <p>默认关闭：需要显式设置 FLINK_PIPELINE_COUNT_SAMPLER_ENABLED=true。
 * 引擎失联或个别作业采样失败只记日志，不影响其他作业与平台主流程。</p>
 *
 * <p>同一轮采样的所有算子共用 {@code sampleBatch}，质量规则据此只取最新一批，
 * 避免把不同轮次的累计值混在一起比较。历史采样默认保留 7 天，避免表无限增长。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "FLINK_PIPELINE_COUNT_SAMPLER_ENABLED", havingValue = "true")
public class PipelineCountSampleScheduler {
    private final FlinkDeploymentMapper deployments;
    private final FlinkRuntimeClient runtime;
    private final FlinkStageCountMapper samples;
    private final int retentionDays;

    public PipelineCountSampleScheduler(FlinkDeploymentMapper deployments, FlinkRuntimeClient runtime,
                                        FlinkStageCountMapper samples,
                                        @Value("${FLINK_PIPELINE_COUNT_RETENTION_DAYS:7}") int retentionDays) {
        this.deployments = deployments;
        this.runtime = runtime;
        this.samples = samples;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${FLINK_PIPELINE_COUNT_SAMPLE_MS:60000}")
    public void sample() {
        purgeExpired();
        List<FlinkDeployment> active = deployments.selectList(Wrappers.lambdaQuery(FlinkDeployment.class)
                .in(FlinkDeployment::getState, SUBMITTED, RECONCILING)
                .isNotNull(FlinkDeployment::getEngineJobId));
        if (active == null || active.isEmpty()) return;

        LocalDateTime sampledAt = LocalDateTime.now().withNano(0);
        for (FlinkDeployment deployment : active) {
            if (!runtime.bound(deployment.getProjectId())) continue;
            // 每轮、每个作业一个批次：某个作业失败不影响其他作业已写入批次的完整性。
            String batch = UUID.randomUUID().toString();
            try {
                for (FlinkRuntimeClient.StageCount count : runtime.stageCounts(deployment.getEngineJobId())) {
                    FlinkStageCount row = new FlinkStageCount();
                    row.setRunId(deployment.getId());
                    row.setJobId(deployment.getEngineJobId());
                    row.setSampleBatch(batch);
                    row.setVertexId(count.vertexId());
                    row.setStage(count.stage());
                    row.setStageOrder(count.stageOrder());
                    row.setSampledAt(sampledAt);
                    row.setRecordsIn(count.recordsIn());
                    row.setRecordsOut(count.recordsOut());
                    samples.insert(row);
                }
            } catch (Exception e) {
                log.warn("阶段计数采样失败 runId={} jobId={}: {}", deployment.getId(),
                        deployment.getEngineJobId(), e.getMessage());
            }
        }
    }

    /** 清理超过保留期的采样；失败只记日志，不能阻断采样。 */
    private void purgeExpired() {
        if (retentionDays <= 0) return;
        try {
            LocalDateTime cutoff = LocalDateTime.now().withNano(0).minusDays(retentionDays);
            int removed = samples.delete(Wrappers.lambdaQuery(FlinkStageCount.class)
                    .lt(FlinkStageCount::getSampledAt, cutoff));
            if (removed > 0) {
                log.info("清理过期阶段计数采样 {} 条（早于 {}）", removed, cutoff);
            }
        } catch (Exception e) {
            log.warn("清理过期阶段计数采样失败: {}", e.getMessage());
        }
    }
}

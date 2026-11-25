package com.datakhaos.workflow.runner;

import com.datakhaos.workflow.flink.FlinkRuntimeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "FLINK_CHECKPOINT_ALERT_ENABLED", havingValue = "true")
class FlinkCheckpointAlertScheduler {
    private static final Set<String> TERMINAL = Set.of("FINISHED", "CANCELED", "FAILED");

    private final FlinkCheckpointMonitor monitor;
    private final FlinkRuntimeClient runtime;
    private String cursor = "";

    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public void scan() {
        var runs = monitor.candidates(runtime.clusterKey(), cursor);
        for (var run : runs) {
            if (!runtime.bound(run.projectId())) continue;
            try {
                boolean ended = run.state().terminal();
                if (!ended) {
                    var job = runtime.job(run.jobId());
                    if (!run.jobId().equals(job.id())) continue;
                    ended = TERMINAL.contains(job.status());
                }
                monitor.record(run.id(), runtime.clusterKey(), run.jobId(),
                        ended ? null : runtime.checkpoints(run.jobId()), ended, LocalDateTime.now());
            } catch (RuntimeException e) {
                log.warn("Flink 检查点巡检失败 runId={} error={}", run.id(), e.getMessage());
            }
        }
        cursor = runs.size() < 20 ? "" : runs.get(runs.size() - 1).id();
    }
}

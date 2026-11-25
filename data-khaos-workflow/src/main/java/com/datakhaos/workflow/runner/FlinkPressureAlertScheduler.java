package com.datakhaos.workflow.runner;

import com.datakhaos.workflow.flink.FlinkRuntimeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "FLINK_PRESSURE_ALERT_ENABLED", havingValue = "true")
class FlinkPressureAlertScheduler {
    private final FlinkPressureMonitor monitor;
    private final FlinkRuntimeClient runtime;
    private String cursor = "";

    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public void scan() {
        var runs = monitor.candidates(runtime.clusterKey(), cursor);
        for (var run : runs) {
            if (!runtime.bound(run.projectId())) continue;
            try {
                monitor.record(run.id(), runtime.clusterKey(), run.jobId(),
                        run.state().terminal() ? null : runtime.pressure(run.jobId()), LocalDateTime.now());
            } catch (RuntimeException e) {
                log.warn("Flink 反压巡检失败 runId={} type={}", run.id(), e.getClass().getSimpleName());
            }
        }
        cursor = runs.size() < FlinkPressureMonitor.BATCH_SIZE ? "" : runs.get(runs.size() - 1).id();
    }
}

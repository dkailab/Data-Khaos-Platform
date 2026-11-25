package com.datakhaos.workflow.runner;

import com.datakhaos.workflow.flink.FlinkRuntimeClient;
import com.datakhaos.workflow.flink.FlinkRuntimeClient.KafkaReading;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "FLINK_KAFKA_ALERT_ENABLED", havingValue = "true")
class FlinkKafkaAlertScheduler {
    private final FlinkKafkaMonitor monitor;
    private final FlinkRuntimeClient runtime;
    private String cursor = "";

    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public void scan() {
        var runs = monitor.candidates(runtime.clusterKey(), cursor);
        for (var run : runs) {
            if (!runtime.bound(run.project())) continue;
            try {
                KafkaReading reading = null;
                if (!run.state().terminal()) {
                    try {
                        reading = runtime.kafkaLag(run.jobId(), run.vertexId());
                    } catch (FlinkRuntimeClient.RuntimeFailure unavailable) {
                        log.debug("Kafka 指标暂不可用 runId={}", run.id());
                    }
                }
                monitor.record(run, runtime.clusterKey(), reading, System.currentTimeMillis(), LocalDateTime.now());
            } catch (RuntimeException e) {
                log.warn("Flink Kafka 积压巡检失败 runId={} type={}", run.id(), e.getClass().getSimpleName());
            }
        }
        cursor = runs.size() < FlinkKafkaMonitor.BATCH_SIZE ? "" : runs.get(runs.size() - 1).id();
    }
}

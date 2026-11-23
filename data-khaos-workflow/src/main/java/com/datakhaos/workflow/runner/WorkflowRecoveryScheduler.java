package com.datakhaos.workflow.runner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
public class WorkflowRecoveryScheduler {
    private final WorkflowRunLifecycle lifecycle;
    private final boolean enabled;
    private final long timeoutSeconds;

    public WorkflowRecoveryScheduler(WorkflowRunLifecycle lifecycle,
                                     @Value("${data-khaos.workflow.recovery-enabled:true}") boolean enabled,
                                     @Value("${data-khaos.workflow.recovery-timeout-seconds:300}") long timeoutSeconds) {
        this.lifecycle = lifecycle;
        this.enabled = enabled;
        this.timeoutSeconds = Math.max(60, timeoutSeconds);
    }

    @Scheduled(initialDelay = 30000, fixedDelay = 30000)
    public void reconcile() {
        if (!enabled) return;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusSeconds(timeoutSeconds);
        for (String runId : lifecycle.expired(cutoff)) {
            try {
                lifecycle.expire(runId, cutoff, now);
            } catch (RuntimeException e) {
                log.warn("工作流恢复扫描失败 runId={}", runId, e);
            }
        }
    }
}

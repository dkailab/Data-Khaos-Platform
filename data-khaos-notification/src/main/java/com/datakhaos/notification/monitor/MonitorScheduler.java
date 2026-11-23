package com.datakhaos.notification.monitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "data-khaos.monitor.enabled", havingValue = "true", matchIfMissing = true)
class MonitorScheduler {
    static final long INTERVAL_MS = 30_000;

    private final MonitorService monitor;
    private final MonitorProbe probe;

    @Scheduled(fixedDelay = INTERVAL_MS, initialDelay = 10_000)
    void sampleServices() {
        for (MonitorViews.ServiceStatus service : monitor.services()) {
            try {
                Instant start = Instant.now();
                MonitorProbe.Sample sample = probe.check(service.serviceId());
                monitor.record(service.serviceId(), start.toEpochMilli() / INTERVAL_MS,
                        LocalDateTime.ofInstant(start, ZoneId.systemDefault()), sample);
            } catch (RuntimeException e) {
                log.warn("平台监控记录失败 serviceId={}", service.serviceId(), e);
            }
        }
    }
}

package com.datakhaos.workflow.runner;

import com.datakhaos.common.model.R;
import com.datakhaos.workflow.api.model.FlinkAlertEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;

@Slf4j
@Component
class FlinkAlertDelivery {
    private final FlinkAlertOutbox outbox;
    private final RestTemplate http;
    private final String token;

    FlinkAlertDelivery(FlinkAlertOutbox outbox, @Qualifier("orchestrationRestTemplate") RestTemplate http,
                       @Value("${FLINK_ALERT_NOTIFICATION_TOKEN:}") String token) {
        this.outbox = outbox;
        this.http = http;
        this.token = token;
    }

    @Scheduled(initialDelay = 10_000, fixedDelay = 10_000)
    public void deliver() {
        if (!outbox.enabled()) return;
        HttpHeaders headers = new HttpHeaders();
        headers.set(FlinkAlertEvent.TOKEN_HEADER, token);
        for (var pending : outbox.due(LocalDateTime.now())) {
            boolean accepted = false;
            try {
                R<?> result = http.postForObject("http://data-khaos-notification" + FlinkAlertEvent.NOTIFICATION_PATH,
                        new HttpEntity<>(pending.event(), headers), R.class);
                accepted = result != null && result.getCode() == 0 && Boolean.TRUE.equals(result.getData());
            } catch (RestClientException e) {
                log.debug("Flink 告警投递失败 eventId={} type={}", pending.id(), e.getClass().getSimpleName());
            }
            if (accepted) {
                outbox.delivered(pending, LocalDateTime.now());
            } else {
                outbox.retry(pending, LocalDateTime.now());
            }
        }
    }
}

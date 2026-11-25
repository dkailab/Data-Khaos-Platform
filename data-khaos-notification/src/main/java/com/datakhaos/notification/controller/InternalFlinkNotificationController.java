package com.datakhaos.notification.controller;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.R;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.notification.service.NotificationService;
import com.datakhaos.workflow.api.model.FlinkAlertEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

@RestController
public class InternalFlinkNotificationController {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final NotificationService notifications;
    private final byte[] expectedToken;

    public InternalFlinkNotificationController(NotificationService notifications,
                                                @Value("${FLINK_ALERT_NOTIFICATION_TOKEN:}") String token) {
        this.notifications = notifications;
        this.expectedToken = token.isBlank() ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(FlinkAlertEvent.NOTIFICATION_PATH)
    public R<Boolean> accept(@RequestHeader(value = FlinkAlertEvent.TOKEN_HEADER, required = false) String token,
                          @RequestBody FlinkAlertEvent event) {
        if (expectedToken.length == 0 || token == null
                || !MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        if (event == null || event.type() == null || !validId(event.alertId()) || !validId(event.runId())
                || !validId(event.receiverId())) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "Flink 告警事件格式无效");
        }
        notifications.recordFlinkAlertEvent(event);
        return R.ok(Boolean.TRUE);
    }

    private boolean validId(String value) {
        return value != null && ID.matcher(value).matches();
    }
}

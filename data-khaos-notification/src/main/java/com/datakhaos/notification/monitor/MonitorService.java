package com.datakhaos.notification.monitor;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.datakhaos.common.constant.CommonConstants;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.notification.monitor.MonitorViews.Alert;
import com.datakhaos.notification.monitor.MonitorViews.ServiceStatus;
import com.datakhaos.notification.service.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MonitorService {
    private static final int FAILURE_THRESHOLD = 3;
    private static final int RECOVERY_THRESHOLD = 2;

    private final MonitorRepository repository;
    private final NotificationService notifications;

    public MonitorService(MonitorRepository repository, NotificationService notifications) {
        this.repository = repository;
        this.notifications = notifications;
    }

    public List<ServiceStatus> services() {
        return repository.services();
    }

    public PageResult<Alert> alerts(long current, long size, boolean openOnly) {
        if (current < 1 || size < 1 || size > 100 || current > Integer.MAX_VALUE) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "分页参数无效");
        }
        long total = repository.countAlerts(openOnly);
        long offset = (current - 1) * size;
        List<Alert> records = offset >= total ? List.of() : repository.alerts(openOnly, size, offset);
        return PageResult.of(current, size, total, records);
    }

    public Alert acknowledge(String alertId, String userId) {
        repository.acknowledge(alertId, userId, LocalDateTime.now());
        Alert alert = repository.findAlert(alertId);
        if (alert == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "告警不存在");
        }
        return alert;
    }

    @Transactional
    public void record(String serviceId, long bucket, LocalDateTime checkedAt, MonitorProbe.Sample sample) {
        MonitorRepository.State previous = repository.lockState(serviceId);
        if (bucket <= previous.bucket()) {
            return;
        }
        int healthy = sample.status() == MonitorStatus.UP ? Math.min(previous.healthy() + 1, RECOVERY_THRESHOLD) : 0;
        int unhealthy = sample.status().unhealthy() ? Math.min(previous.unhealthy() + 1, FAILURE_THRESHOLD) : 0;
        String activeAlert = previous.activeAlert();

        if (activeAlert == null && unhealthy == FAILURE_THRESHOLD) {
            activeAlert = IdWorker.getIdStr();
            repository.open(activeAlert, serviceId, previous.name(), checkedAt, sample.reason());
            String condition = "UNREGISTERED".equals(sample.reason()) ? "未发现注册实例" : "部分或全部实例不可达";
            notifications.recordSiteMessage(CommonConstants.DEFAULT_ADMIN_ID,
                    "平台服务异常 · " + previous.name(),
                    condition + "，请在平台监控告警中心查看。告警 ID " + activeAlert);
        } else if (activeAlert != null && healthy == RECOVERY_THRESHOLD) {
            repository.recover(activeAlert, checkedAt);
            notifications.recordSiteMessage(CommonConstants.DEFAULT_ADMIN_ID,
                    "平台服务恢复 · " + previous.name(), "服务已恢复。告警 ID " + activeAlert);
            activeAlert = null;
        }
        repository.saveSample(serviceId, bucket, checkedAt, sample, healthy, unhealthy, activeAlert);
    }
}

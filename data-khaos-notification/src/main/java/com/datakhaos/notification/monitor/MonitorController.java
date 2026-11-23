package com.datakhaos.notification.monitor;

import com.datakhaos.common.constant.CommonConstants;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.JwtUtil;
import com.datakhaos.notification.monitor.MonitorViews.Alert;
import com.datakhaos.notification.monitor.MonitorViews.ServiceStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/api/notify/monitor")
public class MonitorController {
    private final MonitorService monitor;
    private final String jwtSecret;

    public MonitorController(MonitorService monitor, @Value("${data-khaos.monitor.jwt-secret}") String jwtSecret) {
        this.monitor = monitor;
        this.jwtSecret = jwtSecret;
    }

    @GetMapping("/services")
    public R<List<ServiceStatus>> services(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        requireAdmin(authorization);
        return R.ok(monitor.services());
    }

    @GetMapping("/alerts")
    public R<PageResult<Alert>> alerts(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                       @RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(defaultValue = "false") boolean openOnly) {
        requireAdmin(authorization);
        return R.ok(monitor.alerts(current, size, openOnly));
    }

    @PostMapping("/alerts/{id}/acknowledge")
    public R<Alert> acknowledge(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                  @PathVariable String id) {
        return R.ok(monitor.acknowledge(id, requireAdmin(authorization)));
    }

    private String requireAdmin(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        String token = authorization.substring("Bearer ".length());
        Date expiry = JwtUtil.getExpiresAt(token);
        if (!JwtUtil.verify(token, jwtSecret) || expiry == null || !expiry.after(new Date())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        String userId = JwtUtil.getUid(token, jwtSecret);
        if (!CommonConstants.DEFAULT_ADMIN_ID.equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return userId;
    }
}

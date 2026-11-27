package com.datakhaos.workflow.controller;

import com.datakhaos.common.model.R;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.workflow.flink.FlinkRunView;
import com.datakhaos.workflow.flink.FlinkEventTimeSample;
import com.datakhaos.workflow.flink.FlinkCheckpointView;
import com.datakhaos.workflow.flink.FlinkMetricsView;
import com.datakhaos.workflow.service.FlinkDeploymentService;
import com.datakhaos.workflow.runner.FlinkCheckpointMonitor.AlertHistory;
import com.datakhaos.workflow.runner.FlinkPressureMonitor;
import com.datakhaos.workflow.runner.FlinkKafkaMonitor;
import com.datakhaos.workflow.runner.FlinkEventTimeMonitor;
import com.datakhaos.workflow.service.FlinkDeploymentService.RunPage;
import com.datakhaos.workflow.service.FlinkDeploymentService.SqlValidation;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workflow/flink/projects/{projectId}/assets/{id}")
@RequiredArgsConstructor
public class FlinkDeploymentController {
    private final FlinkDeploymentService runs;

    @GetMapping("/runs")
    public R<RunPage> page(@PathVariable String projectId, @PathVariable String id,
                           @RequestParam(defaultValue = "1") long current,
                           @RequestParam(defaultValue = "10") long size) {
        return R.ok(runs.page(projectId, id, current, size));
    }

    @PostMapping("/versions/{version}/validate")
    public R<SqlValidation> validate(@PathVariable String projectId, @PathVariable String id, @PathVariable int version,
                                     HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return R.ok(runs.validateSql(projectId, id, version));
    }

    @PostMapping("/versions/{version}/runs")
    public R<FlinkRunView> submit(@PathVariable String projectId, @PathVariable String id, @PathVariable int version,
                                  @RequestBody SubmitRequest request) {
        return R.ok(runs.submit(projectId, id, version, request.requestId()));
    }

    @PostMapping("/runs/{runId}/refresh")
    public R<FlinkRunView> refresh(@PathVariable String projectId, @PathVariable String id, @PathVariable String runId) {
        return R.ok(runs.refresh(projectId, id, runId));
    }

    @GetMapping("/runs/{runId}/checkpoint-alerts")
    public R<AlertHistory> checkpointAlerts(@PathVariable String projectId, @PathVariable String id,
                                            @PathVariable String runId) {
        return R.ok(runs.checkpointAlerts(projectId, id, runId));
    }

    @GetMapping("/runs/{runId}/checkpoints")
    public R<FlinkCheckpointView> checkpoints(@PathVariable String projectId, @PathVariable String id,
                                              @PathVariable String runId) {
        return R.ok(runs.checkpoints(projectId, id, runId));
    }

    @GetMapping("/runs/{runId}/pressure-alerts")
    public R<FlinkPressureMonitor.History> pressureAlerts(@PathVariable String projectId, @PathVariable String id,
                                                         @PathVariable String runId) {
        return R.ok(runs.pressureAlerts(projectId, id, runId));
    }

    @GetMapping("/runs/{runId}/kafka-alerts")
    public R<FlinkKafkaMonitor.History> kafkaAlerts(@PathVariable String projectId, @PathVariable String id,
                                                    @PathVariable String runId) {
        return R.ok(runs.kafkaAlerts(projectId, id, runId));
    }

    @PutMapping("/runs/{runId}/kafka-alerts")
    public R<FlinkKafkaMonitor.History> configureKafkaAlerts(@PathVariable String projectId, @PathVariable String id,
            @PathVariable String runId, @RequestBody KafkaRule rule) {
        if (rule.enabled() == null) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "启用状态必填");
        }
        return R.ok(runs.configureKafkaAlerts(projectId, id, runId, rule.enabled(),
                rule.vertexId(), rule.highThreshold(), rule.lowThreshold()));
    }

    @GetMapping("/runs/{runId}/event-time-alerts")
    public R<FlinkEventTimeMonitor.History> eventTimeAlerts(@PathVariable String projectId, @PathVariable String id,
                                                            @PathVariable String runId) {
        return R.ok(runs.eventTimeAlerts(projectId, id, runId));
    }

    @PutMapping("/runs/{runId}/event-time-alerts")
    public R<FlinkEventTimeMonitor.History> configureEventTimeAlerts(@PathVariable String projectId, @PathVariable String id,
            @PathVariable String runId, @RequestBody EventTimeRule rule) {
        if (rule.enabled() == null) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "启用状态必填");
        }
        return R.ok(runs.configureEventTimeAlerts(projectId, id, runId, rule.enabled(), rule.vertexId()));
    }

    @GetMapping("/runs/{runId}/event-time")
    public R<FlinkEventTimeSample> eventTime(@PathVariable String projectId, @PathVariable String id,
                                            @PathVariable String runId, @RequestParam String vertexId) {
        return R.ok(runs.eventTime(projectId, id, runId, vertexId));
    }

    @GetMapping("/runs/{runId}/metrics")
    public R<FlinkMetricsView> metrics(@PathVariable String projectId, @PathVariable String id,
                                        @PathVariable String runId, @RequestParam(required = false) String vertexId) {
        return R.ok(runs.metrics(projectId, id, runId, vertexId));
    }

    @PostMapping("/runs/{runId}/cancel")
    public R<FlinkRunView> cancel(@PathVariable String projectId, @PathVariable String id, @PathVariable String runId) {
        return R.ok(runs.cancel(projectId, id, runId));
    }

    @PostMapping("/runs/{runId}/stop")
    public R<FlinkRunView> stop(@PathVariable String projectId, @PathVariable String id, @PathVariable String runId) {
        return R.ok(runs.stopWithSavepoint(projectId, id, runId));
    }

    @PostMapping("/runs/{runId}/resume")
    public R<FlinkRunView> resume(@PathVariable String projectId, @PathVariable String id,
                                  @PathVariable String runId, @RequestBody SubmitRequest request) {
        return R.ok(runs.resume(projectId, id, runId, request.requestId()));
    }

    public record EventTimeRule(Boolean enabled, String vertexId) {}

    public record KafkaRule(Boolean enabled, String vertexId, Long highThreshold, Long lowThreshold) {}

    public record SubmitRequest(String requestId) {}
}

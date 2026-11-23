package com.datakhaos.workflow.controller;

import com.datakhaos.common.model.R;
import com.datakhaos.workflow.flink.FlinkRunView;
import com.datakhaos.workflow.flink.FlinkCheckpointView;
import com.datakhaos.workflow.flink.FlinkMetricsView;
import com.datakhaos.workflow.service.FlinkDeploymentService;
import com.datakhaos.workflow.service.FlinkDeploymentService.RunPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    @PostMapping("/versions/{version}/runs")
    public R<FlinkRunView> submit(@PathVariable String projectId, @PathVariable String id, @PathVariable int version,
                                  @RequestBody SubmitRequest request) {
        return R.ok(runs.submit(projectId, id, version, request.requestId()));
    }

    @PostMapping("/runs/{runId}/refresh")
    public R<FlinkRunView> refresh(@PathVariable String projectId, @PathVariable String id, @PathVariable String runId) {
        return R.ok(runs.refresh(projectId, id, runId));
    }

    @GetMapping("/runs/{runId}/checkpoints")
    public R<FlinkCheckpointView> checkpoints(@PathVariable String projectId, @PathVariable String id,
                                              @PathVariable String runId) {
        return R.ok(runs.checkpoints(projectId, id, runId));
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

    public record SubmitRequest(String requestId) {}
}

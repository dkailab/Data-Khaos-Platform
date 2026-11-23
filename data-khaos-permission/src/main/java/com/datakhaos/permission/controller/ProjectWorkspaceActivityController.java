package com.datakhaos.permission.controller;

import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.permission.api.model.ProjectWorkspaceRunDto;
import com.datakhaos.permission.api.model.ProjectWorkspaceRunDetailDto;
import com.datakhaos.permission.api.model.ProjectWorkspaceTaskDetailDto;
import com.datakhaos.permission.api.model.ProjectWorkspaceTaskDto;
import com.datakhaos.permission.service.ProjectWorkspaceActivityService;
import com.datakhaos.permission.service.ProjectRunOperations;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/permission/project-group/{projectId}/workspace")
@RequiredArgsConstructor
public class ProjectWorkspaceActivityController {
    private final ProjectWorkspaceActivityService activity;
    private final ProjectRunOperations operations;

    @GetMapping("/tasks")
    public R<PageResult<ProjectWorkspaceTaskDto>> tasks(@PathVariable String projectId,
                                                         @RequestParam(defaultValue = "1") long current,
                                                         @RequestParam(defaultValue = "10") long size,
                                                         @RequestParam(required = false) String kind,
                                                         @RequestParam(required = false) String keyword) {
        return R.ok(activity.tasks(projectId, current, size, kind, keyword));
    }

    @GetMapping("/runs")
    public R<PageResult<ProjectWorkspaceRunDto>> runs(@PathVariable String projectId,
                                                       @RequestParam(defaultValue = "1") long current,
                                                       @RequestParam(defaultValue = "10") long size,
                                                       @RequestParam(required = false) String kind,
                                                       @RequestParam(required = false) String bizDate,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) String taskId,
                                                       @RequestParam(required = false) String triggerType,
                                                       @RequestParam(defaultValue = "false") boolean alertsOnly,
                                                       @RequestParam(defaultValue = "false") boolean unacknowledgedOnly) {
        return R.ok(activity.runs(projectId, current, size, kind, bizDate, status, taskId, triggerType,
                alertsOnly, unacknowledgedOnly));
    }

    @GetMapping("/tasks/{kind}/{id}")
    public R<ProjectWorkspaceTaskDetailDto> task(@PathVariable String projectId, @PathVariable String kind,
                                                 @PathVariable String id) {
        return R.ok(activity.task(projectId, kind, id));
    }

    @GetMapping("/runs/{kind}/{id}")
    public R<ProjectWorkspaceRunDetailDto> run(@PathVariable String projectId, @PathVariable String kind,
                                               @PathVariable String id) {
        return R.ok(activity.run(projectId, kind, id));
    }

    @PostMapping("/runs/WORKFLOW/{id}/acknowledge")
    public R<Void> acknowledge(@PathVariable String projectId, @PathVariable String id) {
        operations.acknowledge(projectId, id);
        return R.ok();
    }

    @PostMapping("/runs/WORKFLOW/{id}/resolve-recovery")
    public R<Void> resolveRecovery(@PathVariable String projectId, @PathVariable String id,
                                   @RequestBody RecoveryConfirmation confirmation) {
        operations.resolve(projectId, id, confirmation.note());
        return R.ok();
    }

    public record RecoveryConfirmation(String note) {}
}

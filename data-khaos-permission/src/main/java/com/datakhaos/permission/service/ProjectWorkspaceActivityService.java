package com.datakhaos.permission.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.permission.api.model.ProjectWorkspaceRunDto;
import com.datakhaos.permission.api.model.ProjectWorkspaceRunDetailDto;
import com.datakhaos.permission.api.model.ProjectWorkspaceTaskDetailDto;
import com.datakhaos.permission.api.model.ProjectWorkspaceTaskDto;
import com.datakhaos.permission.mapper.ProjectWorkspaceActivityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProjectWorkspaceActivityService {
    private static final Set<String> RUN_STATES = Set.of("PENDING", "RUNNING", "SUCCESS", "FAILED", "STOP", "UNKNOWN", "RECOVERY_REQUIRED");
    private static final Set<String> TRIGGER_TYPES = Set.of("MANUAL", "SCHEDULE", "BACKFILL", "WORKFLOW", "CRON");

    private final ProjectGroupAccess access;
    private final ProjectWorkspaceActivityMapper mapper;

    public PageResult<ProjectWorkspaceTaskDto> tasks(String projectId, long current, long size, String kind,
                                                      String keyword) {
        access.requireMembership(projectId);
        String term = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (term.length() > 100) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "搜索词最多 100 个字符");
        }
        String search = term.isEmpty() ? null : "%" + term.replace("!", "!!")
                .replace("%", "!%").replace("_", "!_") + "%";
        return result(mapper.tasks(page(current, size), projectId, kind(kind), search));
    }

    public PageResult<ProjectWorkspaceRunDto> runs(String projectId, long current, long size, String kind,
                                                   String bizDate, String status, String taskId, String triggerType,
                                                   boolean alertsOnly, boolean unacknowledgedOnly) {
        access.requireMembership(projectId);
        if (bizDate != null) {
            try {
                if (bizDate.length() != 10 || !LocalDate.parse(bizDate).toString().equals(bizDate)) {
                    throw new DateTimeParseException("日期格式错误", bizDate, 0);
                }
            } catch (DateTimeParseException e) {
                throw new BusinessException(ResultCode.VALIDATE_FAILED, "业务日期格式为 YYYY-MM-DD");
            }
        }
        if (status != null && !RUN_STATES.contains(status)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "运行状态无效");
        }
        if (triggerType != null && !TRIGGER_TYPES.contains(triggerType)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "触发类型无效");
        }
        if (taskId != null && (taskId.isBlank() || taskId.length() > 32)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "任务 ID 无效");
        }
        return result(mapper.runs(page(current, size), projectId, kind(kind), bizDate, status, taskId, triggerType, alertsOnly || unacknowledgedOnly, unacknowledgedOnly));
    }

    public ProjectWorkspaceTaskDetailDto task(String projectId, String kind, String id) {
        access.requireMembership(projectId);
        boolean workflow = "WORKFLOW".equals(kind(kind));
        ProjectWorkspaceActivityMapper.TaskDetail task = workflow ? mapper.workflowTask(projectId, id) : mapper.pipelineTask(projectId, id);
        if (task == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "任务不存在或不可访问");
        }
        return new ProjectWorkspaceTaskDetailDto(task.id(), kind, task.name(), task.status(), task.updatedAt(),
                task.engine(), task.sourceName(), task.targetName(), task.workspaceRunnable(),
                workflow ? mapper.workflowNodes(projectId, id) : List.of());
    }

    public ProjectWorkspaceRunDetailDto run(String projectId, String kind, String id) {
        access.requireMembership(projectId);
        boolean workflow = "WORKFLOW".equals(kind(kind));
        ProjectWorkspaceActivityMapper.RunDetail run = workflow ? mapper.workflowRun(projectId, id) : mapper.pipelineRun(projectId, id);
        if (run == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "运行不存在或不可访问");
        }
        return new ProjectWorkspaceRunDetailDto(run.id(), kind, run.taskId(), run.taskName(), run.status(),
                run.bizDate(), run.startedAt(), run.endedAt(), run.durationMs(), run.triggerType(), run.failureReason(),
                run.recoveryCode(), run.recoveryResolvedAt(), run.recoveryResolvedBy(), run.recoveryNote(),
                run.alertAcknowledgedAt(), workflow ? mapper.workflowNodeRuns(projectId, id) : List.of());
    }

    private static String kind(String kind) {
        if (kind != null && !"WORKFLOW".equals(kind) && !"PIPELINE".equals(kind)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "任务类型无效");
        }
        return kind;
    }

    private static <T> Page<T> page(long current, long size) {
        if (current < 1 || current > Integer.MAX_VALUE || size < 1 || size > 100) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "分页参数无效");
        }
        return new Page<>(current, size);
    }

    private static <T> PageResult<T> result(IPage<T> page) {
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }
}

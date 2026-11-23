package com.datakhaos.pipeline.workspace;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.pipeline.api.model.WorkspacePipelineRunReceipt;
import com.datakhaos.pipeline.api.model.WorkspacePipelineRunRequest;
import com.datakhaos.pipeline.engine.impl.DbSyncEngine;
import com.datakhaos.pipeline.entity.PipelineInstance;
import com.datakhaos.pipeline.entity.PipelineTask;
import com.datakhaos.pipeline.mapper.PipelineInstanceMapper;
import com.datakhaos.pipeline.mapper.PipelineTaskMapper;
import com.datakhaos.pipeline.service.PipelineRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Service
class WorkspacePipelineRunService {
    private final PipelineTaskMapper tasks;
    private final PipelineInstanceMapper instances;
    private final WorkspacePipelinePolicy policy;
    private final PipelineRunner runner;
    private final DbSyncEngine engine;
    private final ThreadPoolTaskExecutor executor;

    WorkspacePipelineRunService(PipelineTaskMapper tasks, PipelineInstanceMapper instances,
                                WorkspacePipelinePolicy policy, PipelineRunner runner, DbSyncEngine engine,
                                @Qualifier("workspacePipelineExecutor") ThreadPoolTaskExecutor executor) {
        this.tasks = tasks;
        this.instances = instances;
        this.policy = policy;
        this.runner = runner;
        this.engine = engine;
        this.executor = executor;
    }

    WorkspacePipelineRunReceipt submit(String projectId, String taskId, String authorization, WorkspacePipelineRunRequest request) {
        String userId = policy.authenticate(authorization);
        policy.requireMember(projectId, userId);
        String requestId = requestId(request == null ? null : request.requestId());
        String date = businessDate(request == null ? null : request.bizDate());
        PipelineTask task = tasks.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectGroupId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "项目内任务不存在或不可访问");
        }

        PipelineInstance previous = find(projectId, taskId, userId, requestId);
        if (previous != null) {
            return replay(previous, date);
        }
        WorkspacePipelinePolicy.Connections authorized = policy.authorizeResources(task, projectId, date);
        PipelineInstance instance;
        try {
            instance = runner.createWorkspaceInstance(task, date, userId, requestId, authorized.activeKey());
        } catch (DuplicateKeyException e) {
            previous = find(projectId, taskId, userId, requestId);
            if (previous != null) {
                return replay(previous, date);
            }
            throw new BusinessException(ResultCode.DUPLICATE_KEY, "该目标表和业务日期已有运行，完成后可重试");
        }
        try {
            executor.execute(() -> execute(task, instance));
        } catch (RejectedExecutionException e) {
            runner.finishWorkspace(instance, 2, 0, "项目管道队列已满，请重新提交");
            return receipt(instance, "REJECTED", 2);
        }
        return receipt(instance, "ACCEPTED", 0);
    }

    private void execute(PipelineTask accepted, PipelineInstance instance) {
        int rows;
        try {
            PipelineTask current = tasks.selectById(accepted.getId());
            if (!accepted.equals(current)) {
                throw new BusinessException("任务配置已变更，请重新提交");
            }
            policy.requireMember(instance.getProjectGroupId(), instance.getWorkspaceUserId());
            WorkspacePipelinePolicy.Connections authorized = policy.authorizeResources(current, instance.getProjectGroupId(),
                    instance.getBizDate());
            if (!authorized.activeKey().equals(instance.getWorkspaceActiveKey())) {
                throw new BusinessException("目标数据源配置已变更，请重新提交");
            }
            rows = engine.executeWorkspace(current, instance, authorized.source(), authorized.target());
        } catch (Exception e) {
            log.warn("项目空间管道执行失败 runId={}, type={}", instance.getId(), e.getClass().getSimpleName());
            runner.finishWorkspace(instance, 2, 0,
                    e instanceof BusinessException ? e.getMessage() : "项目管道执行失败，请检查数据源及表结构");
            return;
        }
        runner.finishWorkspace(instance, 1, rows, null);
    }

    private PipelineInstance find(String projectId, String taskId, String userId, String requestId) {
        return instances.selectOne(Wrappers.<PipelineInstance>lambdaQuery()
                .eq(PipelineInstance::getProjectGroupId, projectId).eq(PipelineInstance::getTaskId, taskId)
                .eq(PipelineInstance::getWorkspaceUserId, userId).eq(PipelineInstance::getWorkspaceRequestId, requestId));
    }

    private WorkspacePipelineRunReceipt replay(PipelineInstance instance, String date) {
        if (!date.equals(instance.getBizDate())) {
            throw new BusinessException(ResultCode.DUPLICATE_KEY, "同一请求 ID 的业务日期不能改变");
        }
        return receipt(instance, "DUPLICATE", instance.getStatus());
    }

    private static WorkspacePipelineRunReceipt receipt(PipelineInstance instance, String state, Integer status) {
        return new WorkspacePipelineRunReceipt(instance.getId(), instance.getBizDate(), state, status);
    }

    private static String requestId(String value) {
        try {
            if (value != null && value.length() == 36) {
                String normalized = UUID.fromString(value).toString();
                if (normalized.equalsIgnoreCase(value)) return normalized;
            }
        } catch (IllegalArgumentException ignored) {
            // Return a single validation error for missing and malformed keys.
        }
        throw new BusinessException(ResultCode.VALIDATE_FAILED, "请求 ID 必须为 UUID");
    }

    private static String businessDate(String value) {
        if (value != null && value.length() == 10) {
            try {
                LocalDate date = LocalDate.parse(value);
                if (date.getYear() >= 1000 && date.toString().equals(value)) return value;
            } catch (DateTimeParseException ignored) {
                // Not an ISO calendar day.
            }
        }
        throw new BusinessException(ResultCode.VALIDATE_FAILED, "业务日期格式为 YYYY-MM-DD");
    }
}

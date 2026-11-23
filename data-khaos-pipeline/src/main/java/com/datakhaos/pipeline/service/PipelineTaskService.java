package com.datakhaos.pipeline.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.permission.api.service.PermissionConstants;
import com.datakhaos.pipeline.engine.EngineFactory;
import com.datakhaos.pipeline.dto.PipelinePreflight;
import com.datakhaos.pipeline.engine.impl.DbSyncEngine;
import com.datakhaos.pipeline.entity.PipelineInstance;
import com.datakhaos.pipeline.entity.PipelineTask;
import com.datakhaos.pipeline.mapper.PipelineTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * 管道任务服务：CRUD + 手动/定时触发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineTaskService {

    private final PipelineTaskMapper mapper;
    private final PipelineRunner runner;
    private final EngineFactory engineFactory;
    private final PipelineAuthContext auth;
    private final @Qualifier("pipelineTaskExecutor") ThreadPoolTaskExecutor executor;

    /** 分页查询任务 */
    public PageResult<PipelineTask> page(long current, long size, String keyword, String engine) {
        PipelineAuthContext.AuthContext ctx = auth.current();
        LambdaQueryWrapper<PipelineTask> wrapper = new LambdaQueryWrapper<PipelineTask>()
                .eq(!ctx.superAdmin() && StrUtil.isNotBlank(ctx.projectGroupId()),
                        PipelineTask::getProjectGroupId, ctx.projectGroupId())
                .like(StrUtil.isNotBlank(keyword), PipelineTask::getTaskName, keyword)
                .eq(StrUtil.isNotBlank(engine), PipelineTask::getEngine, engine)
                .orderByDesc(PipelineTask::getCreateTime);
        Page<PipelineTask> page = mapper.selectPage(new Page<>(current, size), wrapper);
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    public PipelineTask get(String id) {
        PipelineTask task = mapper.selectById(id);
        if (task == null) {
            throw new BusinessException("管道任务不存在: " + id);
        }
        return task;
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(PipelineTask task) {
        PipelineAuthContext.AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_PIPELINE_MANAGE);
        validate(task);
        task.setId(null);
        task.setProjectGroupId(ctx.superAdmin() ? task.getProjectGroupId() : ctx.projectGroupId());
        if (task.getStatus() == null) {
            task.setStatus(1);
        }
        task.setCreateBy(MetadataHolder.getUserId());
        mapper.insert(task);
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(PipelineTask task) {
        if (StrUtil.isBlank(task.getId())) {
            throw new BusinessException("任务 ID 不能为空");
        }
        PipelineTask exist = get(task.getId());
        PipelineAuthContext.AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_PIPELINE_MANAGE);
        auth.checkGroup(ctx, exist.getProjectGroupId(), "同步任务");
        if (StrUtil.isBlank(task.getTaskName())) {
            task.setTaskName(exist.getTaskName());
        }
        mapper.updateById(task);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        PipelineTask exist = get(id);
        PipelineAuthContext.AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_PIPELINE_MANAGE);
        auth.checkGroup(ctx, exist.getProjectGroupId(), "同步任务");
        mapper.deleteById(id);
    }

    /** 引擎列表（可扩展） */
    public List<Map<String, Object>> engines() {
        return engineFactory.list();
    }

    /** 手动触发运行 */
    public PipelineInstance run(String taskId) {
        return run(taskId, "MANUAL", null, null, null);
    }

    /**
     * 触发运行（供工作流/调度内部调用）。
     * 内部系统身份跳过项目组成员校验，但仍校验任务启用状态。
     *
     * @param triggerType        MANUAL/CRON/SCHEDULE/WORKFLOW
     * @param workflowRunId     关联工作流运行ID（可空）
     * @param workflowNodeRunId 关联工作流节点运行ID（可空）
     * @param bizDate           业务日期 YYYY-MM-DD（按日重算使用，可空）
     */
    public PipelineInstance run(String taskId, String triggerType,
                                String workflowRunId, String workflowNodeRunId, String bizDate) {
        PipelineTask task = get(taskId);
        boolean manual = "MANUAL".equals(triggerType);
        if (!manual || StrUtil.isNotBlank(workflowRunId) || StrUtil.isNotBlank(workflowNodeRunId)) {
            if (!MetadataHolder.isSystemCaller()) {
                throw new BusinessException("内部管道触发需要系统身份");
            }
        }
        if (!manual) {
            // 内部触发：校验任务启用，不校验项目组成员
            if (task.getStatus() == null || task.getStatus() != 1) {
                throw new BusinessException("任务已停用，请先启用: " + taskId);
            }
        } else {
            PipelineAuthContext.AuthContext ctx = auth.current();
            auth.requireCap(ctx, PermissionConstants.CAP_PIPELINE_RUN);
            auth.checkGroup(ctx, task.getProjectGroupId(), "同步任务");
            if (task.getStatus() == null || task.getStatus() != 1) {
                throw new BusinessException("任务已停用，请先启用");
            }
        }
        PipelineRunner.Admission admission = "WORKFLOW".equals(triggerType)
                ? runner.admitWorkflow(task, workflowRunId, workflowNodeRunId, bizDate)
                : new PipelineRunner.Admission(
                        runner.createInstance(task, triggerType, workflowRunId, workflowNodeRunId, bizDate), true);
        PipelineInstance instance = admission.instance();
        if (admission.newlyCreated()) {
            try {
                executor.execute(() -> runner.execute(task, instance));
            } catch (RejectedExecutionException e) {
                runner.rejectQueued(instance);
            }
        }
        return instance;
    }

    public PipelinePreflight preflight(String id, String bizDate) throws Exception {
        PipelineAuthContext.AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_PIPELINE_RUN);
        PipelineTask task = get(id);
        auth.checkGroup(ctx, task.getProjectGroupId(), "同步任务");
        if (!(engineFactory.get(task.getEngine()) instanceof DbSyncEngine dbSync)) {
            throw new BusinessException("当前预检支持 Hive DB-Sync 加载");
        }
        return dbSync.preflight(task, bizDate);
    }

    private void validate(PipelineTask task) {
        if (StrUtil.isBlank(task.getTaskName())) {
            throw new BusinessException("任务名称不能为空");
        }
        if (StrUtil.isBlank(task.getEngine())) {
            task.setEngine("DB_SYNC");
        }
        // 校验引擎受支持
        engineFactory.get(task.getEngine());
    }
}
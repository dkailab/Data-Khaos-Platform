package com.datakhaos.pipeline.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.pipeline.api.model.PipelineInstanceDto;
import com.datakhaos.pipeline.engine.EngineFactory;
import com.datakhaos.pipeline.engine.PipelineEngine;
import com.datakhaos.pipeline.entity.PipelineInstance;
import com.datakhaos.pipeline.entity.PipelineTask;
import com.datakhaos.pipeline.mapper.PipelineInstanceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管道执行器：创建实例、调用引擎执行、更新实例状态。
 * 阻塞式同步执行，由调用方提交到线程池运行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineRunner {

    private final PipelineInstanceMapper instanceMapper;
    private final EngineFactory engineFactory;
    private final JdbcTemplate jdbc;

    public record Admission(PipelineInstance instance, boolean newlyCreated) {}

    /** Admission and recovery serialize on the workflow row, including delayed remote submissions. */
    @Transactional
    public Admission admitWorkflow(PipelineTask task, String runId, String nodeId, String bizDate) {
        if (StrUtil.hasBlank(runId, nodeId)) {
            throw new BusinessException("工作流管道需要运行及节点 ID");
        }
        List<String> state = jdbc.queryForList("SELECT run_status FROM workflow_run WHERE id = ? FOR UPDATE",
                String.class, runId);
        if (!state.equals(List.of("RUNNING"))) {
            throw new BusinessException("工作流已停止受理节点");
        }
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM workflow_node_run
                WHERE run_id = ? AND id = ? AND node_type = 'PIPELINE' AND status = 'RUNNING'
                """, Integer.class, runId, nodeId) != 1) {
            throw new BusinessException("管道节点不在执行状态");
        }
        PipelineInstance prior = instanceMapper.selectOne(Wrappers.<PipelineInstance>lambdaQuery()
                .eq(PipelineInstance::getWorkflowRunId, runId)
                .eq(PipelineInstance::getWorkflowNodeRunId, nodeId)
                .in(PipelineInstance::getStatus, 0, 1, PipelineInstanceDto.COMMIT_UNCONFIRMED)
                .orderByDesc(PipelineInstance::getId)
                .last("LIMIT 1"));
        return prior == null
                ? new Admission(createInstance(task, "WORKFLOW", runId, nodeId, bizDate), true)
                : new Admission(prior, false);
    }

    public void rejectQueued(PipelineInstance instance) {
        finish(instance, 2, 0, "管道队列无法受理任务");
    }

    /** 创建运行实例 */
    public PipelineInstance createInstance(PipelineTask task, String triggerType) {
        return createInstance(task, triggerType, null, null, null);
    }

    /** 创建运行实例（带工作流关联ID与业务日期） */
    public PipelineInstance createInstance(PipelineTask task, String triggerType,
                                          String workflowRunId, String workflowNodeRunId, String bizDate) {
        PipelineInstance instance = newInstance(task, triggerType, bizDate);
        instance.setWorkflowRunId(workflowRunId);
        instance.setWorkflowNodeRunId(workflowNodeRunId);
        instanceMapper.insert(instance);
        return instance;
    }

    public PipelineInstance createWorkspaceInstance(PipelineTask task, String bizDate, String userId,
                                                    String requestId, String activeKey) {
        PipelineInstance instance = newInstance(task, "MANUAL", bizDate);
        instance.setWorkspaceUserId(userId);
        instance.setWorkspaceRequestId(requestId);
        instance.setWorkspaceActiveKey(activeKey);
        instanceMapper.insert(instance);
        return instance;
    }

    private PipelineInstance newInstance(PipelineTask task, String triggerType, String bizDate) {
        PipelineInstance instance = new PipelineInstance();
        instance.setTaskId(task.getId());
        instance.setProjectGroupId(StrUtil.trimToNull(task.getProjectGroupId()));
        instance.setEngine(task.getEngine());
        instance.setTriggerType(triggerType);
        instance.setStatus(0);
        instance.setStartTime(LocalDateTime.now());
        // 运行证据：业务日期 + 实际写入的目标表与策略（取已授权的任务定义）
        instance.setBizDate(bizDate);
        instance.setTargetTable(task.getTargetTable());
        instance.setTargetDatasourceId(task.getTargetDsId());
        instance.setWriteMode(StrUtil.blankToDefault(task.getWriteMode(), "APPEND"));
        return instance;
    }

    /** 同步执行任务（含实例状态维护） */
    public void execute(PipelineTask task, PipelineInstance instance) {
        try {
            PipelineEngine engine = engineFactory.get(task.getEngine());
            if (!engine.available()) {
                throw new IllegalStateException("引擎 " + engine.name() + " 当前不可用，请检查引擎安装。备用引擎请选 DB_SYNC");
            }
            int rows = engine.execute(task, instance);
            finish(instance, 1, rows, null);
            log.info("管道任务 {} 执行成功，影响 {} 行", task.getTaskName(), rows);
        } catch (PipelineEngine.CommitUnconfirmedException e) {
            log.warn("管道任务提交结果待核查: taskId={}, instanceId={}", task.getId(), instance.getId(), e);
            finish(instance, PipelineInstanceDto.COMMIT_UNCONFIRMED, 0, e.getMessage());
        } catch (Exception e) {
            log.warn("管道任务 {} 执行失败: {}", task.getTaskName(), e.getMessage());
            finish(instance, 2, 0, e.getMessage());
        }
    }

    public void finishWorkspace(PipelineInstance instance, int status, int rows, String errorMessage) {
        LocalDateTime ended = LocalDateTime.now();
        instanceMapper.update(null, Wrappers.<PipelineInstance>lambdaUpdate()
                .eq(PipelineInstance::getId, instance.getId())
                .eq(PipelineInstance::getWorkspaceActiveKey, instance.getWorkspaceActiveKey())
                .eq(PipelineInstance::getStatus, 0)
                .set(PipelineInstance::getStatus, status)
                .set(PipelineInstance::getRows, (long) rows)
                .set(PipelineInstance::getEndTime, ended)
                .set(PipelineInstance::getTargetDatabaseName, instance.getTargetDatabaseName())
                .set(PipelineInstance::getPublishedAt, instance.getPublishedAt())
                .set(PipelineInstance::getMaterializationGeneration, instance.getMaterializationGeneration())
                .set(PipelineInstance::getDurationMs, Duration.between(instance.getStartTime(), ended).toMillis())
                .set(PipelineInstance::getErrorMessage, errorMessage)
                .set(PipelineInstance::getWorkspaceActiveKey, null));
    }

    private void finish(PipelineInstance instance, int status, int rows, String errorMessage) {
        instance.setStatus(status);
        instance.setEndTime(LocalDateTime.now());
        instance.setDurationMs(Duration.between(instance.getStartTime(), instance.getEndTime()).toMillis());
        long r = rows < 0 ? 0 : rows;
        instance.setRows(r);
        instance.setErrorMessage(errorMessage);
        instanceMapper.updateById(instance);
    }
}
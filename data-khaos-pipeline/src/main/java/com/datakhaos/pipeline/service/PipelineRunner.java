package com.datakhaos.pipeline.service;

import com.datakhaos.pipeline.engine.EngineFactory;
import com.datakhaos.pipeline.engine.PipelineEngine;
import com.datakhaos.pipeline.entity.PipelineInstance;
import com.datakhaos.pipeline.entity.PipelineTask;
import com.datakhaos.pipeline.mapper.PipelineInstanceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

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

    /** 创建运行实例 */
    public PipelineInstance createInstance(PipelineTask task, String triggerType) {
        return createInstance(task, triggerType, null, null);
    }

    /** 创建运行实例（带工作流关联ID） */
    public PipelineInstance createInstance(PipelineTask task, String triggerType,
                                          String workflowRunId, String workflowNodeRunId) {
        PipelineInstance instance = new PipelineInstance();
        instance.setTaskId(task.getId());
        instance.setEngine(task.getEngine());
        instance.setTriggerType(triggerType);
        instance.setStatus(0);
        instance.setStartTime(LocalDateTime.now());
        instance.setWorkflowRunId(workflowRunId);
        instance.setWorkflowNodeRunId(workflowNodeRunId);
        instanceMapper.insert(instance);
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
        } catch (Exception e) {
            log.warn("管道任务 {} 执行失败: {}", task.getTaskName(), e.getMessage());
            finish(instance, 2, 0, e.getMessage());
        }
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
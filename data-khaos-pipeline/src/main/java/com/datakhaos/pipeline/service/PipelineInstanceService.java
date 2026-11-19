package com.datakhaos.pipeline.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.pipeline.entity.PipelineInstance;
import com.datakhaos.pipeline.mapper.PipelineInstanceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管道执行实例服务
 */
@Service
@RequiredArgsConstructor
public class PipelineInstanceService {

    private final PipelineInstanceMapper mapper;

    public PageResult<PipelineInstance> page(long current, long size, String taskId, Integer status) {
        LambdaQueryWrapper<PipelineInstance> wrapper = new LambdaQueryWrapper<PipelineInstance>()
                .eq(StrUtil.isNotBlank(taskId), PipelineInstance::getTaskId, taskId)
                .eq(status != null, PipelineInstance::getStatus, status)
                .orderByDesc(PipelineInstance::getStartTime);
        Page<PipelineInstance> page = mapper.selectPage(new Page<>(current, size), wrapper);
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    public PipelineInstance get(String id) {
        PipelineInstance instance = mapper.selectById(id);
        if (instance == null) {
            throw new BusinessException("执行实例不存在: " + id);
        }
        return instance;
    }

    public List<PipelineInstance> byTask(String taskId) {
        return mapper.selectList(new LambdaQueryWrapper<PipelineInstance>()
                .eq(PipelineInstance::getTaskId, taskId)
                .orderByDesc(PipelineInstance::getStartTime)
                .last("LIMIT 20"));
    }

    /** 按工作流节点运行ID反查实例（用于超时后确认提交结果，避免重复触发） */
    public List<PipelineInstance> byWorkflowNode(String workflowRunId, String workflowNodeRunId) {
        if (cn.hutool.core.util.StrUtil.isBlank(workflowRunId) || cn.hutool.core.util.StrUtil.isBlank(workflowNodeRunId)) {
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<PipelineInstance>()
                .eq(PipelineInstance::getWorkflowRunId, workflowRunId)
                .eq(PipelineInstance::getWorkflowNodeRunId, workflowNodeRunId)
                .orderByDesc(PipelineInstance::getStartTime));
    }
}
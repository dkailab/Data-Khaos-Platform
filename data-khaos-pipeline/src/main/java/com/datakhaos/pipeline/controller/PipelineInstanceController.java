package com.datakhaos.pipeline.controller;

import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.pipeline.entity.PipelineInstance;
import com.datakhaos.pipeline.service.PipelineInstanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 数据管道执行实例接口
 */
@Tag(name = "数据管道实例")
@RestController
@RequestMapping("/api/pipeline/instance")
@RequiredArgsConstructor
public class PipelineInstanceController {

    private final PipelineInstanceService instanceService;

    @Operation(summary = "分页查询执行实例")
    @GetMapping("/page")
    public R<PageResult<PipelineInstance>> page(@RequestParam(defaultValue = "1") long current,
                                                @RequestParam(defaultValue = "10") long size,
                                                @RequestParam(required = false) String taskId,
                                                @RequestParam(required = false) Integer status) {
        return R.ok(instanceService.page(current, size, taskId, status));
    }

    @Operation(summary = "实例详情")
    @GetMapping("/{id}")
    public R<PipelineInstance> get(@PathVariable String id) {
        return R.ok(instanceService.get(id));
    }

    @Operation(summary = "某任务的历史实例")
    @GetMapping("/task/{taskId}")
    public R<List<PipelineInstance>> byTask(@PathVariable String taskId) {
        return R.ok(instanceService.byTask(taskId));
    }

    @Operation(summary = "按工作流节点运行ID反查实例（超时确认提交结果）")
    @GetMapping("/by-node")
    public R<List<PipelineInstance>> byWorkflowNode(@RequestParam String runId,
                                                    @RequestParam String nodeRunId) {
        if (!MetadataHolder.isSystemCaller()) {
            throw new com.datakhaos.common.exception.BusinessException("按节点反查实例需要系统身份");
        }
        return R.ok(instanceService.byWorkflowNode(runId, nodeRunId));
    }
}
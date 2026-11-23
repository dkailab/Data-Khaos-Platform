package com.datakhaos.pipeline.controller;

import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.pipeline.dto.PipelinePreflight;
import com.datakhaos.pipeline.entity.PipelineInstance;
import com.datakhaos.pipeline.entity.PipelineTask;
import com.datakhaos.pipeline.service.PipelineTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据管道任务接口（管理面）
 */
@Tag(name = "数据管道任务")
@RestController
@RequestMapping("/api/pipeline/task")
@RequiredArgsConstructor
public class PipelineTaskController {

    private final PipelineTaskService taskService;

    @Operation(summary = "分页查询管道任务")
    @GetMapping("/page")
    public R<PageResult<PipelineTask>> page(@RequestParam(defaultValue = "1") long current,
                                            @RequestParam(defaultValue = "10") long size,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String engine) {
        return R.ok(taskService.page(current, size, keyword, engine));
    }

    @Operation(summary = "任务详情")
    @GetMapping("/{id}")
    public R<PipelineTask> get(@PathVariable String id) {
        return R.ok(taskService.get(id));
    }

    @Operation(summary = "新增管道任务")
    @PostMapping
    public R<Void> create(@RequestBody PipelineTask task) {
        taskService.create(task);
        return R.ok();
    }

    @Operation(summary = "修改管道任务")
    @PutMapping
    public R<Void> update(@RequestBody PipelineTask task) {
        taskService.update(task);
        return R.ok();
    }

    @Operation(summary = "删除管道任务")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        taskService.delete(id);
        return R.ok();
    }

    @Operation(summary = "触发运行")
    @PostMapping("/{id}/run")
    public R<PipelineInstance> run(@PathVariable String id,
                                   @RequestParam(defaultValue = "MANUAL") String triggerType,
                                   @RequestParam(required = false) String workflowRunId,
                                   @RequestParam(required = false) String workflowNodeRunId,
                                   @RequestParam(required = false) String bizDate) {
        return R.ok(taskService.run(id, triggerType, workflowRunId, workflowNodeRunId, bizDate));
    }

    @Operation(summary = "预检已保存的 Hive 加载任务")
    @PostMapping("/{id}/preflight")
    public R<PipelinePreflight> preflight(@PathVariable String id,
                                          @RequestBody(required = false) PreflightInput input) throws Exception {
        return R.ok(taskService.preflight(id, input == null ? null : input.bizDate()));
    }

    public record PreflightInput(String bizDate) {}

    @Operation(summary = "引擎列表（可扩展）")
    @GetMapping("/engines")
    public R<List<Map<String, Object>>> engines() {
        return R.ok(taskService.engines());
    }
}
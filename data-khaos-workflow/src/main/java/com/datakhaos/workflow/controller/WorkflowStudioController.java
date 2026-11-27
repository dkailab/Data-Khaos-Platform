package com.datakhaos.workflow.controller;

import com.datakhaos.common.model.R;
import com.datakhaos.workflow.dto.NodeDebugResult;
import com.datakhaos.workflow.dto.WorkflowDependencyGraph;
import com.datakhaos.workflow.dto.WorkflowPublishResult;
import com.datakhaos.workflow.dto.WorkflowTaskView;
import com.datakhaos.workflow.dto.WorkflowUpstreamRequest;
import com.datakhaos.workflow.dto.WorkflowUpstreamView;
import com.datakhaos.workflow.entity.WorkflowDef;
import com.datakhaos.workflow.service.WorkflowNodeDebugService;
import com.datakhaos.workflow.service.WorkflowPublishService;
import com.datakhaos.workflow.service.WorkflowService;
import com.datakhaos.workflow.service.WorkflowUpstreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据开发工作台接口：发布/下线、任务列表、单节点调试、跨任务流依赖、下拉选项。
 */
@RestController
@RequestMapping("/api/workflow")
@RequiredArgsConstructor
public class WorkflowStudioController {

    private final WorkflowPublishService publishService;
    private final WorkflowUpstreamService upstreamService;
    private final WorkflowNodeDebugService debugService;
    private final WorkflowService workflowService;

    /** 任务流下拉选项（嵌套任务流、上游依赖选择使用） */
    @GetMapping("/options")
    public R<List<WorkflowDef>> options(@RequestParam(required = false) String projectId) {
        return R.ok(workflowService.options(projectId));
    }

    /** 发布：固化版本快照并绑定，发布后可参与调度 */
    @PostMapping("/{id}/publish")
    public R<WorkflowPublishResult> publish(@PathVariable String id) {
        return R.ok(publishService.publish(id));
    }

    /** 下线：停止调度，保留历史版本 */
    @PostMapping("/{id}/offline")
    public R<WorkflowPublishResult> offline(@PathVariable String id) {
        return R.ok(publishService.offline(id));
    }

    /** 任务列表：节点定义 + 最近一次运行状态 */
    @GetMapping("/{id}/tasks")
    public R<List<WorkflowTaskView>> tasks(@PathVariable String id) {
        return R.ok(workflowService.tasks(id));
    }

    /** 单节点调试：同步执行，不产生运行实例 */
    @PostMapping("/{id}/nodes/{code}/debug")
    public R<NodeDebugResult> debug(@PathVariable String id, @PathVariable String code,
                                    @RequestBody(required = false) Map<String, Object> params) {
        return R.ok(debugService.debug(id, code, params));
    }

    /** 上游任务流依赖列表 */
    @GetMapping("/{id}/upstreams")
    public R<List<WorkflowUpstreamView>> upstreams(@PathVariable String id) {
        return R.ok(upstreamService.list(id));
    }

    /** 整体替换上游依赖 */
    @PutMapping("/{id}/upstreams")
    public R<List<WorkflowUpstreamView>> saveUpstreams(@PathVariable String id,
                                                       @RequestBody List<WorkflowUpstreamRequest> requests) {
        return R.ok(upstreamService.save(id, requests));
    }

    /** 跨任务流依赖图 */
    @GetMapping("/{id}/upstreams/graph")
    public R<WorkflowDependencyGraph> upstreamGraph(@PathVariable String id,
                                                    @RequestParam(required = false) Integer depth) {
        return R.ok(upstreamService.graph(id, depth));
    }
}

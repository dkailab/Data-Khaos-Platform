package com.datakhaos.workflow.controller;

import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.common.model.R;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.workflow.dto.BackfillRequest;
import com.datakhaos.workflow.dto.BackfillResult;
import com.datakhaos.workflow.dto.RunAccept;
import com.datakhaos.workflow.dto.RunRequest;
import com.datakhaos.workflow.dto.WorkflowGraphRequest;
import com.datakhaos.workflow.entity.WorkflowDef;
import com.datakhaos.workflow.entity.WorkflowNodeRun;
import com.datakhaos.workflow.entity.WorkflowRun;
import com.datakhaos.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工作流编排 REST 接口。
 */
@RestController
@RequestMapping("/api/workflow")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    /** 保存工作流图（新增/整体更新） */
    @PostMapping
    public R<WorkflowDef> save(@RequestBody WorkflowGraphRequest request) {
        return R.ok(workflowService.saveGraph(request));
    }

    /** 工作流详情（含节点与连线） */
    @GetMapping("/{id}")
    public R<WorkflowGraphRequest> detail(@PathVariable String id) {
        return R.ok(workflowService.detail(id));
    }

    /** 分页列表 */
    @GetMapping("/page")
    public R<PageResult<WorkflowDef>> page(@RequestParam(defaultValue = "1") long current,
                                           @RequestParam(defaultValue = "10") long size,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Integer status) {
        return R.ok(workflowService.page(current, size, keyword, status));
    }

    /** 删除工作流 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        workflowService.delete(id);
        return R.ok();
    }

    /** 更新状态 0:禁用 1:启用 */
    @PutMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable String id,
                                @RequestParam Integer status) {
        workflowService.updateStatus(id, status);
        return R.ok();
    }

    /**
     * 触发一次手动运行（旧入口，保留兼容；无 requestId/bizDate 时 data 仍为 String）。
     * 携带 requestId/bizDate/scheduledAt 时走 PR2A 受理，data 为 RunAccept。
     */
    @PostMapping("/{id}/trigger")
    public R<Object> trigger(@PathVariable String id,
                             @RequestBody(required = false) Map<String, Object> params,
                             @RequestParam(defaultValue = "MANUAL") String triggerType,
                             @RequestParam(required = false) String scheduleJobId,
                             @RequestParam(required = false) String scheduleLogId,
                             @RequestParam(required = false) String requestId,
                             @RequestParam(required = false) String bizDate,
                             @RequestParam(required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime scheduledAt) {
        if (!"MANUAL".equals(triggerType) || scheduleJobId != null || scheduleLogId != null
                || requestId != null || bizDate != null || scheduledAt != null) {
            if (!MetadataHolder.isSystemCaller()) {
                throw new com.datakhaos.common.exception.BusinessException("内部工作流触发需要系统身份");
            }
        }
        // 携带业务日期/幂等键时走 PR2A 受理入口；否则保持旧行为
        if (requestId != null || bizDate != null || scheduledAt != null) {
            // 拒绝语义随 HTTP 200 结构化返回（state=REJECTED + rejectCode），便于调用方区分处理
            RunAccept accept = workflowService.triggerWithRequest(id, params, triggerType,
                    scheduleJobId, scheduleLogId, requestId, bizDate, scheduledAt);
            return R.ok(accept);
        }
        String runId = "MANUAL".equals(triggerType) && scheduleJobId == null && scheduleLogId == null
                ? workflowService.triggerManual(id, params)
                : workflowService.trigger(id, params, triggerType, scheduleJobId, scheduleLogId);
        return R.ok("触发成功", runId);
    }

    /** 带业务日期的手动运行（幂等键由调用方给出） */
    @PostMapping("/{id}/runs")
    public R<RunAccept> run(@PathVariable String id, @RequestBody RunRequest request) {
        return R.ok(workflowService.runByRequest(id, request));
    }

    /** 区间补数（含首尾，最多 31 天） */
    @PostMapping("/{id}/backfills")
    public R<BackfillResult> backfill(@PathVariable String id, @RequestBody BackfillRequest request) {
        return R.ok(workflowService.backfill(id, request));
    }

    /** 运行实例分页列表 */
    @GetMapping("/run/page")
    public R<List<WorkflowRun>> runPage(@RequestParam(required = false) String wfId,
                                        @RequestParam(required = false) String bizDate,
                                        @RequestParam(defaultValue = "1") long current,
                                        @RequestParam(defaultValue = "10") long size) {
        return R.ok(workflowService.runPage(wfId, current, size, bizDate));
    }

    /** 触发请求异常后按调度日志确认是否已受理。 */
    @GetMapping("/{id}/run/by-schedule-log/{scheduleLogId}")
    public R<WorkflowRun> byScheduleLog(@PathVariable String id, @PathVariable String scheduleLogId) {
        if (!MetadataHolder.isSystemCaller()) {
            throw new com.datakhaos.common.exception.BusinessException("按调度日志反查运行需要系统身份");
        }
        return R.ok(workflowService.latestRunByScheduleLog(id, scheduleLogId));
    }

    /** 运行实例详情 */
    @GetMapping("/run/{runId}")
    public R<WorkflowRun> runDetail(@PathVariable String runId) {
        return R.ok(workflowService.runDetail(runId));
    }

    /** 某次运行的节点执行记录 */
    @GetMapping("/run/{runId}/nodes")
    public R<List<WorkflowNodeRun>> runNodes(@PathVariable String runId) {
        return R.ok(workflowService.nodeRuns(runId));
    }
}
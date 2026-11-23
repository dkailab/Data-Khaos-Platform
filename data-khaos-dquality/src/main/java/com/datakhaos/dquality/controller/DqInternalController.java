package com.datakhaos.dquality.controller;

import com.datakhaos.common.model.R;
import com.datakhaos.dquality.entity.DqSnapshot;
import com.datakhaos.dquality.service.SnapshotService;
import com.datakhaos.dquality.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 数据质量内部接口（供调度系统等微服务间调用，不经过网关鉴权）。
 * 调用方需以系统管理员身份透传用户头（X-User-Id / X-Roles: SUPER_ADMIN），
 * 由 {@code MetadataContextFilter} 注入上下文，此处不再重复校验权限。
 */
@Tag(name = "数据质量-内部接口")
@RestController
@RequestMapping("/api/dquality/internal")
@RequiredArgsConstructor
public class DqInternalController {

    private final TaskService taskService;
    private final SnapshotService snapshotService;

    @Operation(summary = "内部触发质量任务（供调度系统周期稽核）")
    @PostMapping("/task/{id}/run")
    public R<Void> runTask(@PathVariable String id,
                           @RequestParam(defaultValue = "SCHEDULE") String triggerType) {
        taskService.runInternal(id, triggerType);
        return R.ok();
    }

    @Operation(summary = "内部触发质量任务并返回快照（供工作流 QUALITY 节点门禁）")
    @PostMapping("/task/{id}/audit")
    public R<DqSnapshot> auditTask(@PathVariable String id,
                                   @RequestParam(defaultValue = "WORKFLOW") String triggerType,
                                   @RequestParam(required = false) String runId,
                                   @RequestParam(required = false) String nodeRunId,
                                   @RequestParam(required = false) String bizDate) {
        return R.ok(taskService.runInternal(id, triggerType, runId, nodeRunId, bizDate));
    }

    @Operation(summary = "按ID查询快照（供元数据聚合运行证据）")
    @GetMapping("/snapshot/{id}")
    public R<DqSnapshot> snapshot(@PathVariable String id) {
        DqSnapshot snapshot = snapshotService.getInternal(id);
        if (snapshot == null) {
            throw new com.datakhaos.common.exception.BusinessException("快照不存在: " + id);
        }
        return R.ok(snapshot);
    }

    @Operation(summary = "查询某表某业务日最近一次稽核快照（供数据地图回查质量证据）")
    @GetMapping("/snapshot/latest")
    public R<DqSnapshot> latestSnapshot(@RequestParam(required = false) String tableName,
                                        @RequestParam(required = false) String bizDate,
                                        @RequestParam(required = false) String conclusion) {
        return R.ok(snapshotService.latestInternal(tableName, bizDate, conclusion));
    }
}

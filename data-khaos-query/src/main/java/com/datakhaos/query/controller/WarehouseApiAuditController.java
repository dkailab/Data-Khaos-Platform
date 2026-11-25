package com.datakhaos.query.controller;

import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.query.service.WarehouseApiAuditService;
import com.datakhaos.query.service.WarehouseApiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数仓 API 调用审计查询。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "数仓 API 调用审计")
public class WarehouseApiAuditController {
    private static final String BASE = "/api/query/warehouse/v1/projects/{projectId}/audits";

    private final WarehouseApiService apis;
    private final WarehouseApiAuditService audits;

    @GetMapping(BASE)
    @Operation(summary = "按项目与日期分页查询调用记录")
    public R<PageResult<WarehouseApiAuditService.View>> page(@PathVariable String projectId,
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        apis.requireProject(projectId, true);
        return R.ok(audits.page(projectId, appId, requestId, from, to, current, size));
    }
}

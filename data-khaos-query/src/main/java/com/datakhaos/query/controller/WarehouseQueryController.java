package com.datakhaos.query.controller;

import com.datakhaos.common.model.R;
import com.datakhaos.query.dto.MaterializationStatus;
import com.datakhaos.query.dto.OrderRegionGmv;
import com.datakhaos.query.service.MaterializationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "数仓查询")
@RestController
@RequiredArgsConstructor
public class WarehouseQueryController {

    private final MaterializationQueryService materializations;

    @Operation(summary = "检查受管物化分区发布是否就绪")
    @GetMapping("/api/query/warehouse/v1/materializations/{assetId}/status")
    public R<MaterializationStatus> materializationStatus(@PathVariable String assetId, @RequestParam String bizDate,
                                                         HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return R.ok(materializations.status(assetId, bizDate));
    }

    @Operation(summary = "查询指定业务日的已发布地区成交额")
    @GetMapping("/api/data/v1/order-region-gmv")
    public R<OrderRegionGmv> orderRegionGmv(@RequestParam String bizDate, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return R.ok(materializations.orderRegionGmv(bizDate));
    }
}

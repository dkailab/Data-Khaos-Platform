package com.datakhaos.metadata.controller;

import com.datakhaos.common.model.R;
import com.datakhaos.metadata.dto.AssetEvidenceDto;
import com.datakhaos.metadata.dto.RunEvidenceDto;
import com.datakhaos.metadata.service.RunEvidenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PR3 运行证据与数据地图追溯接口。
 *
 * <p>复用元数据的资产 ID 与血缘能力：结构血缘走 {@code /api/meta/lineage}，
 * 本控制器只提供「哪个运行生产了这个分区、质量结论是什么」的证据回查。</p>
 */
@Tag(name = "数据地图-运行证据")
@RestController
@RequestMapping("/api/meta")
@RequiredArgsConstructor
public class RunEvidenceController {

    private final RunEvidenceService runEvidenceService;

    @Operation(summary = "运行证据：runId → 节点 → 管道实例/质量快照 → 输入输出资产")
    @GetMapping("/run-evidence/{runId}")
    public R<RunEvidenceDto> runEvidence(@PathVariable String runId) {
        return R.ok(runEvidenceService.runEvidence(runId));
    }

    @Operation(summary = "资产证据：从数据地图的表回查某业务日分区的发布与质量证据")
    @GetMapping("/assets/{assetId}/evidence")
    public R<AssetEvidenceDto> assetEvidence(@PathVariable String assetId,
                                            @RequestParam(required = false) String bizDate) {
        return R.ok(runEvidenceService.assetEvidence(assetId, bizDate));
    }
}

package com.datakhaos.workflow.controller;

import com.datakhaos.common.model.R;
import com.datakhaos.workflow.service.FlinkLineageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 实时作业版本的表级血缘：查询与手动刷新。 */
@Tag(name = "实时血缘")
@RestController
@RequestMapping("/api/workflow/flink/projects/{projectId}/assets/{id}/versions/{version}/lineage")
@RequiredArgsConstructor
public class FlinkLineageController {

    private final FlinkLineageService lineage;

    @Operation(summary = "查询作业版本血缘（首次自动解析）")
    @GetMapping
    public R<FlinkLineageService.View> view(@PathVariable String projectId, @PathVariable String id,
                                            @PathVariable int version) {
        return R.ok(lineage.view(projectId, id, version));
    }

    @Operation(summary = "重新解析作业版本血缘")
    @PostMapping("/refresh")
    public R<FlinkLineageService.View> refresh(@PathVariable String projectId, @PathVariable String id,
                                               @PathVariable int version) {
        return R.ok(lineage.refresh(projectId, id, version));
    }
}

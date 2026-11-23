package com.datakhaos.workflow.controller;

import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.workflow.entity.FlinkAsset;
import com.datakhaos.workflow.entity.FlinkAsset.Kind;
import com.datakhaos.workflow.flink.FlinkDefinition;
import com.datakhaos.workflow.flink.FlinkVersionView;
import com.datakhaos.workflow.flink.FlinkVersionView.Revision;
import com.datakhaos.workflow.service.FlinkStudioService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workflow/flink/projects/{projectId}/assets")
@RequiredArgsConstructor
public class FlinkStudioController {
    private final FlinkStudioService studio;

    @GetMapping
    public R<PageResult<FlinkAsset>> page(@PathVariable String projectId, @RequestParam Kind kind,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "15") long size) {
        return R.ok(studio.page(projectId, kind, keyword, current, size));
    }

    @PostMapping
    public R<FlinkVersionView> create(@PathVariable String projectId, @RequestBody CreateRequest request) {
        return R.ok(studio.create(projectId, request.kind(), request.code(), request.name(), request.note(), request.definition()));
    }

    @PutMapping("/{id}/draft")
    public R<FlinkVersionView> save(@PathVariable String projectId, @PathVariable String id, @RequestBody EditRequest request) {
        return R.ok(studio.save(projectId, id, request.expectedVersion(), request.name(), request.note(), request.definition()));
    }

    @GetMapping("/{id}/versions")
    public R<PageResult<Revision>> history(@PathVariable String projectId, @PathVariable String id,
                                           @RequestParam(defaultValue = "1") long current,
                                           @RequestParam(defaultValue = "15") long size) {
        return R.ok(studio.history(projectId, id, current, size));
    }

    @GetMapping("/{id}/versions/{version}")
    public R<FlinkVersionView> detail(@PathVariable String projectId, @PathVariable String id, @PathVariable int version,
                                      HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return R.ok(studio.detail(projectId, id, version));
    }

    @PostMapping("/{id}/versions/{version}/restore")
    public R<FlinkVersionView> restore(@PathVariable String projectId, @PathVariable String id, @PathVariable int version,
                                       @RequestBody RestoreRequest request) {
        return R.ok(studio.restore(projectId, id, version, request.expectedVersion(), request.note()));
    }

    @GetMapping("/{id}/versions/{version}/sql")
    public R<String> exportSql(@PathVariable String projectId, @PathVariable String id, @PathVariable int version,
                               HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return R.ok(studio.exportSql(projectId, id, version));
    }

    public record CreateRequest(Kind kind, String code, String name, String note, FlinkDefinition definition) {}

    public record EditRequest(Integer expectedVersion, String name, String note, FlinkDefinition definition) {}

    public record RestoreRequest(Integer expectedVersion, String note) {}
}

package com.datakhaos.workflow.controller;

import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.workflow.dto.WorkflowRevisionView;
import com.datakhaos.workflow.entity.WorkflowDef;
import com.datakhaos.workflow.service.WorkflowRevisionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workflow/{id}/revisions")
@RequiredArgsConstructor
public class WorkflowRevisionController {
    private final WorkflowRevisionService revisions;

    @GetMapping
    public R<PageResult<WorkflowRevisionView>> page(@PathVariable String id,
                                                     @RequestParam(defaultValue = "1") long current,
                                                     @RequestParam(defaultValue = "10") long size) {
        return R.ok(revisions.page(id, current, size));
    }

    @PostMapping
    public R<WorkflowRevisionView> create(@PathVariable String id, @RequestBody RevisionRequest request) {
        return R.ok(revisions.create(id, request.note()));
    }

    @PostMapping("/{revisionId}/draft")
    public R<WorkflowDef> createDraft(@PathVariable String id, @PathVariable String revisionId,
                                      @RequestBody DraftRequest request) {
        return R.ok(revisions.createDraft(id, revisionId, request.name()));
    }

    public record RevisionRequest(String note) {}

    public record DraftRequest(String name) {}
}

package com.datakhaos.pipeline.workspace;

import com.datakhaos.common.model.R;
import com.datakhaos.pipeline.api.model.WorkspacePipelineRunReceipt;
import com.datakhaos.pipeline.api.model.WorkspacePipelineRunRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pipeline/workspaces/{projectId}/tasks/{taskId}/runs")
@RequiredArgsConstructor
class WorkspacePipelineRunController {
    private final WorkspacePipelineRunService service;

    @PostMapping
    public R<WorkspacePipelineRunReceipt> submit(@PathVariable String projectId, @PathVariable String taskId,
                                                 @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String token,
                                                 @RequestBody WorkspacePipelineRunRequest request) {
        return R.ok(service.submit(projectId, taskId, token, request));
    }
}

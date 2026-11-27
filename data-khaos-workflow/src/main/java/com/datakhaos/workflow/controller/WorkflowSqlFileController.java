package com.datakhaos.workflow.controller;

import com.datakhaos.common.model.R;
import com.datakhaos.workflow.dto.WorkflowSqlFileRequest;
import com.datakhaos.workflow.entity.WorkflowSqlFile;
import com.datakhaos.workflow.service.WorkflowSqlFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SQL 文件资源接口：工作台左侧资源树与节点「外部任务」选择共用。
 */
@RestController
@RequestMapping("/api/workflow/sql-file")
@RequiredArgsConstructor
public class WorkflowSqlFileController {

    private final WorkflowSqlFileService sqlFileService;

    @GetMapping("/list")
    public R<List<WorkflowSqlFile>> list(@RequestParam(required = false) String projectId,
                                         @RequestParam(required = false) String keyword) {
        return R.ok(sqlFileService.list(projectId, keyword));
    }

    @GetMapping("/{id}")
    public R<WorkflowSqlFile> get(@PathVariable String id) {
        return R.ok(sqlFileService.get(id));
    }

    @PostMapping
    public R<WorkflowSqlFile> save(@RequestBody WorkflowSqlFileRequest request) {
        return R.ok(sqlFileService.save(request));
    }

    @PostMapping("/{id}/publish")
    public R<WorkflowSqlFile> publish(@PathVariable String id) {
        return R.ok(sqlFileService.publish(id));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        sqlFileService.delete(id);
        return R.ok();
    }
}

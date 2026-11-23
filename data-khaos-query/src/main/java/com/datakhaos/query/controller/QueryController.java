package com.datakhaos.query.controller;

import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.datasource.api.model.ColumnInfo;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.query.dto.QueryExecuteRequest;
import com.datakhaos.query.entity.QueryHistory;
import com.datakhaos.query.service.QueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * SQL 查询平台接口
 */
@Tag(name = "SQL 查询平台")
@RestController
@RequestMapping("/api/query")
@RequiredArgsConstructor
public class QueryController {

    private final QueryService queryService;

    @Operation(summary = "执行 SQL（自动审核 + 可选表权限校验）")
    @PostMapping("/execute")
    public R<QueryResult> execute(@RequestBody QueryExecuteRequest request) {
        return R.ok(queryService.execute(request));
    }

    @Operation(summary = "导出查询结果为 CSV")
    @PostMapping("/export")
    public ResponseEntity<String> export(@RequestBody QueryExecuteRequest request) {
        QueryResult result = queryService.execute(request);
        String csv = toCsv(result);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=query_result.csv")
                .contentType(MediaType.TEXT_PLAIN)
                .body(csv);
    }

    @Operation(summary = "查询历史（分页）")
    @GetMapping("/history")
    public R<PageResult<QueryHistory>> history(@RequestParam(defaultValue = "1") long current,
                                               @RequestParam(defaultValue = "10") long size) {
        return R.ok(queryService.history(current, size));
    }

    @Operation(summary = "查询历史详情")
    @GetMapping("/history/{id}")
    public R<QueryHistory> historyDetail(@PathVariable String id) {
        return R.ok(queryService.historyDetail(id));
    }

    /** QueryResult 转 CSV（含 UTF-8 BOM，避免 Excel 中文乱码） */
    private String toCsv(QueryResult result) {
        StringBuilder sb = new StringBuilder("﻿");
        if (result.getColumns() != null) {
            sb.append(result.getColumns().stream()
                    .map(ColumnInfo::getColumnName)
                    .map(this::escape)
                    .collect(Collectors.joining(",")));
            sb.append("\r\n");
        }
        if (result.getRows() != null) {
            for (Map<String, Object> row : result.getRows()) {
                sb.append(result.getColumns().stream()
                        .map(ColumnInfo::getColumnName)
                        .map(name -> String.valueOf(row.get(name)))
                        .map(this::escape)
                        .collect(Collectors.joining(",")));
                sb.append("\r\n");
            }
        }
        return sb.toString();
    }

    private String escape(String value) {
        String v = value == null ? "" : value;
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}

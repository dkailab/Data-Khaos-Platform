package com.datakhaos.query.controller;

import com.datakhaos.common.model.R;
import com.datakhaos.datasource.api.model.ColumnInfo;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.query.dto.SqlCompleteRequest;
import com.datakhaos.query.dto.SqlCompleteResult;
import com.datakhaos.query.dto.SqlDiagnoseResult;
import com.datakhaos.query.dto.SqlParseResult;
import com.datakhaos.query.service.OnesqlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * OneSQL 增强接口：SQL 补全、格式化、解析、元数据提示。
 * 为前端的 CodeMirror SQL 编辑器提供后端支持。
 */
@Tag(name = "OneSQL 增强")
@RestController
@RequestMapping("/api/query/onesql")
@RequiredArgsConstructor
public class OnesqlController {

    private final OnesqlService onesqlService;

    @Operation(summary = "SQL 补全建议")
    @PostMapping("/complete")
    public R<SqlCompleteResult> complete(@RequestBody SqlCompleteRequest request) {
        return R.ok(onesqlService.complete(request));
    }

    @Operation(summary = "SQL 格式化")
    @PostMapping("/format")
    public R<String> format(@RequestBody Map<String, String> body) {
        String sql = body.getOrDefault("sql", "");
        return R.ok(onesqlService.format(sql));
    }

    @Operation(summary = "SQL 解析（提取表名/列引用）")
    @PostMapping("/parse")
    public R<SqlParseResult> parse(@RequestBody Map<String, String> body) {
        String sql = body.getOrDefault("sql", "");
        return R.ok(onesqlService.parse(sql));
    }

    @Operation(summary = "获取数据源 Schema 提示（懒加载，仅表名）")
    @GetMapping("/hints")
    public R<Map<String, Object>> hints(@RequestParam String datasourceId,
                                        @RequestParam(required = false) String databaseName) {
        return R.ok(onesqlService.getSchemaHints(datasourceId, databaseName));
    }

    @Operation(summary = "懒加载获取单表字段")
    @GetMapping("/columns")
    public R<List<ColumnInfo>> columns(@RequestParam String datasourceId,
                                       @RequestParam(required = false) String databaseName,
                                       @RequestParam String table) {
        return R.ok(onesqlService.getTableColumns(datasourceId, databaseName, table));
    }

    @Operation(summary = "SQL 健康诊断（select */缺WHERE/全表扫描/JOIN无ON/隐式类型转换）")
    @PostMapping("/diagnose")
    public R<SqlDiagnoseResult> diagnose(@RequestBody Map<String, String> body) {
        return R.ok(onesqlService.diagnose(body.getOrDefault("sql", ""),
                body.getOrDefault("datasourceId", ""), body.getOrDefault("databaseName", "")));
    }

    @Operation(summary = "执行计划 EXPLAIN（访问类型/扫描行数/索引）")
    @PostMapping("/explain")
    public R<QueryResult> explain(@RequestBody Map<String, String> body) {
        return R.ok(onesqlService.explain(body.getOrDefault("datasourceId", ""),
                body.getOrDefault("databaseName", ""), body.getOrDefault("sql", "")));
    }
}

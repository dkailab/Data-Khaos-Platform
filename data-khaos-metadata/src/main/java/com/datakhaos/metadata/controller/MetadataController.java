package com.datakhaos.metadata.controller;

import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.metadata.entity.MetaColumn;
import com.datakhaos.metadata.entity.MetaDatabase;
import com.datakhaos.metadata.entity.MetaTable;
import com.datakhaos.metadata.entity.MetaTableLineage;
import com.datakhaos.metadata.service.MetadataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 元数据中心接口
 */
@Tag(name = "元数据中心")
@RestController
@RequestMapping("/api/meta")
@RequiredArgsConstructor
public class MetadataController {

    private final MetadataService metadataService;

    @Operation(summary = "全量同步数据源元数据")
    @PostMapping("/sync/{datasourceId}")
    public R<Void> sync(@PathVariable String datasourceId) {
        metadataService.sync(datasourceId);
        return R.ok();
    }

    @Operation(summary = "同步单库元数据")
    @PostMapping("/sync/{datasourceId}/{database}")
    public R<Void> syncDatabase(@PathVariable String datasourceId, @PathVariable String database) {
        metadataService.syncDatabase(datasourceId, database);
        return R.ok();
    }

    @Operation(summary = "同步单表字段")
    @PostMapping("/sync/{datasourceId}/{database}/{table}")
    public R<Void> syncTable(@PathVariable String datasourceId,
                             @PathVariable String database,
                             @PathVariable String table) {
        metadataService.syncTable(datasourceId, database, table);
        return R.ok();
    }

    @Operation(summary = "结构树（库 -> 表 -> 字段）")
    @GetMapping("/structure/{datasourceId}")
    public R<List<Map<String, Object>>> structure(@PathVariable String datasourceId) {
        return R.ok(metadataService.structure(datasourceId));
    }

    @Operation(summary = "已采集的数据库列表")
    @GetMapping("/database/list/{datasourceId}")
    public R<List<MetaDatabase>> databases(@PathVariable String datasourceId) {
        return R.ok(metadataService.databases(datasourceId));
    }

    @Operation(summary = "分页查询表")
    @GetMapping("/table/page")
    public R<PageResult<MetaTable>> tables(@RequestParam(defaultValue = "1") long current,
                                           @RequestParam(defaultValue = "10") long size,
                                           @RequestParam(required = false) String datasourceId,
                                           @RequestParam(required = false) String keyword) {
        return R.ok(metadataService.tablePage(current, size, datasourceId, keyword));
    }

    @Operation(summary = "分页查询字段")
    @GetMapping("/column/page")
    public R<PageResult<MetaColumn>> columns(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String tableId) {
        return R.ok(metadataService.columnPage(current, size, tableId));
    }

    @Operation(summary = "更新字段业务元数据（业务名/说明/字典关联）")
    @PutMapping("/column/{id}")
    public R<Void> updateColumn(@PathVariable String id, @RequestBody MetaColumn patch) {
        metadataService.updateColumn(id, patch);
        return R.ok();
    }

    @Operation(summary = "数据标准落标校验")
    @GetMapping("/column/{columnId}/standard-check")
    public R<Map<String, Object>> checkColumnStandard(@PathVariable String columnId,
                                                      @RequestParam String stdCode) {
        return R.ok(metadataService.checkColumnStandard(columnId, stdCode));
    }

    @Operation(summary = "检索（表/字段）")
    @GetMapping("/search")
    public R<List<Map<String, Object>>> search(@RequestParam String keyword) {
        return R.ok(metadataService.search(keyword));
    }

    @Operation(summary = "查询表血缘")
    @GetMapping("/lineage/{tableId}")
    public R<List<MetaTableLineage>> lineage(@PathVariable String tableId) {
        return R.ok(metadataService.lineage(tableId));
    }

    @Operation(summary = "记录血缘关系")
    @PostMapping("/lineage")
    public R<Void> saveLineage(@RequestBody MetaTableLineage lineage) {
        metadataService.saveLineage(lineage);
        return R.ok();
    }

    @Operation(summary = "SQL 血缘自动分析（解析 INSERT/CREATE TABLE AS ... SELECT 写入血缘）")
    @PostMapping("/lineage/analyze")
    public R<List<MetaTableLineage>> analyzeLineage(@RequestParam String datasourceId,
                                                    @RequestParam String database,
                                                    @RequestParam String sql) {
        return R.ok(metadataService.analyzeSqlLineage(datasourceId, database, sql));
    }
}

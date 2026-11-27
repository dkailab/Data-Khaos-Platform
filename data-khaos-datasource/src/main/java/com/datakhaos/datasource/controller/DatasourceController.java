package com.datakhaos.datasource.controller;

import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.datasource.api.model.ColumnInfo;
import com.datakhaos.datasource.api.model.DsConfig;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.datasource.dto.ExecuteSqlRequest;
import com.datakhaos.datasource.entity.MetaDatasource;
import com.datakhaos.datasource.service.DatasourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 数据源管理接口。
 * 端点与 data-khaos-datasource-api 的 {@code DatasourceApiClient} 契约保持一致，
 * 供 metadata / mart / query 等下游服务复用。
 */
@Tag(name = "数据源管理")
@RestController
@RequestMapping("/api/ds")
@RequiredArgsConstructor
public class DatasourceController {

    private final DatasourceService datasourceService;

    @Operation(summary = "分页查询数据源")
    @GetMapping("/page")
    public R<PageResult<MetaDatasource>> page(@RequestParam(defaultValue = "1") long current,
                                              @RequestParam(defaultValue = "10") long size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String dsType) {
        return R.ok(datasourceService.page(current, size, keyword, dsType));
    }

    @Operation(summary = "数据源详情")
    @GetMapping("/{id}")
    public R<MetaDatasource> get(@PathVariable String id) {
        return R.ok(datasourceService.get(id));
    }

    @Operation(summary = "新增数据源")
    @PostMapping
    public R<Void> create(@RequestBody MetaDatasource ds) {
        datasourceService.create(ds);
        return R.ok();
    }

    @Operation(summary = "修改数据源（密码留空表示不修改）")
    @PutMapping
    public R<Void> update(@RequestBody MetaDatasource ds) {
        datasourceService.update(ds);
        return R.ok();
    }

    @Operation(summary = "删除数据源")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        datasourceService.delete(id);
        return R.ok();
    }

    @Operation(summary = "测试连接（未保存的配置）")
    @PostMapping("/test")
    public R<Boolean> test(@RequestBody DsConfig config) {
        return R.ok(datasourceService.testConfig(config));
    }

    @Operation(summary = "测试连接（已保存的数据源）")
    @PostMapping("/{id}/test")
    public R<Boolean> testById(@PathVariable String id) {
        return R.ok(datasourceService.test(id));
    }

    @Operation(summary = "获取数据库列表")
    @GetMapping("/{id}/databases")
    public R<List<String>> databases(@PathVariable String id) {
        return R.ok(datasourceService.databases(id));
    }

    @Operation(summary = "获取表列表")
    @GetMapping("/{id}/tables/{database}")
    public R<List<String>> tables(@PathVariable String id, @PathVariable String database) {
        return R.ok(datasourceService.tables(id, database));
    }

    @Operation(summary = "获取字段名列表")
    @GetMapping("/{id}/columns/{database}/{table}")
    public R<List<String>> columns(@PathVariable String id,
                                   @PathVariable String database,
                                   @PathVariable String table) {
        return R.ok(datasourceService.columnNames(id, database, table));
    }

    @Operation(summary = "获取字段详情列表")
    @GetMapping("/{id}/column-info/{database}/{table}")
    public R<List<ColumnInfo>> columnInfos(@PathVariable String id,
                                           @PathVariable String database,
                                           @PathVariable String table) {
        return R.ok(datasourceService.columnInfos(id, database, table));
    }

    @Operation(summary = "执行 SQL（自动 SQL 审核）")
    @PostMapping("/{id}/execute")
    public R<QueryResult> execute(@PathVariable String id, @RequestBody ExecuteSqlRequest request) {
        return R.ok(datasourceService.execute(id, request.getSql(), request.getParams()));
    }

    @Operation(summary = "统计表行数")
    @GetMapping("/{id}/count/{database}/{table}")
    public R<Long> tableCount(@PathVariable String id,
                              @PathVariable String database,
                              @PathVariable String table) {
        return R.ok(datasourceService.tableCount(id, database, table));
    }
}

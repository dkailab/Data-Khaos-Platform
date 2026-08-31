package com.datakhaos.mart.controller;

import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.mart.api.model.MarketModelDto;
import com.datakhaos.mart.dto.MartQueryRequest;
import com.datakhaos.mart.dto.MartQueryResult;
import com.datakhaos.mart.entity.MartDimLevel;
import com.datakhaos.mart.entity.MartDimension;
import com.datakhaos.mart.entity.MartMetric;
import com.datakhaos.mart.entity.MartModel;
import com.datakhaos.mart.entity.MartModelRel;
import com.datakhaos.mart.entity.MartWarehouseLayer;
import com.datakhaos.mart.service.MartQueryService;
import com.datakhaos.mart.service.MartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据集市接口
 */
@Tag(name = "数据集市")
@RestController
@RequestMapping("/api/mart")
@RequiredArgsConstructor
public class MartController {

    private final MartService martService;
    private final MartQueryService martQueryService;

    // ==================== 语义查询（BI 画布） ====================

    @Operation(summary = "语义查询：模型 + 指标 + 维度 + 筛选 + 排序 → 生成 SQL 并执行")
    @PostMapping("/query")
    public R<MartQueryResult> query(@RequestBody MartQueryRequest request) {
        return R.ok(martQueryService.query(request));
    }

    @Operation(summary = "维度取值（枚举筛选器下拉用）")
    @GetMapping("/dimension/{dimId}/values")
    public R<List<String>> dimensionValues(@PathVariable String dimId,
                                           @RequestParam(defaultValue = "100") int limit) {
        return R.ok(martQueryService.dimensionValues(dimId, limit));
    }

    // ==================== 数仓分层 ====================

    @Operation(summary = "数仓分层列表（启用状态）")
    @GetMapping("/layer/list")
    public R<List<MartWarehouseLayer>> listLayers() {
        return R.ok(martService.listLayers());
    }

    // ==================== 模型 ====================

    @Operation(summary = "分页查询模型（按项目组隔离，支持分层筛选）")
    @GetMapping("/model/page")
    public R<PageResult<MartModel>> modelPage(@RequestParam(defaultValue = "1") long current,
                                              @RequestParam(defaultValue = "10") long size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Integer status,
                                              @RequestParam(required = false) String layerId) {
        return R.ok(martService.modelPage(current, size, keyword, status, layerId));
    }

    @Operation(summary = "模型详情（含指标/维度/关联）")
    @GetMapping("/model/{id}")
    public R<Map<String, Object>> modelDetail(@PathVariable String id) {
        return R.ok(martService.modelDetail(id));
    }

    @Operation(summary = "模型市场分页（仅已发布，按项目组隔离，含统计）")
    @GetMapping("/market/page")
    public R<PageResult<MarketModelDto>> marketPage(@RequestParam(defaultValue = "1") long current,
                                                    @RequestParam(defaultValue = "12") long size,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String layerId) {
        return R.ok(martService.marketPage(current, size, keyword, layerId));
    }

    @Operation(summary = "新增模型")
    @PostMapping("/model")
    public R<Void> createModel(@RequestBody MartModel model) {
        martService.createModel(model);
        return R.ok();
    }

    @Operation(summary = "修改模型")
    @PutMapping("/model")
    public R<Void> updateModel(@RequestBody MartModel model) {
        martService.updateModel(model);
        return R.ok();
    }

    @Operation(summary = "删除模型（级联删除指标/维度/关联）")
    @DeleteMapping("/model/{id}")
    public R<Void> deleteModel(@PathVariable String id) {
        martService.deleteModel(id);
        return R.ok();
    }

    @Operation(summary = "发布模型")
    @PostMapping("/model/{id}/publish")
    public R<Void> publish(@PathVariable String id) {
        martService.publish(id);
        return R.ok();
    }

    @Operation(summary = "下线模型")
    @PostMapping("/model/{id}/offline")
    public R<Void> offline(@PathVariable String id) {
        martService.offline(id);
        return R.ok();
    }

    @Operation(summary = "预览模型数据（事实表前 100 行）")
    @GetMapping("/model/{id}/preview")
    public R<QueryResult> preview(@PathVariable String id) {
        return R.ok(martService.preview(id));
    }

    // ==================== 指标 ====================

    @Operation(summary = "分页查询模型指标")
    @GetMapping("/metric/page")
    public R<PageResult<MartMetric>> metricPage(@RequestParam(defaultValue = "1") long current,
                                                @RequestParam(defaultValue = "10") long size,
                                                @RequestParam(required = false) String modelId,
                                                @RequestParam(required = false) String keyword) {
        return R.ok(martService.metricPage(current, size, modelId, keyword));
    }

    @Operation(summary = "新增指标")
    @PostMapping("/metric")
    public R<Void> createMetric(@RequestBody MartMetric metric) {
        martService.createMetric(metric);
        return R.ok();
    }

    @Operation(summary = "修改指标")
    @PutMapping("/metric")
    public R<Void> updateMetric(@RequestBody MartMetric metric) {
        martService.updateMetric(metric);
        return R.ok();
    }

    @Operation(summary = "删除指标")
    @DeleteMapping("/metric/{id}")
    public R<Void> deleteMetric(@PathVariable String id) {
        martService.deleteMetric(id);
        return R.ok();
    }

    // ==================== 维度 ====================

    @Operation(summary = "分页查询模型维度")
    @GetMapping("/dimension/page")
    public R<PageResult<MartDimension>> dimensionPage(@RequestParam(defaultValue = "1") long current,
                                                      @RequestParam(defaultValue = "10") long size,
                                                      @RequestParam(required = false) String modelId) {
        return R.ok(martService.dimensionPage(current, size, modelId));
    }

    @Operation(summary = "新增维度")
    @PostMapping("/dimension")
    public R<Void> createDimension(@RequestBody MartDimension dimension) {
        martService.createDimension(dimension);
        return R.ok();
    }

    @Operation(summary = "修改维度")
    @PutMapping("/dimension")
    public R<Void> updateDimension(@RequestBody MartDimension dimension) {
        martService.updateDimension(dimension);
        return R.ok();
    }

    @Operation(summary = "删除维度（级联删除层级）")
    @DeleteMapping("/dimension/{id}")
    public R<Void> deleteDimension(@PathVariable String id) {
        martService.deleteDimension(id);
        return R.ok();
    }

    @Operation(summary = "维度层级列表")
    @GetMapping("/dimension/{dimId}/levels")
    public R<List<MartDimLevel>> levels(@PathVariable String dimId) {
        return R.ok(martService.levels(dimId));
    }

    @Operation(summary = "保存维度层级（全量替换）")
    @PostMapping("/dimension/{dimId}/levels")
    public R<Void> saveLevels(@PathVariable String dimId, @RequestBody List<MartDimLevel> levels) {
        martService.saveLevels(dimId, levels);
        return R.ok();
    }

    // ==================== 关联关系 ====================

    @Operation(summary = "模型关联关系列表")
    @GetMapping("/model/{modelId}/rel")
    public R<List<MartModelRel>> rels(@PathVariable String modelId) {
        return R.ok(martService.rels(modelId));
    }

    @Operation(summary = "保存模型关联关系")
    @PostMapping("/model/{modelId}/rel")
    public R<Void> saveRel(@PathVariable String modelId, @RequestBody MartModelRel rel) {
        martService.saveRel(modelId, rel);
        return R.ok();
    }

    @Operation(summary = "删除模型关联关系")
    @DeleteMapping("/rel/{id}")
    public R<Void> deleteRel(@PathVariable String id) {
        martService.deleteRel(id);
        return R.ok();
    }
}

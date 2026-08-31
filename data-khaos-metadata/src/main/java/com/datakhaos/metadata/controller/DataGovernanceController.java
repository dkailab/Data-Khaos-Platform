package com.datakhaos.metadata.controller;

import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.metadata.entity.MetaDictItem;
import com.datakhaos.metadata.entity.MetaDictType;
import com.datakhaos.metadata.entity.MetaStandard;
import com.datakhaos.metadata.service.DataGovernanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 数据治理接口：数据字典管理 + 数据标准配置。
 */
@Tag(name = "数据治理")
@RestController
@RequestMapping("/api/meta")
@RequiredArgsConstructor
public class DataGovernanceController {

    private final DataGovernanceService governanceService;

    // ---------------- 字典类型 ----------------

    @Operation(summary = "字典类型分页")
    @GetMapping("/dict/type/page")
    public R<PageResult<MetaDictType>> typePage(@RequestParam(defaultValue = "1") long current,
                                                @RequestParam(defaultValue = "10") long size,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) Integer status) {
        return R.ok(governanceService.typePage(current, size, keyword, status));
    }

    @Operation(summary = "字典类型全部列表（下拉）")
    @GetMapping("/dict/type/list")
    public R<List<MetaDictType>> typeList(@RequestParam(required = false) String keyword) {
        return R.ok(governanceService.typeList(keyword));
    }

    @Operation(summary = "新增字典类型")
    @PostMapping("/dict/type")
    public R<Void> createType(@RequestBody MetaDictType type) {
        governanceService.createType(type);
        return R.ok();
    }

    @Operation(summary = "更新字典类型")
    @PutMapping("/dict/type/{id}")
    public R<Void> updateType(@PathVariable String id, @RequestBody MetaDictType type) {
        governanceService.updateType(id, type);
        return R.ok();
    }

    @Operation(summary = "删除字典类型（连带删除字典项）")
    @DeleteMapping("/dict/type/{id}")
    public R<Void> deleteType(@PathVariable String id) {
        governanceService.deleteType(id);
        return R.ok();
    }

    // ---------------- 字典项 ----------------

    @Operation(summary = "字典项分页")
    @GetMapping("/dict/item/page")
    public R<PageResult<MetaDictItem>> itemPage(@RequestParam(defaultValue = "1") long current,
                                                @RequestParam(defaultValue = "10") long size,
                                                @RequestParam String typeId,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) Integer status) {
        return R.ok(governanceService.itemPage(current, size, typeId, keyword, status));
    }

    @Operation(summary = "按字典类型编码取全部启用项")
    @GetMapping("/dict/item/list")
    public R<List<MetaDictItem>> itemListByTypeCode(@RequestParam String typeCode) {
        return R.ok(governanceService.itemListByTypeCode(typeCode));
    }

    @Operation(summary = "新增字典项")
    @PostMapping("/dict/item")
    public R<Void> createItem(@RequestBody MetaDictItem item) {
        governanceService.createItem(item);
        return R.ok();
    }

    @Operation(summary = "更新字典项")
    @PutMapping("/dict/item/{id}")
    public R<Void> updateItem(@PathVariable String id, @RequestBody MetaDictItem item) {
        governanceService.updateItem(id, item);
        return R.ok();
    }

    @Operation(summary = "删除字典项")
    @DeleteMapping("/dict/item/{id}")
    public R<Void> deleteItem(@PathVariable String id) {
        governanceService.deleteItem(id);
        return R.ok();
    }

    // ---------------- 数据标准 ----------------

    @Operation(summary = "数据标准分页")
    @GetMapping("/standard/page")
    public R<PageResult<MetaStandard>> standardPage(@RequestParam(defaultValue = "1") long current,
                                                    @RequestParam(defaultValue = "10") long size,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String category,
                                                    @RequestParam(required = false) Integer status) {
        return R.ok(governanceService.standardPage(current, size, keyword, category, status));
    }

    @Operation(summary = "新增数据标准")
    @PostMapping("/standard")
    public R<Void> createStandard(@RequestBody MetaStandard standard) {
        governanceService.createStandard(standard);
        return R.ok();
    }

    @Operation(summary = "更新数据标准")
    @PutMapping("/standard/{id}")
    public R<Void> updateStandard(@PathVariable String id, @RequestBody MetaStandard standard) {
        governanceService.updateStandard(id, standard);
        return R.ok();
    }

    @Operation(summary = "删除数据标准")
    @DeleteMapping("/standard/{id}")
    public R<Void> deleteStandard(@PathVariable String id) {
        governanceService.deleteStandard(id);
        return R.ok();
    }
}
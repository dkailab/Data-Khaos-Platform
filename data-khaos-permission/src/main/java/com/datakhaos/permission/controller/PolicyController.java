package com.datakhaos.permission.controller;

import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.permission.entity.SysColumnPolicy;
import com.datakhaos.permission.entity.SysRowPolicy;
import com.datakhaos.permission.service.PolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 行/列级数据权限策略管理
 */
@Tag(name = "数据权限策略")
@RestController
@RequestMapping("/api/permission/policy")
@RequiredArgsConstructor
public class PolicyController {

    private final PolicyService policyService;

    @Operation(summary = "分页查询行权限策略")
    @GetMapping("/row/page")
    public R<PageResult<SysRowPolicy>> rowPage(@RequestParam(defaultValue = "1") long current,
                                               @RequestParam(defaultValue = "10") long size,
                                               @RequestParam(required = false) String targetTable) {
        return R.ok(policyService.rowPage(current, size, targetTable));
    }

    @PostMapping("/row")
    public R<Void> saveRow(@RequestBody SysRowPolicy policy) {
        policyService.saveRow(policy);
        return R.ok();
    }

    @PutMapping("/row/{id}")
    public R<Void> updateRow(@PathVariable String id, @RequestBody SysRowPolicy policy) {
        policy.setId(id);
        policyService.updateRow(policy);
        return R.ok();
    }

    @DeleteMapping("/row/{id}")
    public R<Void> deleteRow(@PathVariable String id) {
        policyService.deleteRow(id);
        return R.ok();
    }

    @Operation(summary = "分页查询列权限策略")
    @GetMapping("/column/page")
    public R<PageResult<SysColumnPolicy>> columnPage(@RequestParam(defaultValue = "1") long current,
                                                     @RequestParam(defaultValue = "10") long size,
                                                     @RequestParam(required = false) String targetTable) {
        return R.ok(policyService.columnPage(current, size, targetTable));
    }

    @PostMapping("/column")
    public R<Void> saveColumn(@RequestBody SysColumnPolicy policy) {
        policyService.saveColumn(policy);
        return R.ok();
    }

    @PutMapping("/column/{id}")
    public R<Void> updateColumn(@PathVariable String id, @RequestBody SysColumnPolicy policy) {
        policy.setId(id);
        policyService.updateColumn(policy);
        return R.ok();
    }

    @DeleteMapping("/column/{id}")
    public R<Void> deleteColumn(@PathVariable String id) {
        policyService.deleteColumn(id);
        return R.ok();
    }

    // ==================== 查询接口（供 SQL 改写引擎使用） ====================

    @Operation(summary = "查询用户在指定表上的行级权限策略")
    @GetMapping("/row/user-table")
    public R<List<SysRowPolicy>> listRowPolicies(@RequestParam String targetTable,
                                                  @RequestParam(required = false) String userId,
                                                  @RequestParam(required = false) List<String> roleIds,
                                                  @RequestParam(required = false) List<String> projectGroupIds) {
        return R.ok(policyService.listRowPoliciesForUserTable(userId, roleIds, projectGroupIds, targetTable));
    }

    @Operation(summary = "查询用户在指定表上的列级权限策略")
    @GetMapping("/column/user-table")
    public R<List<SysColumnPolicy>> listColumnPolicies(@RequestParam String targetTable,
                                                        @RequestParam(required = false) String userId,
                                                        @RequestParam(required = false) List<String> roleIds,
                                                        @RequestParam(required = false) List<String> projectGroupIds) {
        return R.ok(policyService.listColumnPoliciesForUserTable(userId, roleIds, projectGroupIds, targetTable));
    }
}

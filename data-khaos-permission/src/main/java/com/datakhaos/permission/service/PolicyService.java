package com.datakhaos.permission.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.permission.entity.SysColumnPolicy;
import com.datakhaos.permission.entity.SysRowPolicy;
import com.datakhaos.permission.mapper.SysColumnPolicyMapper;
import com.datakhaos.permission.mapper.SysRowPolicyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 行/列级数据权限策略管理
 */
@Service
@RequiredArgsConstructor
public class PolicyService {

    private final SysRowPolicyMapper rowPolicyMapper;
    private final SysColumnPolicyMapper columnPolicyMapper;

    // ---------- 行权限 ----------

    public PageResult<SysRowPolicy> rowPage(long current, long size, String targetTable) {
        LambdaQueryWrapper<SysRowPolicy> wrapper = new LambdaQueryWrapper<SysRowPolicy>()
                .like(StrUtil.isNotBlank(targetTable), SysRowPolicy::getTargetTable, targetTable)
                .orderByAsc(SysRowPolicy::getCreateTime);
        var result = rowPolicyMapper.selectPage(com.baomidou.mybatisplus.extension.plugins.pagination.Page.of(current, size), wrapper);
        return PageResult.of(current, size, result.getTotal(), result.getRecords());
    }

    public void saveRow(SysRowPolicy policy) {
        rowPolicyMapper.insert(policy);
    }

    public void updateRow(SysRowPolicy policy) {
        rowPolicyMapper.updateById(policy);
    }

    public void deleteRow(String id) {
        rowPolicyMapper.deleteById(id);
    }

    // ---------- 列权限 ----------

    public PageResult<SysColumnPolicy> columnPage(long current, long size, String targetTable) {
        LambdaQueryWrapper<SysColumnPolicy> wrapper = new LambdaQueryWrapper<SysColumnPolicy>()
                .like(StrUtil.isNotBlank(targetTable), SysColumnPolicy::getTargetTable, targetTable)
                .orderByAsc(SysColumnPolicy::getCreateTime);
        var result = columnPolicyMapper.selectPage(com.baomidou.mybatisplus.extension.plugins.pagination.Page.of(current, size), wrapper);
        return PageResult.of(current, size, result.getTotal(), result.getRecords());
    }

    public void saveColumn(SysColumnPolicy policy) {
        columnPolicyMapper.insert(policy);
    }

    public void updateColumn(SysColumnPolicy policy) {
        columnPolicyMapper.updateById(policy);
    }

    public void deleteColumn(String id) {
        columnPolicyMapper.deleteById(id);
    }

    // ---------- 按用户+表查询策略（供 SQL 改写引擎使用） ----------

    /**
     * 查询用户在指定表上的行级权限策略列表。
     * 匹配规则：userId 直接命中、或 projectGroupId 命中、或 roleId 命中任一角色。
     */
    public List<SysRowPolicy> listRowPoliciesForUserTable(String userId, List<String> roleIds, List<String> projectGroupIds, String tableName) {
        if (StrUtil.isBlank(tableName)) return new ArrayList<>();
        LambdaQueryWrapper<SysRowPolicy> wrapper = new LambdaQueryWrapper<SysRowPolicy>()
                .eq(SysRowPolicy::getTargetTable, tableName)
                .eq(SysRowPolicy::getStatus, 1)
                .and(w -> {
                    w.eq(StrUtil.isNotBlank(userId), SysRowPolicy::getUserId, userId);
                    if (roleIds != null && !roleIds.isEmpty()) {
                        w.in(SysRowPolicy::getRoleId, roleIds);
                    }
                    if (projectGroupIds != null && !projectGroupIds.isEmpty()) {
                        w.in(SysRowPolicy::getProjectGroupId, projectGroupIds);
                    }
                })
                .orderByDesc(SysRowPolicy::getCreateTime);
        return rowPolicyMapper.selectList(wrapper);
    }

    /**
     * 查询用户在指定表上的列级权限策略列表。
     */
    public List<SysColumnPolicy> listColumnPoliciesForUserTable(String userId, List<String> roleIds, List<String> projectGroupIds, String tableName) {
        if (StrUtil.isBlank(tableName)) return new ArrayList<>();
        LambdaQueryWrapper<SysColumnPolicy> wrapper = new LambdaQueryWrapper<SysColumnPolicy>()
                .eq(SysColumnPolicy::getTargetTable, tableName)
                .eq(SysColumnPolicy::getStatus, 1)
                .and(w -> {
                    w.eq(StrUtil.isNotBlank(userId), SysColumnPolicy::getUserId, userId);
                    if (roleIds != null && !roleIds.isEmpty()) {
                        w.in(SysColumnPolicy::getRoleId, roleIds);
                    }
                    if (projectGroupIds != null && !projectGroupIds.isEmpty()) {
                        w.in(SysColumnPolicy::getProjectGroupId, projectGroupIds);
                    }
                })
                .orderByDesc(SysColumnPolicy::getCreateTime);
        return columnPolicyMapper.selectList(wrapper);
    }
}

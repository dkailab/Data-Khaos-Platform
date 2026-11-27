package com.datakhaos.dquality.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.dquality.entity.DqRule;
import com.datakhaos.dquality.mapper.DqRuleMapper;
import com.datakhaos.dquality.service.DqAuthContext.AuthContext;
import com.datakhaos.permission.api.service.PermissionConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 质量规则服务：CRUD + 模板下拉。
 */
@Service
@RequiredArgsConstructor
public class RuleService {

    private final DqRuleMapper ruleMapper;
    private final DqAuthContext auth;

    public PageResult<DqRule> page(long current, long size, String keyword, String ruleType, Integer status) {
        AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_QUALITY_BROWSE);
        Page<DqRule> page = ruleMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<DqRule>()
                        .eq(!ctx.superAdmin() && StrUtil.isNotBlank(ctx.projectGroupId()),
                                DqRule::getProjectGroupId, ctx.projectGroupId())
                        .eq(StrUtil.isNotBlank(ruleType), DqRule::getRuleType, ruleType)
                        .eq(status != null, DqRule::getStatus, status)
                        .and(StrUtil.isNotBlank(keyword), w -> w
                                .like(DqRule::getRuleName, keyword)
                                .or().like(DqRule::getRuleCode, keyword))
                        .orderByDesc(DqRule::getCreateTime));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    public DqRule get(String id) {
        AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_QUALITY_BROWSE);
        DqRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException("规则不存在");
        }
        auth.checkGroup(ctx, rule.getProjectGroupId(), "质量规则");
        return rule;
    }

    public void create(DqRule rule) {
        AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_QUALITY_MANAGE);
        if (StrUtil.isBlank(rule.getRuleName())) {
            throw new BusinessException("规则名称不能为空");
        }
        if (StrUtil.isBlank(rule.getRuleType())) {
            throw new BusinessException("规则类型不能为空");
        }
        if (rule.getWeight() == null) {
            rule.setWeight(1);
        }
        if (rule.getStatus() == null) {
            rule.setStatus(1);
        }
        rule.setId(null);
        rule.setProjectGroupId(ctx.superAdmin() ? rule.getProjectGroupId() : ctx.projectGroupId());
        rule.setCreateBy(MetadataHolder.getUserId());
        ruleMapper.insert(rule);
    }

    public void update(String id, DqRule rule) {
        AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_QUALITY_MANAGE);
        DqRule exist = ruleMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException("规则不存在");
        }
        auth.checkGroup(ctx, exist.getProjectGroupId(), "质量规则");
        rule.setId(id);
        rule.setProjectGroupId(exist.getProjectGroupId());
        // 不允许篡改创建人与创建时间
        rule.setCreateBy(null);
        ruleMapper.updateById(rule);
    }

    public void delete(String id) {
        AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_QUALITY_MANAGE);
        DqRule exist = ruleMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException("规则不存在");
        }
        auth.checkGroup(ctx, exist.getProjectGroupId(), "质量规则");
        ruleMapper.deleteById(id);
    }

    /** 规则模板下拉（静态元数据） */
    public List<Map<String, Object>> templateOptions() {
        AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_QUALITY_BROWSE);
        return List.of(
                Map.of("type", QualityEngine.TYPE_NOT_NULL, "name", "非空校验", "desc", "统计字段空值率"),
                Map.of("type", QualityEngine.TYPE_UNIQUE, "name", "唯一性校验", "desc", "检测字段重复"),
                Map.of("type", QualityEngine.TYPE_VALUE_RANGE, "name", "值域校验", "desc", "检测越界值"),
                Map.of("type", QualityEngine.TYPE_CUSTOM_SQL, "name", "自定义SQL校验", "desc", "返回违规行的 SQL"),
                Map.of("type", QualityEngine.TYPE_CUSTOM_PROBE, "name", "自定义SQL探查", "desc", "返回单个数值统计"),
                Map.of("type", QualityEngine.TYPE_PIPELINE_COUNT, "name", "管道阶段计数", "desc", "相邻阶段计数偏差对账")
        );
    }
}

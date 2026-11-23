package com.datakhaos.dquality.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.dquality.entity.DqRule;
import com.datakhaos.dquality.entity.DqRuleResult;
import com.datakhaos.dquality.entity.DqSnapshot;
import com.datakhaos.dquality.client.ScheduleApiClient;
import com.datakhaos.dquality.dto.ScheduleJobBrief;
import com.datakhaos.dquality.entity.DqTask;
import com.datakhaos.dquality.mapper.DqRuleMapper;
import com.datakhaos.dquality.mapper.DqRuleResultMapper;
import com.datakhaos.dquality.mapper.DqSnapshotMapper;
import com.datakhaos.dquality.mapper.DqTaskMapper;
import com.datakhaos.dquality.service.DqAuthContext.AuthContext;
import com.datakhaos.dquality.service.QualityEngine.EngineResult;
import com.datakhaos.permission.api.service.PermissionConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 质量任务服务：CRUD + 手动执行（调用稽核引擎生成快照）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final DqTaskMapper taskMapper;
    private final DqRuleMapper ruleMapper;
    private final DqSnapshotMapper snapshotMapper;
    private final DqRuleResultMapper ruleResultMapper;
    private final QualityEngine engine;
    private final DqAuthContext auth;
    private final com.datakhaos.dquality.client.NotificationApiClient notificationClient;
    private final ScheduleApiClient scheduleClient;

    public PageResult<DqTask> page(long current, long size, String keyword, Integer status) {
        AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_QUALITY_BROWSE);
        Page<DqTask> page = taskMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<DqTask>()
                        .eq(!ctx.superAdmin() && StrUtil.isNotBlank(ctx.projectGroupId()),
                                DqTask::getProjectGroupId, ctx.projectGroupId())
                        .eq(status != null, DqTask::getStatus, status)
                        .like(StrUtil.isNotBlank(keyword), DqTask::getTaskName, keyword)
                        .orderByDesc(DqTask::getCreateTime));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    public void create(DqTask task) {
        AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_QUALITY_MANAGE);
        if (StrUtil.isBlank(task.getTaskName())) {
            throw new BusinessException("任务名称不能为空");
        }
        if (StrUtil.isBlank(task.getRuleIds())) {
            throw new BusinessException("请至少绑定一个规则");
        }
        if (task.getStatus() == null) {
            task.setStatus(1);
        }
        task.setId(null);
        task.setProjectGroupId(ctx.superAdmin() ? task.getProjectGroupId() : ctx.projectGroupId());
        task.setCreateBy(MetadataHolder.getUserId());
        taskMapper.insert(task);
    }

    public void update(String id, DqTask task) {
        AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_QUALITY_MANAGE);
        DqTask exist = taskMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException("任务不存在");
        }
        auth.checkGroup(ctx, exist.getProjectGroupId(), "质量任务");
        task.setId(id);
        task.setProjectGroupId(exist.getProjectGroupId());
        task.setCreateBy(null);
        taskMapper.updateById(task);
    }

    public void delete(String id) {
        AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_QUALITY_MANAGE);
        DqTask exist = taskMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException("任务不存在");
        }
        auth.checkGroup(ctx, exist.getProjectGroupId(), "质量任务");
        taskMapper.deleteById(id);
    }

    public void setEnabled(String id, boolean enabled) {
        AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_QUALITY_MANAGE);
        DqTask exist = taskMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException("任务不存在");
        }
        auth.checkGroup(ctx, exist.getProjectGroupId(), "质量任务");
        DqTask upd = new DqTask();
        upd.setId(id);
        upd.setStatus(enabled ? 1 : 0);
        taskMapper.updateById(upd);
    }

    /**
     * 质量任务 <-> 调度任务关联映射。
     * 拉取调度服务全部 QUALITY 任务，解析 params.taskId 建立映射，
     * 供质量任务列表展示「关联调度任务」。
     */
    public Map<String, List<ScheduleJobBrief>> scheduleMap() {
        Map<String, List<ScheduleJobBrief>> map = new HashMap<>();
        for (ScheduleJobBrief job : scheduleClient.listQualityJobs()) {
            if (StrUtil.isBlank(job.getTaskId())) {
                continue;
            }
            map.computeIfAbsent(job.getTaskId(), k -> new ArrayList<>()).add(job);
        }
        return map;
    }

    /**
     * 手动执行任务：校验权限后调用内部执行逻辑。
     */
    @Transactional(rollbackFor = Exception.class)
    public DqSnapshot run(String id) {
        AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_QUALITY_RUN);
        DqTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        auth.checkGroup(ctx, task.getProjectGroupId(), "质量任务");
        return runInternal(task, "MANUAL", null, null, null);
    }

    /**
     * 内部执行任务（供调度系统 / 手动执行复用）：
     * 不做权限校验，由调用方保证已授权（如调度系统以系统身份触发）。
     * 执行完毕后若结论非 PASS，则发送站内信告警。
     */
    @Transactional(rollbackFor = Exception.class)
    public DqSnapshot runInternal(String id, String triggerType) {
        return runInternal(id, triggerType, null, null, null);
    }

    /**
     * 内部执行任务（工作流节点触发）：记录来源工作流运行/节点与业务日期，
     * 供运行证据聚合与数据地图回查质量证据。
     */
    @Transactional(rollbackFor = Exception.class)
    public DqSnapshot runInternal(String id, String triggerType,
                                  String workflowRunId, String workflowNodeRunId, String bizDate) {
        DqTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        // 跨服务调用以系统身份透传；非超级管理员时核对任务的项目组范围
        if (!MetadataHolder.isSuperAdmin()) {
            AuthContext ctx = auth.current();
            auth.checkGroup(ctx, task.getProjectGroupId(), "质量任务");
        }
        return runInternal(task, triggerType, workflowRunId, workflowNodeRunId, bizDate);
    }

    private DqSnapshot runInternal(DqTask task, String triggerType,
                                   String workflowRunId, String workflowNodeRunId, String bizDate) {
        List<DqRule> rules = loadRules(task.getRuleIds());
        // 汇总同一表（取第一个规则的稽核对象；无有效规则时留空）
        DqRule first = rules.isEmpty() ? null : rules.get(0);
        EngineResult er = rules.isEmpty()
                ? null
                : engine.execute(rules, MetadataHolder.getUserId(), triggerType, bizDate);

        DqSnapshot snapshot = new DqSnapshot();
        snapshot.setProjectGroupId(task.getProjectGroupId());
        snapshot.setTaskId(task.getId());
        snapshot.setTaskName(task.getTaskName());
        snapshot.setWorkflowRunId(workflowRunId);
        snapshot.setWorkflowNodeRunId(workflowNodeRunId);
        snapshot.setBizDate(bizDate);
        snapshot.setDatasourceId(first == null ? null : first.getDatasourceId());
        snapshot.setDatabaseName(first == null ? null : first.getDatabaseName());
        snapshot.setTableName(first == null ? null : first.getTableName());
        if (er == null) {
            // 无有效规则：无法判定，按 ERROR 留痕（工作流门禁应阻断下游）
            snapshot.setScore(BigDecimal.ZERO);
            snapshot.setRuleTotal(0);
            snapshot.setRulePass(0);
            snapshot.setRuleFail(0);
            snapshot.setCostMs(0L);
            snapshot.setConclusion(QualityEngine.CONCLUSION_ERROR);
            snapshot.setErrorCode("ZERO_VALID_RULES");
        } else {
            snapshot.setScore(er.getScore());
            snapshot.setRuleTotal(er.getTotalRules());
            snapshot.setRulePass(er.getPassRules());
            snapshot.setRuleFail(er.getFailRules());
            snapshot.setCostMs(er.getCostMs());
            snapshot.setConclusion(er.getConclusion());
            snapshot.setErrorCode(er.getErrorCode());
        }
        snapshot.setTriggerType(triggerType);
        snapshot.setCreateBy(MetadataHolder.getUserId());
        snapshotMapper.insert(snapshot);

        if (er != null) {
            for (DqRuleResult r : er.getResults()) {
                r.setId(null);
                r.setSnapshotId(snapshot.getId());
                ruleResultMapper.insert(r);
            }
        }

        log.info("质量任务 [{}] 执行完成: 结论 {}, 评分 {}, 通过 {}/{}, 触发方式 {}, 业务日 {}",
                task.getId(), snapshot.getConclusion(), snapshot.getScore(),
                snapshot.getRulePass(), snapshot.getRuleTotal(), triggerType, bizDate);

        // 告警：结论非 PASS（FAIL 或 ERROR）时发送站内信
        if (!QualityEngine.CONCLUSION_PASS.equals(snapshot.getConclusion())) {
            sendAlert(task, snapshot);
        }
        return snapshot;
    }

    private void sendAlert(DqTask task, DqSnapshot snapshot) {
        String title = String.format("数据质量告警：%s 质量评分 %.2f",
                task.getTaskName(), snapshot.getScore() == null ? 0 : snapshot.getScore());
        String content = String.format(
                "任务【%s】本次稽核完成：结论 %s，共 %d 条规则，通过 %d 条，失败 %d 条，评分 %.2f。\n" +
                        "稽核对象：%s.%s\n请前往数据质量模块查看详情。",
                task.getTaskName(),
                snapshot.getConclusion() == null ? "-" : snapshot.getConclusion(),
                snapshot.getRuleTotal() == null ? 0 : snapshot.getRuleTotal(),
                snapshot.getRulePass() == null ? 0 : snapshot.getRulePass(),
                snapshot.getRuleFail() == null ? 0 : snapshot.getRuleFail(),
                snapshot.getScore() == null ? 0 : snapshot.getScore(),
                snapshot.getDatabaseName(), snapshot.getTableName());
        String receiverId = task.getCreateBy();
        if (receiverId == null || receiverId.isBlank()) {
            receiverId = MetadataHolder.getUserId();
        }
        if (receiverId == null || receiverId.isBlank()) {
            log.info("无接收人，跳过质量告警发送: {}", task.getId());
            return;
        }
        boolean ok = notificationClient.sendSite(receiverId, title, content);
        log.info("质量任务 [{}] 告警发送{}: -> {}", task.getId(), ok ? "成功" : "失败", receiverId);
    }

    private List<DqRule> loadRules(String ruleIdsJson) {
        List<DqRule> result = new ArrayList<>();
        if (StrUtil.isBlank(ruleIdsJson)) {
            return result;
        }
        try {
            List<String> ids = JSONUtil.toList(ruleIdsJson, String.class);
            for (String ruleId : ids) {
                DqRule rule = ruleMapper.selectById(ruleId);
                if (rule != null && (rule.getStatus() == null || rule.getStatus() == 1)) {
                    result.add(rule);
                }
            }
        } catch (Exception e) {
            log.warn("解析任务规则ID失败: {}", ruleIdsJson);
        }
        return result;
    }
}
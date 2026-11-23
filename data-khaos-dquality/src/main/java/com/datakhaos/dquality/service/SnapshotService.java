package com.datakhaos.dquality.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.dquality.api.model.DqOverviewDto;
import com.datakhaos.dquality.api.model.DqTrendDto;
import com.datakhaos.dquality.entity.DqRuleResult;
import com.datakhaos.dquality.entity.DqSnapshot;
import com.datakhaos.dquality.entity.DqTask;
import com.datakhaos.dquality.mapper.DqRuleResultMapper;
import com.datakhaos.dquality.mapper.DqSnapshotMapper;
import com.datakhaos.dquality.mapper.DqTaskMapper;
import com.datakhaos.dquality.service.DqAuthContext.AuthContext;
import com.datakhaos.permission.api.service.PermissionConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 快照 / 报告服务：分页、详情、趋势、总览与导出。
 */
@Service
@RequiredArgsConstructor
public class SnapshotService {

    private final DqSnapshotMapper snapshotMapper;
    private final DqRuleResultMapper ruleResultMapper;
    private final DqTaskMapper taskMapper;
    private final DqAuthContext auth;

    public PageResult<DqSnapshot> page(long current, long size, String taskId, String tableName, Integer status) {
        AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_QUALITY_BROWSE);
        Page<DqSnapshot> page = snapshotMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<DqSnapshot>()
                        .eq(!ctx.superAdmin() && StrUtil.isNotBlank(ctx.projectGroupId()),
                                DqSnapshot::getProjectGroupId, ctx.projectGroupId())
                        .eq(StrUtil.isNotBlank(taskId), DqSnapshot::getTaskId, taskId)
                        .like(StrUtil.isNotBlank(tableName), DqSnapshot::getTableName, tableName)
                        .orderByDesc(DqSnapshot::getCreateTime));

        // 补任务名称
        List<DqSnapshot> records = page.getRecords();
        if (!records.isEmpty()) {
            List<String> taskIds = records.stream().map(DqSnapshot::getTaskId)
                    .filter(StrUtil::isNotBlank).distinct().toList();
            Map<String, String> nameMap = taskIds.isEmpty() ? Map.of()
                    : taskMapper.selectBatchIds(taskIds).stream()
                    .collect(Collectors.toMap(DqTask::getId, DqTask::getTaskName, (a, b) -> a));
            records.forEach(s -> s.setTaskName(nameMap.getOrDefault(s.getTaskId(), "-")));
        }
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    /**
     * 内部查询快照（PR3：元数据聚合运行证据）。
     * 与 {@link #detail(String)} 不同，不做能力/项目组成员校验，调用方需以系统身份透传。
     * 查不到返回 null，便于调用方区分「无证据」与「查询失败」。
     */
    public DqSnapshot getInternal(String id) {
        if (StrUtil.isBlank(id)) {
            return null;
        }
        return snapshotMapper.selectById(id);
    }

    /**
     * 内部查询某张表某个业务日最近一次稽核快照（PR3：数据地图回查质量证据）。
     *
     * @param tableName 表名（可空=不限表）
     * @param bizDate   业务日期（可空=不限日期）
     * @param conclusion 结论 PASS/FAIL/ERROR（可空=不限结论）
     */
    public DqSnapshot latestInternal(String tableName, String bizDate, String conclusion) {
        return snapshotMapper.selectList(new LambdaQueryWrapper<DqSnapshot>()
                        .eq(StrUtil.isNotBlank(tableName), DqSnapshot::getTableName, tableName)
                        .eq(StrUtil.isNotBlank(bizDate), DqSnapshot::getBizDate, bizDate)
                        .eq(StrUtil.isNotBlank(conclusion), DqSnapshot::getConclusion, conclusion)
                        .orderByDesc(DqSnapshot::getCreateTime)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    public Map<String, Object> detail(String id) {
        AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_QUALITY_BROWSE);
        DqSnapshot snapshot = snapshotMapper.selectById(id);
        if (snapshot == null) {
            throw new BusinessException("快照不存在");
        }
        auth.checkGroup(ctx, snapshot.getProjectGroupId(), "质量快照");

        List<DqRuleResult> results = ruleResultMapper.selectList(
                new LambdaQueryWrapper<DqRuleResult>()
                        .eq(DqRuleResult::getSnapshotId, id)
                        .orderByDesc(DqRuleResult::getPassed));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("snapshot", snapshot);
        data.put("results", results);
        if (StrUtil.isNotBlank(snapshot.getTaskId())) {
            DqTask task = taskMapper.selectById(snapshot.getTaskId());
            data.put("taskName", task == null ? "-" : task.getTaskName());
        } else {
            data.put("taskName", "-");
        }
        return data;
    }

    public List<DqTrendDto> trend(String tableName) {
        AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_QUALITY_BROWSE);
        return snapshotMapper.selectList(new LambdaQueryWrapper<DqSnapshot>()
                        .eq(StrUtil.isNotBlank(tableName), DqSnapshot::getTableName, tableName)
                        .eq(!ctx.superAdmin() && StrUtil.isNotBlank(ctx.projectGroupId()),
                                DqSnapshot::getProjectGroupId, ctx.projectGroupId())
                        .orderByAsc(DqSnapshot::getCreateTime))
                .stream()
                .map(s -> {
                    DqTrendDto dto = new DqTrendDto();
                    dto.setSnapshotTime(s.getCreateTime());
                    dto.setScore(s.getScore());
                    dto.setPassRate(s.getRuleTotal() == null || s.getRuleTotal() == 0 ? BigDecimal.ZERO
                            : BigDecimal.valueOf(s.getRulePass() * 100L)
                            .divide(BigDecimal.valueOf(s.getRuleTotal()), 2, RoundingMode.HALF_UP));
                    return dto;
                })
                .toList();
    }

    public DqOverviewDto overview() {
        AuthContext ctx = auth.current();
        auth.requireCap(ctx, PermissionConstants.CAP_QUALITY_BROWSE);
        DqOverviewDto vo = new DqOverviewDto();

        List<DqSnapshot> snapshots = snapshotMapper.selectList(new LambdaQueryWrapper<DqSnapshot>()
                .eq(!ctx.superAdmin() && StrUtil.isNotBlank(ctx.projectGroupId()),
                        DqSnapshot::getProjectGroupId, ctx.projectGroupId()));

        vo.setTotalExecutions(snapshots.size());
        if (!snapshots.isEmpty()) {
            DqSnapshot latest = snapshots.get(0);
            vo.setCurrentScore(latest.getScore());
            BigDecimal total = BigDecimal.ZERO;
            for (DqSnapshot s : snapshots) {
                if (s.getScore() != null) {
                    total = total.add(s.getScore());
                }
            }
            vo.setAvgPassRate(total.divide(BigDecimal.valueOf(snapshots.size()), 2, RoundingMode.HALF_UP));
        } else {
            vo.setCurrentScore(BigDecimal.ZERO);
            vo.setAvgPassRate(BigDecimal.ZERO);
        }

        long activeRules = ruleResultCount();
        vo.setTotalRules((int) activeRules);
        vo.setActiveRules((int) activeRules);

        List<DqTask> tasks = taskMapper.selectList(null);
        vo.setTotalTasks(tasks.size());

        // 最差表 Top 5：按表分组取平均分
        Map<String, List<DqSnapshot>> byTable = snapshots.stream()
                .collect(Collectors.groupingBy(s -> s.getDatabaseName() + "." + s.getTableName()));
        vo.setWorstTables(byTable.entrySet().stream()
                .map(e -> {
                    List<DqSnapshot> list = e.getValue();
                    BigDecimal avg = list.stream()
                            .filter(s -> s.getScore() != null)
                            .map(DqSnapshot::getScore)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(list.size()), 2, RoundingMode.HALF_UP);
                    DqSnapshot first = list.get(0);
                    DqOverviewDto.WorstTable wt = new DqOverviewDto.WorstTable();
                    wt.setDatasourceId(first.getDatasourceId());
                    wt.setDatabaseName(first.getDatabaseName());
                    wt.setTableName(first.getTableName());
                    wt.setScore(avg);
                    wt.setExecCount(list.size());
                    return wt;
                })
                .sorted((a, b) -> a.getScore().compareTo(b.getScore()))
                .limit(5)
                .toList());

        return vo;
    }

    private long ruleResultCount() {
        return ruleResultMapper.selectCount(null) != null ? ruleResultMapper.selectCount(null) : 0;
    }

    /**
     * 生成导出 CSV（UTF-8 BOM 前缀，防中文乱码），返回字符串内容。
     */
    public String exportCsv(String id) {
        Map<String, Object> detail = detail(id);
        DqSnapshot snapshot = (DqSnapshot) detail.get("snapshot");
        List<DqRuleResult> results = (List<DqRuleResult>) detail.get("results");

        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("数据质量稽核报告\n");
        sb.append("任务,").append(detail.get("taskName")).append("\n");
        sb.append("表,").append(snapshot.getDatabaseName()).append(".").append(snapshot.getTableName()).append("\n");
        sb.append("评分,").append(snapshot.getScore()).append("\n");
        sb.append("规则数,").append(snapshot.getRuleTotal())
                .append(",通过,").append(snapshot.getRulePass())
                .append(",失败,").append(snapshot.getRuleFail()).append("\n");
        sb.append("执行时间,").append(snapshot.getCreateTime()).append("\n\n");

        sb.append("规则ID,结果,实际值,阈值,说明\n");
        for (DqRuleResult r : results) {
            sb.append(r.getRuleId()).append(",")
                    .append(r.getPassed() == 1 ? "通过" : "失败").append(",")
                    .append(r.getActualValue()).append(",")
                    .append(r.getThreshold()).append(",")
                    .append(escapeCsv(r.getMessage())).append("\n");
        }
        return sb.toString();
    }

    private String escapeCsv(String s) {
        if (s == null) {
            return "";
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
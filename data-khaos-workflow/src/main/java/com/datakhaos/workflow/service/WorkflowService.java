package com.datakhaos.workflow.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.workflow.dto.BackfillRequest;
import com.datakhaos.workflow.dto.BackfillResult;
import com.datakhaos.workflow.dto.RunAccept;
import com.datakhaos.workflow.dto.RunRequest;
import com.datakhaos.workflow.dto.WorkflowGraphRequest;
import com.datakhaos.workflow.entity.WorkflowDef;
import com.datakhaos.workflow.entity.WorkflowEdge;
import com.datakhaos.workflow.entity.WorkflowNode;
import com.datakhaos.workflow.entity.WorkflowRun;
import com.datakhaos.workflow.mapper.WorkflowBackfillBatchMapper;
import com.datakhaos.workflow.mapper.WorkflowDefMapper;
import com.datakhaos.workflow.mapper.WorkflowEdgeMapper;
import com.datakhaos.workflow.mapper.WorkflowNodeMapper;
import com.datakhaos.workflow.mapper.WorkflowRunMapper;
import com.datakhaos.workflow.runner.WorkflowRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 工作流定义管理：DAG 图（定义+节点+边）整体保存、查询、删除、状态控制。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowDefMapper defMapper;
    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowEdgeMapper edgeMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowRunner workflowRunner;
    private final WorkflowProjectAccess projectAccess;
    private final WorkflowBackfillBatchMapper backfillBatchMapper;

    private static final String DEVELOP = "task:develop";
    private static final String SCHEDULE = "task:schedule";

    /** 补数区间首版最多天数（含首尾） */
    private static final int BACKFILL_MAX_DAYS = 31;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 整体保存/更新工作流及其节点、连线（事务替换图） */
    @Transactional(rollbackFor = Exception.class)
    public WorkflowDef saveGraph(WorkflowGraphRequest request) {
        WorkflowDef def = request.getDef();
        if (def == null || StrUtil.isBlank(def.getName())) {
            throw new BusinessException("工作流名称不能为空");
        }
        if (def.getCode() == null || def.getCode().isBlank()) {
            def.setCode("wf_" + System.currentTimeMillis());
        }
        LocalDateTime now = LocalDateTime.now();
        def.setUpdateTime(now);

        WorkflowDef existing = StrUtil.isBlank(def.getId()) ? null : defMapper.selectOne(
                new LambdaQueryWrapper<WorkflowDef>().eq(WorkflowDef::getId, def.getId()).last("FOR UPDATE"));
        boolean isNew = existing == null;
        if (!isNew && StrUtil.isNotBlank(existing.getProjectId())) {
            projectAccess.requireAction(existing.getProjectId(), DEVELOP);
            if (StrUtil.isNotBlank(def.getProjectId()) && !existing.getProjectId().equals(def.getProjectId())) {
                throw new BusinessException(ResultCode.FORBIDDEN, "项目归属需要通过管理员迁移");
            }
            def.setProjectId(existing.getProjectId());
        } else {
            projectAccess.requireAction(def.getProjectId(), DEVELOP);
        }
        boolean changesSchedule = existing == null
                ? StrUtil.isNotBlank(def.getCronExpression()) || (def.getStatus() != null && def.getStatus() != 0)
                : (def.getCronExpression() != null && !java.util.Objects.equals(def.getCronExpression(), existing.getCronExpression()))
                    || (def.getStatus() != null && !java.util.Objects.equals(def.getStatus(), existing.getStatus()));
        if (changesSchedule) {
            projectAccess.requireAction(def.getProjectId(), SCHEDULE);
        }
        if (isNew) {
            if (def.getStatus() == null) {
                def.setStatus(0);
            }
            def.setCreateTime(now);
            def.setId(null);
            defMapper.insert(def);
        } else {
            defMapper.updateById(def);
        }
        String wfId = def.getId();

        // 逻辑删除旧图，再全量写入新图（保证节点与边自洽）
        List<WorkflowNode> oldNodes = nodeMapper.selectList(new LambdaQueryWrapper<WorkflowNode>()
                .eq(WorkflowNode::getWfId, wfId));
        for (WorkflowNode n : oldNodes) {
            nodeMapper.deleteById(n.getId());
        }
        List<WorkflowEdge> oldEdges = edgeMapper.selectList(new LambdaQueryWrapper<WorkflowEdge>()
                .eq(WorkflowEdge::getWfId, wfId));
        for (WorkflowEdge e : oldEdges) {
            edgeMapper.deleteById(e.getId());
        }

        List<WorkflowNode> nodes = request.getNodes();
        if (nodes != null) {
            for (WorkflowNode n : nodes) {
                n.setId(null);
                n.setWfId(wfId);
                n.setCreateTime(now);
                n.setUpdateTime(now);
                nodeMapper.insert(n);
            }
        }
        List<WorkflowEdge> edges = request.getEdges();
        if (edges != null) {
            for (WorkflowEdge e : edges) {
                e.setId(null);
                e.setWfId(wfId);
                e.setCreateTime(now);
                e.setUpdateTime(now);
                edgeMapper.insert(e);
            }
        }
        return def;
    }

    /** 工作流详情：定义 + 节点 + 连线 */
    public WorkflowGraphRequest detail(String wfId) {
        WorkflowDef def = defMapper.selectById(wfId);
        if (def == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "工作流不存在: " + wfId);
        }
        List<WorkflowNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<WorkflowNode>()
                .eq(WorkflowNode::getWfId, wfId)
                .orderByAsc(WorkflowNode::getCreateTime));
        List<WorkflowEdge> edges = edgeMapper.selectList(new LambdaQueryWrapper<WorkflowEdge>()
                .eq(WorkflowEdge::getWfId, wfId));
        WorkflowGraphRequest request = new WorkflowGraphRequest();
        request.setDef(def);
        request.setNodes(nodes);
        request.setEdges(edges);
        return request;
    }

    /** 分页列表 */
    public PageResult<WorkflowDef> page(long current, long size, String keyword) {
        Page<WorkflowDef> page = defMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<WorkflowDef>()
                        .and(StrUtil.isNotBlank(keyword), w -> w
                                .like(WorkflowDef::getName, keyword)
                                .or().like(WorkflowDef::getCode, keyword))
                        .orderByDesc(WorkflowDef::getCreateTime));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /** 删除工作流（逻辑删除定义及其图） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String wfId) {
        WorkflowDef def = defMapper.selectById(wfId);
        if (def == null) {
            return;
        }
        projectAccess.requireAction(def.getProjectId(), DEVELOP);
        defMapper.deleteById(wfId);
        List<WorkflowNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<WorkflowNode>()
                .eq(WorkflowNode::getWfId, wfId));
        for (WorkflowNode n : nodes) {
            nodeMapper.deleteById(n.getId());
        }
        List<WorkflowEdge> edges = edgeMapper.selectList(new LambdaQueryWrapper<WorkflowEdge>()
                .eq(WorkflowEdge::getWfId, wfId));
        for (WorkflowEdge e : edges) {
            edgeMapper.deleteById(e.getId());
        }
    }

    /** 更新状态 0:禁用 1:启用 */
    public void updateStatus(String wfId, Integer status) {
        WorkflowDef def = defMapper.selectById(wfId);
        if (def == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "工作流不存在: " + wfId);
        }
        projectAccess.requireAction(def.getProjectId(), SCHEDULE);
        def.setStatus(status);
        def.setUpdateTime(LocalDateTime.now());
        defMapper.updateById(def);
    }

    public List<WorkflowRun> runPage(String wfId, long current, long size) {
        Page<WorkflowRun> page = runMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<WorkflowRun>()
                        .eq(StrUtil.isNotBlank(wfId), WorkflowRun::getWfId, wfId)
                        .orderByDesc(WorkflowRun::getCreateTime));
        return page.getRecords();
    }

    public WorkflowRun latestRunByScheduleLog(String workflowId, String scheduleLogId) {
        return runMapper.selectList(new LambdaQueryWrapper<WorkflowRun>()
                        .eq(WorkflowRun::getWfId, workflowId)
                        .eq(WorkflowRun::getScheduleLogId, scheduleLogId)
                        .orderByDesc(WorkflowRun::getCreateTime)
                        .orderByDesc(WorkflowRun::getId))
                .stream().findFirst().orElse(null);
    }

    public WorkflowRun runDetail(String runId) {
        return runMapper.selectById(runId);
    }

    public List<WorkflowDef> listAll() {
        return defMapper.selectList(new LambdaQueryWrapper<WorkflowDef>()
                .eq(WorkflowDef::getStatus, 1)
                .orderByAsc(WorkflowDef::getCreateTime));
    }

    /** 节点运行记录（按运行实例） */
    public List<com.datakhaos.workflow.entity.WorkflowNodeRun> nodeRuns(String runId) {
        return workflowRunner.nodeRuns(runId);
    }

    /** 触发工作流运行（runner 内部异步执行） */
    public String trigger(String wfId, Map<String, Object> params, String triggerType) {
        return trigger(wfId, params, triggerType, null, null);
    }

    /**
     * 触发工作流运行（携带调度关联ID，供追溯）。
     */
    public String trigger(String wfId, Map<String, Object> params, String triggerType,
                          String scheduleJobId, String scheduleLogId) {
        return trigger(requireEnabledWorkflow(wfId), params, triggerType, scheduleJobId, scheduleLogId);
    }

    public String triggerManual(String wfId, Map<String, Object> params) {
        WorkflowDef def = requireEnabledWorkflow(wfId);
        projectAccess.requireAction(def.getProjectId(), DEVELOP);
        return trigger(def, params, "MANUAL", null, null);
    }

    private WorkflowDef requireEnabledWorkflow(String wfId) {
        WorkflowDef def = defMapper.selectById(wfId);
        if (def == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "工作流不存在: " + wfId);
        }
        if (def.getStatus() != null && def.getStatus() == 0) {
            throw new BusinessException("工作流已禁用，无法运行");
        }
        return def;
    }

    private String trigger(WorkflowDef def, Map<String, Object> params, String triggerType,
                           String scheduleJobId, String scheduleLogId) {
        Map<String, Object> effective = mergeParams(def.getParams(), params);
        return workflowRunner.trigger(def.getId(), triggerType, effective, def.getName(), def.getProjectId(),
                scheduleJobId, scheduleLogId);
    }

    /** 合并参数：def.params 模板为默认，runtimeParams 覆盖之 */
    private Map<String, Object> mergeParams(String paramsTemplate, Map<String, Object> runtimeParams) {
        Map<String, Object> merged = new HashMap<>();
        if (StrUtil.isNotBlank(paramsTemplate)) {
            try {
                cn.hutool.json.JSONObject tpl = cn.hutool.json.JSONUtil.parseObj(paramsTemplate);
                merged.putAll(tpl);
            } catch (Exception ignore) {
                log.warn("工作流参数模板 JSON 解析失败: {}", paramsTemplate);
            }
        }
        if (runtimeParams != null) {
            merged.putAll(runtimeParams);
        }
        return merged;
    }

    // ------------------------------------------------------------------
    // PR2A：带业务日期运行、区间补数、请求幂等
    // ------------------------------------------------------------------

    /**
     * 带业务日期的手动运行（幂等键由调用方给出）。
     *
     * @return 受理结果（ACCEPTED / DUPLICATE / REJECTED），拒绝时携带 errorCode
     */
    public RunAccept runByRequest(String wfId, RunRequest request) {
        WorkflowDef def = defMapper.selectById(wfId);
        if (def == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "工作流不存在: " + wfId);
        }
        if (def.getStatus() != null && def.getStatus() == 0) {
            throw new BusinessException("工作流已禁用，无法运行");
        }
        BusinessException denial = projectDenial(def.getProjectId(), DEVELOP);
        if (denial != null) {
            return RunAccept.rejected(rejectCode(denial), denial.getMessage(), request == null ? null : request.getBizDate());
        }
        if (request == null || StrUtil.isBlank(request.getRequestId())) {
            throw new BusinessException("requestId 不能为空");
        }
        String requestId = request.getRequestId().trim();
        String bizDate = request.getBizDate() == null ? null : request.getBizDate().trim();
        LocalDate date = parseStrict(bizDate);
        if (date == null) {
            return RunAccept.rejected("INVALID_DATE", "业务日期格式应为 YYYY-MM-DD: " + bizDate, bizDate);
        }
        String isoDate = date.toString();
        Map<String, Object> effective = mergeParams(def.getParams(), request.getParams());
        // 覆盖规则：业务日期运行中，节点静态参数不能改写 bizDate
        effective.put("bizDate", isoDate);
        String norm = normalizeParams(effective);
        String hash = DigestUtil.sha256Hex(norm);
        return doCreate(def, "MANUAL", effective, requestId, isoDate, norm, hash, null, null, null);
    }

    /**
     * 区间补数：含首尾，首版最多 31 天；先验证整段参数与权限，再按日受理。
     * 每日一条运行，子请求键 = requestId:bizDate；重发时返回已受理运行并尝试未受理日期。
     */
    public BackfillResult backfill(String wfId, BackfillRequest request) {
        WorkflowDef def = defMapper.selectById(wfId);
        if (def == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "工作流不存在: " + wfId);
        }
        if (def.getStatus() != null && def.getStatus() == 0) {
            throw new BusinessException("工作流已禁用，无法运行");
        }
        BackfillResult result = new BackfillResult();
        result.setRequestId(request == null ? null : request.getRequestId());
        result.setFromDate(request == null ? null : request.getFromDate());
        result.setToDate(request == null ? null : request.getToDate());

        BusinessException denial = projectDenial(def.getProjectId(), SCHEDULE);
        if (denial != null) {
            BackfillResult.Item item = new BackfillResult.Item();
            item.setState(RunAccept.STATE_REJECTED);
            item.setRejectCode(rejectCode(denial));
            item.setRejectMessage(denial.getMessage());
            result.getItems().add(item);
            return result;
        }
        if (request == null || StrUtil.isBlank(request.getRequestId())) {
            throw new BusinessException("requestId 不能为空");
        }
        result.setRequestId(request.getRequestId().trim());
        if (result.getRequestId().codePointCount(0, result.getRequestId().length()) > 85) {
            throw new BusinessException("补数请求 ID 最多 85 个字符");
        }
        LocalDate from = parseStrict(request.getFromDate());
        LocalDate to = parseStrict(request.getToDate());
        if (from == null || to == null) {
            throw new BusinessException("补数日期格式应为 YYYY-MM-DD");
        }
        if (from.isAfter(to)) {
            throw new BusinessException("补数起始日期不能晚于结束日期");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > BACKFILL_MAX_DAYS) {
            throw new BusinessException("补数区间最多 " + BACKFILL_MAX_DAYS + " 天，当前 " + days + " 天");
        }
        result.setFromDate(from.toString());
        result.setToDate(to.toString());

        Map<String, Object> baseParams = mergeParams(def.getParams(), request.getParams());
        baseParams.remove("bizDate");
        String batchHash = DigestUtil.sha256Hex(normalizeParams(baseParams));
        String requestId = result.getRequestId();
        backfillBatchMapper.reserve(wfId, requestId, from, to, batchHash);
        if (!backfillBatchMapper.matches(wfId, requestId, from, to, batchHash)) {
            BackfillResult.Item conflict = new BackfillResult.Item();
            conflict.setState(RunAccept.STATE_REJECTED);
            conflict.setRejectCode("REQUEST_CONFLICT");
            conflict.setRejectMessage("补数批次键已绑定原业务日期区间及参数");
            result.getItems().add(conflict);
            return result;
        }
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            String bizDate = d.toString();
            Map<String, Object> effective = new HashMap<>(baseParams);
            effective.put("bizDate", bizDate);
            // 批次键绑定原始区间，按日派生稳定子请求键
            String norm = normalizeParams(effective) + "|backfill=" + result.getRequestId()
                    + "|" + from + "|" + to;
            String hash = DigestUtil.sha256Hex(norm);
            RunAccept accept = doCreate(def, "BACKFILL", effective,
                    result.getRequestId() + ":" + bizDate, bizDate, norm, hash, null, null, null);
            BackfillResult.Item item = new BackfillResult.Item();
            item.setBizDate(bizDate);
            item.setState(accept.getState());
            item.setRunId(accept.getRunId());
            item.setRejectCode(accept.getRejectCode());
            item.setRejectMessage(accept.getRejectMessage());
            item.setActiveRunId(accept.getActiveRunId());
            result.getItems().add(item);
        }
        return result;
    }

    /**
     * 受信内部触发（调度使用）：携带 requestId / bizDate / scheduledAt，
     * 由调度服务生成稳定幂等键与业务日期。
     */
    public RunAccept triggerWithRequest(String wfId, Map<String, Object> params, String triggerType,
                                        String scheduleJobId, String scheduleLogId,
                                        String requestId, String bizDate, LocalDateTime scheduledAt) {
        WorkflowDef def = defMapper.selectById(wfId);
        if (def == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "工作流不存在: " + wfId);
        }
        if (def.getStatus() != null && def.getStatus() == 0) {
            throw new BusinessException("工作流已禁用，无法运行");
        }
        Map<String, Object> effective = mergeParams(def.getParams(), params);
        if (StrUtil.isNotBlank(bizDate)) {
            effective.put("bizDate", bizDate);
        }
        String norm = normalizeParams(effective);
        String hash = DigestUtil.sha256Hex(norm);
        return doCreate(def, triggerType, effective, requestId, bizDate, norm, hash,
                scheduleJobId, scheduleLogId, scheduledAt);
    }

    /** 创建运行并在事务提交后提交 DAG；拒绝语义转换为结构化受理结果 */
    private RunAccept doCreate(WorkflowDef def, String triggerType, Map<String, Object> effective,
                               String requestId, String bizDate, String norm, String hash,
                               String scheduleJobId, String scheduleLogId, LocalDateTime scheduledAt) {
        try {
            String runId = workflowRunner.createRunGraph(def.getId(), triggerType, effective, def.getName(),
                    def.getProjectId(), scheduleJobId, scheduleLogId, requestId, bizDate, norm, hash, scheduledAt);
            workflowRunner.executeAsync(runId, def.getId(), effective, bizDate);
            return RunAccept.accepted(runId, bizDate);
        } catch (WorkflowRunner.RunRejectedException e) {
            if (e.isDuplicate()) {
                return RunAccept.duplicate(e.getExistingRunId(), bizDate);
            }
            log.warn("工作流运行被拒绝: wfId={}, requestId={}, code={}, msg={}",
                    def.getId(), requestId, e.getCode(), e.getMessage());
            RunAccept reject = RunAccept.rejected(e.getCode(), e.getMessage(), bizDate);
            reject.setActiveRunId(e.getActiveRunId());
            return reject;
        }
    }

    private BusinessException projectDenial(String projectId, String capability) {
        try {
            projectAccess.requireAction(projectId, capability);
            return null;
        } catch (BusinessException e) {
            if (e.getCode() == ResultCode.NOT_FOUND.getCode() || e.getCode() == ResultCode.FORBIDDEN.getCode()) {
                return e;
            }
            throw e;
        }
    }

    private String rejectCode(BusinessException denial) {
        return denial.getCode() == ResultCode.NOT_FOUND.getCode() ? "PROJECT_FORBIDDEN" : "FORBIDDEN";
    }

    /** 规范化参数（key 排序拼接），用于同键异参校验 */
    private String normalizeParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        TreeMap<String, Object> sorted = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue() == null ? "" : e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /** 严格 YYYY-MM-DD 解析（ISO_LOCAL_DATE 要求补零） */
    private LocalDate parseStrict(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim(), DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    /** 运行实例分页（可按业务日期过滤） */
    public List<WorkflowRun> runPage(String wfId, long current, long size, String bizDate) {
        Page<WorkflowRun> page = runMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<WorkflowRun>()
                        .eq(StrUtil.isNotBlank(wfId), WorkflowRun::getWfId, wfId)
                        .eq(StrUtil.isNotBlank(bizDate), WorkflowRun::getBizDate, bizDate)
                        .orderByDesc(WorkflowRun::getCreateTime));
        return page.getRecords();
    }
}
package com.datakhaos.workflow.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.workflow.dto.WorkflowGraphRequest;
import com.datakhaos.workflow.entity.WorkflowDef;
import com.datakhaos.workflow.entity.WorkflowEdge;
import com.datakhaos.workflow.entity.WorkflowNode;
import com.datakhaos.workflow.entity.WorkflowRun;
import com.datakhaos.workflow.mapper.WorkflowDefMapper;
import com.datakhaos.workflow.mapper.WorkflowEdgeMapper;
import com.datakhaos.workflow.mapper.WorkflowNodeMapper;
import com.datakhaos.workflow.mapper.WorkflowRunMapper;
import com.datakhaos.workflow.runner.WorkflowRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        boolean isNew = StrUtil.isBlank(def.getId()) || defMapper.selectById(def.getId()) == null;
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
        WorkflowDef def = defMapper.selectById(wfId);
        if (def == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "工作流不存在: " + wfId);
        }
        if (def.getStatus() != null && def.getStatus() == 0) {
            throw new BusinessException("工作流已禁用，无法运行");
        }
        // 参数模板(def.params) 作为默认值，运行时参数优先覆盖
        Map<String, Object> effective = mergeParams(def.getParams(), params);
        return workflowRunner.trigger(wfId, triggerType, effective, def.getName(), scheduleJobId, scheduleLogId);
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
}
package com.datakhaos.workflow.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.workflow.dto.WorkflowDependencyGraph;
import com.datakhaos.workflow.dto.WorkflowUpstreamRequest;
import com.datakhaos.workflow.dto.WorkflowUpstreamView;
import com.datakhaos.workflow.entity.WorkflowDef;
import com.datakhaos.workflow.entity.WorkflowRun;
import com.datakhaos.workflow.entity.WorkflowUpstream;
import com.datakhaos.workflow.mapper.WorkflowDefMapper;
import com.datakhaos.workflow.mapper.WorkflowRunMapper;
import com.datakhaos.workflow.mapper.WorkflowUpstreamMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * 跨任务流依赖：配置上游、运行前门禁校验、参数映射与依赖图。
 *
 * <p>这是「任务流之间」的依赖，与工作流内部的节点 DAG 是两层关系：
 * 内部 DAG 决定一次运行里节点怎么走，这里决定当前任务流能否开始运行。</p>
 */
@Service
@RequiredArgsConstructor
public class WorkflowUpstreamService {

    private static final String DEVELOP = "task:develop";
    private static final int MAX_DEPENDENCIES = 20;
    private static final int MAX_GRAPH_DEPTH = 5;
    private static final int DEFAULT_GRAPH_DEPTH = 3;

    private final WorkflowUpstreamMapper mapper;
    private final WorkflowDefMapper defMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowProjectAccess access;

    public List<WorkflowUpstreamView> list(String wfId) {
        WorkflowDef definition = requireDefinition(wfId);
        access.requireAction(definition.getProjectId(), DEVELOP);
        List<WorkflowUpstream> rows = mapper.selectList(new LambdaQueryWrapper<WorkflowUpstream>()
                .eq(WorkflowUpstream::getWfId, wfId)
                .orderByAsc(WorkflowUpstream::getCreateTime));
        Map<String, WorkflowDef> defs = loadDefinitions(rows.stream().map(WorkflowUpstream::getUpstreamWfId).toList());
        return rows.stream().map(row -> WorkflowUpstreamView.of(row, defs.get(row.getUpstreamWfId()))).toList();
    }

    /** 整体替换当前任务流的上游依赖；保存前校验自依赖、重复与环路。 */
    @Transactional(rollbackFor = Exception.class)
    public List<WorkflowUpstreamView> save(String wfId, List<WorkflowUpstreamRequest> requests) {
        WorkflowDef definition = requireDefinition(wfId);
        access.requireAction(definition.getProjectId(), DEVELOP);
        List<WorkflowUpstreamRequest> items = requests == null ? List.of() : requests;
        if (items.size() > MAX_DEPENDENCIES) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "上游依赖最多 " + MAX_DEPENDENCIES + " 个");
        }
        Set<String> seen = new HashSet<>();
        List<WorkflowUpstream> rows = new ArrayList<>();
        for (WorkflowUpstreamRequest item : items) {
            String upstreamId = item == null ? null : StrUtil.trimToNull(item.upstreamWfId());
            if (upstreamId == null) {
                throw new BusinessException(ResultCode.VALIDATE_FAILED, "上游任务流不能为空");
            }
            if (upstreamId.equals(wfId)) {
                throw new BusinessException(ResultCode.VALIDATE_FAILED, "任务流不能依赖自身");
            }
            if (!seen.add(upstreamId)) {
                throw new BusinessException(ResultCode.VALIDATE_FAILED, "上游任务流重复: " + upstreamId);
            }
            if (defMapper.selectById(upstreamId) == null) {
                throw new BusinessException(ResultCode.VALIDATE_FAILED, "上游任务流不存在: " + upstreamId);
            }
            String strategy = normalizeStrategy(item.dependStrategy());
            String mapping = normalizeMapping(item.paramMapping());
            WorkflowUpstream row = new WorkflowUpstream();
            row.setWfId(wfId);
            row.setUpstreamWfId(upstreamId);
            row.setDependStrategy(strategy);
            row.setParamMapping(mapping);
            row.setEnabled(item.enabled() == null || item.enabled() ? 1 : 0);
            rows.add(row);
        }
        assertNoCycle(wfId, rows);

        mapper.delete(new LambdaQueryWrapper<WorkflowUpstream>().eq(WorkflowUpstream::getWfId, wfId));
        for (WorkflowUpstream row : rows) {
            mapper.insert(row);
        }
        return list(wfId);
    }

    /** 删除任务流时清理两个方向的依赖，避免留下指向已删除对象的脏记录。 */
    @Transactional(rollbackFor = Exception.class)
    public void removeAll(String wfId) {
        mapper.delete(new LambdaQueryWrapper<WorkflowUpstream>().eq(WorkflowUpstream::getWfId, wfId));
        mapper.delete(new LambdaQueryWrapper<WorkflowUpstream>().eq(WorkflowUpstream::getUpstreamWfId, wfId));
    }

    /**
     * 运行前门禁：返回阻塞原因列表，空列表表示可以运行。
     * OFF 策略只参与展示，不做校验。
     */
    public List<String> blockers(String wfId, String bizDate) {
        List<WorkflowUpstream> deps = activeDependencies(wfId);
        if (deps.isEmpty()) {
            return List.of();
        }
        List<String> blockers = new ArrayList<>();
        for (WorkflowUpstream dep : deps) {
            boolean strict = WorkflowUpstream.STRATEGY_STRICT.equalsIgnoreCase(dep.getDependStrategy());
            if (!strict && !WorkflowUpstream.STRATEGY_LOOSE.equalsIgnoreCase(dep.getDependStrategy())) {
                continue;
            }
            if (!hasSuccessfulRun(dep.getUpstreamWfId(), strict ? bizDate : null, strict)) {
                WorkflowDef upstream = defMapper.selectById(dep.getUpstreamWfId());
                String name = upstream == null ? dep.getUpstreamWfId() : upstream.getName();
                blockers.add(strict
                        ? "上游任务流「" + name + "」在业务日 " + (StrUtil.isBlank(bizDate) ? "今日" : bizDate) + " 尚未成功"
                        : "上游任务流「" + name + "」没有成功运行记录");
            }
        }
        return blockers;
    }

    /**
     * 上游参数注入：按 paramMapping 把上游最近一次成功的运行参数映射到本次运行。
     * 显式传入的同名参数优先，不被上游覆盖。
     */
    public Map<String, Object> mergeParams(String wfId, Map<String, Object> params, String bizDate) {
        Map<String, Object> merged = new HashMap<>(params == null ? Map.of() : params);
        for (WorkflowUpstream dep : activeDependencies(wfId)) {
            if (StrUtil.isBlank(dep.getParamMapping())) {
                continue;
            }
            JSONObject mapping;
            try {
                mapping = JSONUtil.parseObj(dep.getParamMapping());
            } catch (Exception e) {
                continue;
            }
            WorkflowRun upstreamRun = latestSuccessfulRun(dep.getUpstreamWfId(), bizDate);
            if (upstreamRun == null || StrUtil.isBlank(upstreamRun.getTriggerParams())) {
                continue;
            }
            JSONObject upstreamParams;
            try {
                upstreamParams = JSONUtil.parseObj(upstreamRun.getTriggerParams());
            } catch (Exception e) {
                continue;
            }
            for (String localKey : mapping.keySet()) {
                Object current = merged.get(localKey);
                if (current != null && StrUtil.isNotBlank(String.valueOf(current))) {
                    continue;
                }
                String upstreamKey = mapping.getStr(localKey);
                if (StrUtil.isNotBlank(upstreamKey) && upstreamParams.containsKey(upstreamKey)) {
                    merged.put(localKey, upstreamParams.get(upstreamKey));
                }
            }
        }
        return merged;
    }

    /** 依赖图：从当前任务流向上展开 depth 层，用于「生成依赖图」。 */
    public WorkflowDependencyGraph graph(String wfId, Integer depth) {
        WorkflowDef root = requireDefinition(wfId);
        access.requireAction(root.getProjectId(), DEVELOP);
        int limit = depth == null ? DEFAULT_GRAPH_DEPTH : Math.max(1, Math.min(MAX_GRAPH_DEPTH, depth));

        Map<String, WorkflowDependencyGraph.Node> nodes = new LinkedHashMap<>();
        nodes.put(wfId, new WorkflowDependencyGraph.Node(wfId, root.getName(), root.getCode(),
                root.getStatus(), 0, true));
        List<WorkflowDependencyGraph.Edge> edges = new ArrayList<>();
        Set<String> edgeKeys = new HashSet<>();
        Set<String> frontier = new LinkedHashSet<>(List.of(wfId));
        int actualDepth = 0;

        for (int level = 1; level <= limit && !frontier.isEmpty(); level++) {
            Set<String> next = new LinkedHashSet<>();
            for (String current : frontier) {
                List<WorkflowUpstream> deps = mapper.selectList(new LambdaQueryWrapper<WorkflowUpstream>()
                        .eq(WorkflowUpstream::getWfId, current)
                        .eq(WorkflowUpstream::getEnabled, 1));
                for (WorkflowUpstream dep : deps) {
                    WorkflowDef upstream = defMapper.selectById(dep.getUpstreamWfId());
                    if (upstream == null) {
                        continue;
                    }
                    boolean isNew = !nodes.containsKey(upstream.getId());
                    if (isNew) {
                        nodes.put(upstream.getId(), new WorkflowDependencyGraph.Node(
                                upstream.getId(), upstream.getName(), upstream.getCode(), upstream.getStatus(),
                                level, false));
                    }
                    String edgeKey = dep.getUpstreamWfId() + "->" + current;
                    if (edgeKeys.add(edgeKey)) {
                        edges.add(new WorkflowDependencyGraph.Edge(dep.getUpstreamWfId(), current,
                                dep.getDependStrategy()));
                    }
                    if (isNew && level < limit) {
                        next.add(dep.getUpstreamWfId());
                    }
                }
            }
            if (!next.isEmpty()) {
                actualDepth = level;
            }
            frontier = next;
        }
        return new WorkflowDependencyGraph(wfId, actualDepth, new ArrayList<>(nodes.values()), edges);
    }

    private List<WorkflowUpstream> activeDependencies(String wfId) {
        return mapper.selectList(new LambdaQueryWrapper<WorkflowUpstream>()
                .eq(WorkflowUpstream::getWfId, wfId)
                .eq(WorkflowUpstream::getEnabled, 1));
    }

    /** 上游是否有成功运行；strict=true 时按业务日或当日限定。 */
    private boolean hasSuccessfulRun(String upstreamWfId, String bizDate, boolean strict) {
        LambdaQueryWrapper<WorkflowRun> query = new LambdaQueryWrapper<WorkflowRun>()
                .eq(WorkflowRun::getWfId, upstreamWfId)
                .eq(WorkflowRun::getRunStatus, "SUCCESS");
        if (strict) {
            if (StrUtil.isNotBlank(bizDate)) {
                query.eq(WorkflowRun::getBizDate, bizDate.trim());
            } else {
                query.ge(WorkflowRun::getCreateTime, LocalDate.now().atStartOfDay());
            }
        }
        return runMapper.selectCount(query) > 0;
    }

    private WorkflowRun latestSuccessfulRun(String upstreamWfId, String bizDate) {
        LambdaQueryWrapper<WorkflowRun> query = new LambdaQueryWrapper<WorkflowRun>()
                .eq(WorkflowRun::getWfId, upstreamWfId)
                .eq(WorkflowRun::getRunStatus, "SUCCESS")
                .orderByDesc(WorkflowRun::getCreateTime)
                .last("LIMIT 1");
        if (StrUtil.isNotBlank(bizDate)) {
            query.eq(WorkflowRun::getBizDate, bizDate.trim());
        }
        return runMapper.selectOne(query);
    }

    /** 环路检测：新依赖里任一上游若能沿上游边回到当前任务流，则构成环。 */
    private void assertNoCycle(String wfId, List<WorkflowUpstream> newRows) {
        Map<String, Set<String>> graph = new HashMap<>();
        for (WorkflowUpstream row : mapper.selectList(new LambdaQueryWrapper<WorkflowUpstream>()
                .eq(WorkflowUpstream::getEnabled, 1))) {
            if (wfId.equals(row.getWfId())) {
                continue;
            }
            graph.computeIfAbsent(row.getWfId(), key -> new HashSet<>()).add(row.getUpstreamWfId());
        }
        for (WorkflowUpstream row : newRows) {
            graph.computeIfAbsent(wfId, key -> new HashSet<>()).add(row.getUpstreamWfId());
        }
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        for (WorkflowUpstream row : newRows) {
            queue.offer(row.getUpstreamWfId());
        }
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (wfId.equals(current)) {
                throw new BusinessException(ResultCode.VALIDATE_FAILED, "上游依赖形成环路，请检查任务流依赖关系");
            }
            if (!visited.add(current)) {
                continue;
            }
            queue.addAll(graph.getOrDefault(current, Set.of()));
        }
    }

    private WorkflowDef requireDefinition(String wfId) {
        WorkflowDef definition = StrUtil.isBlank(wfId) ? null : defMapper.selectById(wfId);
        if (definition == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "任务流不存在或已删除");
        }
        return definition;
    }

    private Map<String, WorkflowDef> loadDefinitions(List<String> ids) {
        Map<String, WorkflowDef> result = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        for (WorkflowDef definition : defMapper.selectBatchIds(ids)) {
            result.put(definition.getId(), definition);
        }
        return result;
    }

    private String normalizeStrategy(String raw) {
        String strategy = StrUtil.trimToNull(raw);
        if (strategy == null) {
            return WorkflowUpstream.STRATEGY_STRICT;
        }
        strategy = strategy.toUpperCase();
        if (!WorkflowUpstream.STRATEGY_STRICT.equals(strategy)
                && !WorkflowUpstream.STRATEGY_LOOSE.equals(strategy)
                && !WorkflowUpstream.STRATEGY_OFF.equals(strategy)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "不支持的依赖策略: " + raw);
        }
        return strategy;
    }

    private String normalizeMapping(String raw) {
        String mapping = StrUtil.trimToNull(raw);
        if (mapping == null) {
            return null;
        }
        try {
            JSONObject object = JSONUtil.parseObj(mapping);
            for (String key : object.keySet()) {
                if (object.getStr(key) == null || object.getStr(key).isBlank()) {
                    throw new BusinessException(ResultCode.VALIDATE_FAILED, "参数映射的值不能为空: " + key);
                }
            }
            return object.toString();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "参数映射必须是 JSON 对象");
        }
    }
}

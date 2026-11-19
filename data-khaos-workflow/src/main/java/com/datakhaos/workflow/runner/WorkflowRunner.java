package com.datakhaos.workflow.runner;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.workflow.entity.WorkflowEdge;
import com.datakhaos.workflow.entity.WorkflowNode;
import com.datakhaos.workflow.entity.WorkflowNodeRun;
import com.datakhaos.workflow.entity.WorkflowRun;
import com.datakhaos.workflow.executor.ExecResult;
import com.datakhaos.workflow.executor.NodeContext;
import com.datakhaos.workflow.executor.NodeExecutor;
import com.datakhaos.workflow.executor.NodeExecutorFactory;
import com.datakhaos.workflow.mapper.WorkflowEdgeMapper;
import com.datakhaos.workflow.mapper.WorkflowNodeMapper;
import com.datakhaos.workflow.mapper.WorkflowNodeRunMapper;
import com.datakhaos.workflow.mapper.WorkflowRunMapper;
import com.datakhaos.pipeline.api.service.PipelineApiClient;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工作流 DAG 调度器：基于拓扑排序，并行执行就绪节点，任一节点失败则快速失败（下游 SKIPPED）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowRunner {

    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowEdgeMapper edgeMapper;
    private final NodeExecutorFactory executorFactory;
    @Qualifier("nodeTaskExecutor")
    private final ThreadPoolTaskExecutor nodeTaskExecutor;

    @Resource(name = "workflowGraphExecutor")
    private ThreadPoolTaskExecutor graphExecutor;

    /**
     * 触发一次工作流运行（异步执行），返回运行实例ID。
     */
    public String trigger(String wfId, String triggerType, Map<String, Object> params, String wfName) {
        return trigger(wfId, triggerType, params, wfName, null, null);
    }

    /**
     * 触发一次工作流运行（异步执行），返回运行实例ID。
     * 可携带调度关联ID（scheduleJobId / scheduleLogId）用于追溯。
     */
    public String trigger(String wfId, String triggerType, Map<String, Object> params, String wfName,
                          String scheduleJobId, String scheduleLogId) {
        List<WorkflowNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<WorkflowNode>()
                .eq(WorkflowNode::getWfId, wfId)
                .orderByAsc(WorkflowNode::getCreateTime));
        List<WorkflowEdge> edges = edgeMapper.selectList(new LambdaQueryWrapper<WorkflowEdge>()
                .eq(WorkflowEdge::getWfId, wfId));
        if (nodes.isEmpty()) {
            throw new BusinessException("工作流未配置任何节点，无法运行");
        }
        validateGraph(nodes, edges);

        LocalDateTime now = LocalDateTime.now();
        WorkflowRun run = new WorkflowRun();
        run.setWfId(wfId);
        run.setWfName(wfName);
        run.setTriggerType(triggerType);
        run.setTriggerParams(params == null ? null : JSONUtil.toJsonStr(params));
        run.setRunStatus("PENDING");
        run.setCreateTime(now);
        run.setStartTime(now);
        run.setScheduleJobId(scheduleJobId);
        run.setScheduleLogId(scheduleLogId);
        runMapper.insert(run);
        String runId = run.getId();

        Map<String, WorkflowNodeRun> nodeRuns = new LinkedHashMap<>();
        for (WorkflowNode n : nodes) {
            WorkflowNodeRun nr = new WorkflowNodeRun();
            nr.setRunId(runId);
            nr.setWfId(wfId);
            nr.setNodeCode(n.getNodeCode());
            nr.setNodeName(n.getNodeName());
            nr.setNodeType(n.getNodeType());
            nr.setStatus("PENDING");
            nr.setCreateTime(now);
            nodeRunMapper.insert(nr);
            nodeRuns.put(n.getNodeCode(), nr);
        }

        graphExecutor.execute(() -> executeGraph(runId, nodes, edges, nodeRuns, params));
        return runId;
    }

    /** 按运行实例ID查节点运行记录 */
    public List<WorkflowNodeRun> nodeRuns(String runId) {
        return nodeRunMapper.selectList(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getRunId, runId)
                .orderByAsc(WorkflowNodeRun::getCreateTime));
    }

    // ------------------------------------------------------------------
    // DAG 拓扑调度
    // ------------------------------------------------------------------

    private void executeGraph(String runId, List<WorkflowNode> nodes, List<WorkflowEdge> edges,
                              Map<String, WorkflowNodeRun> nodeRuns, Map<String, Object> params) {
        Map<String, WorkflowNode> nodeMap = new HashMap<>();
        for (WorkflowNode n : nodes) {
            nodeMap.put(n.getNodeCode(), n);
        }

        // 邻接表 + 剩余依赖数
        Map<String, List<String>> adj = new HashMap<>();
        Map<String, Integer> remaining = new ConcurrentHashMap<>();
        for (WorkflowNode n : nodes) {
            adj.put(n.getNodeCode(), new ArrayList<>());
            remaining.put(n.getNodeCode(), 0);
        }
        for (WorkflowEdge e : edges) {
            adj.get(e.getFromCode()).add(e.getToCode());
            remaining.put(e.getToCode(), remaining.get(e.getToCode()) + 1);
        }

        Set<String> done = ConcurrentHashMap.newKeySet();
        WorkflowRun currentRun = runMapper.selectById(runId);
        currentRun.setRunStatus("RUNNING");
        runMapper.updateById(currentRun);
        Set<String> started = ConcurrentHashMap.newKeySet();
        AtomicInteger finished = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(nodes.size());
        Object lock = new Object();

        // 根节点入队
        Queue<String> ready = new LinkedBlockingQueue<>();
        remaining.forEach((code, r) -> {
            if (r == 0) {
                ready.offer(code);
            }
        });

        // 主循环：调度就绪节点；失败则快速失败
        while (finished.get() < nodes.size()) {
            if (!ready.isEmpty()) {
                String code = ready.poll();
                nodeTaskExecutor.execute(() ->
                        runOne(runId, code, nodeMap.get(code), nodeRuns.get(code), params,
                                adj, remaining, ready, done, started, finished, latch, lock));
            } else {
                // 无就绪节点：等待运行中的节点完成 / 失败事件推进
                if (latch.getCount() <= 0) {
                    break;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 汇总运行结果
        WorkflowRun run = runMapper.selectById(runId);
        if (run != null) {
            boolean anyFailed = nodeRunMapper.selectList(new LambdaQueryWrapper<WorkflowNodeRun>()
                            .eq(WorkflowNodeRun::getRunId, runId)
                            .eq(WorkflowNodeRun::getStatus, "FAILED"))
                    .size() > 0;
            run.setRunStatus(anyFailed ? "FAILED" : "SUCCESS");
            run.setEndTime(LocalDateTime.now());
            run.setDurationMs(nodeRunMapper.selectList(new LambdaQueryWrapper<WorkflowNodeRun>()
                            .eq(WorkflowNodeRun::getRunId, runId)).stream()
                    .map(WorkflowNodeRun::getDurationMs)
                    .filter(d -> d != null)
                    .mapToLong(Long::longValue)
                    .sum());
            if (anyFailed) {
                run.setErrorMessage("存在节点执行失败，工作流快速失败");
            }
            runMapper.updateById(run);
        }
        log.info("工作流运行结束 runId={}", runId);
    }

    private void runOne(String runId, String code, WorkflowNode node, WorkflowNodeRun nodeRun,
                        Map<String, Object> params, Map<String, List<String>> adj,
                        Map<String, Integer> remaining, Queue<String> ready, Set<String> done, Set<String> started,
                        AtomicInteger finished, CountDownLatch latch, Object lock) {
        // 幂等保护：已被快速失败标记为 SKIPPED 的节点不再实际执行
        synchronized (lock) {
            if (done.contains(code)) {
                return;
            }
            started.add(code);
        }

        // 重试策略：最大尝试次数 = retryCount + 1，间隔 retryInterval 秒
        int maxAttempts = (node.getRetryCount() == null || node.getRetryCount() <= 0)
                ? 1 : node.getRetryCount() + 1;
        long intervalMs = (node.getRetryInterval() == null || node.getRetryInterval() <= 0)
                ? 0 : (long) node.getRetryInterval() * 1000L;

        boolean ok = false;
        int attempt = 0;
        String errMsg = null;
        ExecResult result = null;
        long start = System.currentTimeMillis();
        try {
            for (; attempt < maxAttempts; attempt++) {
                synchronized (lock) {
                    if (done.contains(code)) {
                        return;
                    }
                }
                nodeRun.setStatus("RUNNING");
                nodeRun.setStartTime(LocalDateTime.now());
                if (attempt > 0) {
                    nodeRun.setLogText(truncate("第 " + attempt + " 次执行失败，第 " + (attempt + 1) + " 次重试中", 4000));
                }
                nodeRunMapper.updateById(nodeRun);

                try {
                    NodeExecutor executor = executorFactory.get(node.getNodeType());
                    result = executor.execute(buildContext(node, params, runId, nodeRun.getId()));
                    ok = result != null && result.isSuccess();
                    if (ok) {
                        break;
                    }
                    errMsg = "节点执行失败（退出码非 0）";
                    log.warn("节点执行失败，将重试 node={} attempt={}/{}", node.getNodeName(), attempt + 1, maxAttempts);
                } catch (PipelineApiClient.SubmissionUnconfirmedException e) {
                    errMsg = truncate(e.getMessage(), 2000);
                    break;
                } catch (Throwable e) {
                    ok = false;
                    errMsg = truncate(e.getMessage(), 2000);
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    log.warn("节点执行异常，将重试 node={} attempt={}/{}", node.getNodeName(), attempt + 1, maxAttempts, e);
                }

                // 未成功且有剩余重试次数：按间隔等待后重试
                if (attempt + 1 < maxAttempts) {
                    try {
                        Thread.sleep(intervalMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        errMsg = "节点重试等待被中断";
                        break;
                    }
                }
            }
        } catch (Throwable unexpected) {
            ok = false;
            errMsg = truncate(unexpected.getMessage(), 2000);
        }

        synchronized (lock) {
            if (done.contains(code)) {
                return;
            }
            done.add(code);
            nodeRun.setEndTime(LocalDateTime.now());
            nodeRun.setDurationMs(System.currentTimeMillis() - start);
            nodeRun.setStatus(ok ? "SUCCESS" : "FAILED");
            if (ok) {
                nodeRun.setResultRows(result == null ? null : result.getRows());
                String logText = result == null ? "" : result.getLog();
                if (attempt > 0) {
                    logText = ("（重试 " + attempt + " 次后成功）\n") + logText;
                }
                nodeRun.setLogText(truncate(logText, 4000));
            } else {
                nodeRun.setErrorMessage(errMsg);
                nodeRun.setLogText(truncate(errMsg, 4000));
            }
            nodeRunMapper.updateById(nodeRun);
            finished.incrementAndGet();
            if (!ok) {
                failFast(runId, adj, ready, done, started, finished, latch, lock);
            }
            latch.countDown();
        }

        if (ok) {
            synchronized (lock) {
                for (String to : adj.getOrDefault(code, List.of())) {
                    if (done.contains(to)) {
                        continue;
                    }
                    int rem = remaining.get(to) - 1;
                    remaining.put(to, rem);
                    if (rem == 0) {
                        ready.offer(to);
                    }
                }
            }
        }
    }

    /** 失败快速跳过：标记所有未完成节点为 SKIPPED 并计数，避免死锁 */
    private void failFast(String runId, Map<String, List<String>> adj,
                          Queue<String> ready, Set<String> done, Set<String> started,
                          AtomicInteger finished, CountDownLatch latch, Object lock) {
        synchronized (lock) {
            ready.clear();
            for (String code : adj.keySet()) {
                if (done.contains(code) || started.contains(code)) {
                    continue;
                }
                done.add(code);
                WorkflowNodeRun nr = new WorkflowNodeRun();
                nr.setStatus("SKIPPED");
                nodeRunMapper.update(nr,
                        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WorkflowNodeRun>()
                                .eq(WorkflowNodeRun::getRunId, runId)
                                .eq(WorkflowNodeRun::getNodeCode, code));
                finished.incrementAndGet();
                latch.countDown();
            }
        }
    }

    /** 组装节点执行上下文并做 ${param} 替换 */
    private NodeContext buildContext(WorkflowNode node, Map<String, Object> params,
                                     String runId, String nodeRunId) {
        Map<String, Object> config = new HashMap<>();
        if (StrUtil.isNotBlank(node.getConfigJson())) {
            JSONObject obj = JSONUtil.parseObj(node.getConfigJson());
            config.putAll(obj);
        }
        String contentKey = NodeExecutorKeys.contentKey(node.getNodeType());
        String content = contentKey == null ? null : resolveStr(String.valueOf(config.getOrDefault(contentKey, "")), params);
        String datasourceId = resolveStr(String.valueOf(config.getOrDefault("datasourceId", "")), params);
        return NodeContext.builder()
                .node(node)
                .content(content)
                .datasourceId(StrUtil.isBlank(datasourceId) ? null : datasourceId)
                .config(config)
                .params(params)
                .timeoutSeconds(node.getTimeout() == null || node.getTimeout() <= 0 ? null : (long) node.getTimeout())
                .runId(runId)
                .nodeRunId(nodeRunId)
                .build();
    }

    /** DAG 环检测 */
    private void validateGraph(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        Set<String> codes = new ConcurrentHashMap<String, Boolean>().newKeySet();
        for (WorkflowNode n : nodes) {
            codes.add(n.getNodeCode());
        }
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
        for (String c : codes) {
            indegree.put(c, 0);
            adj.put(c, new ArrayList<>());
        }
        for (WorkflowEdge e : edges) {
            if (!codes.contains(e.getFromCode()) || !codes.contains(e.getToCode())) {
                throw new BusinessException("连线引用了不存在的节点: " + e.getFromCode() + " -> " + e.getToCode());
            }
            adj.get(e.getFromCode()).add(e.getToCode());
            indegree.put(e.getToCode(), indegree.get(e.getToCode()) + 1);
        }
        Queue<String> q = new java.util.ArrayDeque<>();
        indegree.forEach((c, d) -> {
            if (d == 0) {
                q.offer(c);
            }
        });
        int visited = 0;
        while (!q.isEmpty()) {
            String c = q.poll();
            visited++;
            for (String to : adj.get(c)) {
                int d = indegree.get(to) - 1;
                indegree.put(to, d);
                if (d == 0) {
                    q.offer(to);
                }
            }
        }
        if (visited != codes.size()) {
            throw new BusinessException("工作流 DAG 存在环路，请检查连线");
        }
    }

    /** ${param} 模板替换 */
    private String resolveStr(String template, Map<String, Object> params) {
        if (template == null || params == null || params.isEmpty()) {
            return template;
        }
        String out = template;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return out;
    }

    /** 限制字符串长度 */
    private String truncate(String text, int maxLen) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private static class NodeExecutorKeys {
        static String contentKey(String nodeType) {
            switch (nodeType) {
                case "SQL":
                case "DATA_OP":
                    return "sql";
                case "SHELL":
                case "PYTHON":
                    return "script";
                default:
                    return null;
            }
        }
    }
}
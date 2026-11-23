package com.datakhaos.workflow.runner;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.workflow.entity.WorkflowBizdateLock;
import com.datakhaos.workflow.entity.WorkflowEdge;
import com.datakhaos.workflow.entity.WorkflowNode;
import com.datakhaos.workflow.entity.WorkflowNodeRun;
import com.datakhaos.workflow.entity.WorkflowRun;
import com.datakhaos.workflow.executor.ExecResult;
import com.datakhaos.workflow.executor.NodeContext;
import com.datakhaos.workflow.executor.NodeExecutor;
import com.datakhaos.workflow.executor.NodeExecutorFactory;
import com.datakhaos.workflow.mapper.WorkflowBizdateLockMapper;
import com.datakhaos.workflow.mapper.WorkflowEdgeMapper;
import com.datakhaos.workflow.mapper.WorkflowNodeMapper;
import com.datakhaos.workflow.mapper.WorkflowNodeRunMapper;
import com.datakhaos.workflow.mapper.WorkflowRunMapper;
import com.datakhaos.pipeline.api.service.PipelineApiClient;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final WorkflowBizdateLockMapper lockMapper;
    private final NodeExecutorFactory executorFactory;
    @Qualifier("nodeTaskExecutor")
    private final ThreadPoolTaskExecutor nodeTaskExecutor;
    private final WorkflowRunLifecycle lifecycle;
    private final Set<String> activeGraphs = ConcurrentHashMap.newKeySet();

    @Resource(name = "workflowGraphExecutor")
    private ThreadPoolTaskExecutor graphExecutor;

    /**
     * 触发一次工作流运行（异步执行），返回运行实例ID。
     * 可携带调度关联ID（scheduleJobId / scheduleLogId）用于追溯。
     */
    public String trigger(String wfId, String triggerType, Map<String, Object> params, String wfName,
                          String projectGroupId, String scheduleJobId, String scheduleLogId) {
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
        run.setProjectGroupId(StrUtil.trimToNull(projectGroupId));
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
        }

        submit(runId, nodes, edges, params, null);
        return runId;
    }

    // ------------------------------------------------------------------
    // PR2A：带业务日期与请求幂等键的运行受理
    // ------------------------------------------------------------------

    /**
     * 创建一次带业务日期的运行（事务：运行行 + 节点初始行 + 业务日锁一起持久化）。
     *
     * <p>只有本方法成功返回，调用方才会提交 DAG 执行；幂等重复返回既有运行ID，
     * 拒绝场景抛出 {@link RunRejectedException}（含拒绝码与活跃运行ID）。</p>
     *
     * @return 新建运行ID；幂等重复时返回既有运行ID（异常形式携带 state=DUPLICATE）
     */
    // READ_COMMITTED：唯一键冲突后回查既有行/锁时需读到其他事务已提交的最新数据，
    // 否则默认 REPEATABLE READ 的旧快照会看不到并发胜出方，把同键重复误判为参数冲突。
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public String createRunGraph(String wfId, String triggerType, Map<String, Object> params, String wfName,
                                 String projectGroupId,
                                 String scheduleJobId, String scheduleLogId,
                                 String requestId, String bizDate,
                                 String requestParams, String requestHash, LocalDateTime scheduledAt) {
        // 1) 幂等：同 (wfId, requestId) 已存在
        if (StrUtil.isNotBlank(requestId)) {
            List<WorkflowRun> existing = runMapper.selectList(new LambdaQueryWrapper<WorkflowRun>()
                    .eq(WorkflowRun::getWfId, wfId)
                    .eq(WorkflowRun::getRequestId, requestId)
                    .orderByAsc(WorkflowRun::getId));
            if (!existing.isEmpty()) {
                WorkflowRun run = existing.get(0);
                if (Objects.equals(run.getRequestHash(), requestHash)) {
                    throw RunRejectedException.duplicate(run.getId());
                }
                throw RunRejectedException.of("REQUEST_CONFLICT",
                        "同键请求参数不一致: requestId=" + requestId
                                + "，已存 hash=" + run.getRequestHash() + "，本次 hash=" + requestHash);
            }
        }

        // 2) 活跃互斥：同一 (wfId, bizDate) 仅允许一条活跃运行
        if (StrUtil.isNotBlank(bizDate)) {
            WorkflowBizdateLock active = lockMapper.selectOne(new LambdaQueryWrapper<WorkflowBizdateLock>()
                    .eq(WorkflowBizdateLock::getWfId, wfId)
                    .eq(WorkflowBizdateLock::getBizDate, bizDate));
            if (active != null) {
                throw RunRejectedException.active(active.getRunId(),
                        "业务日 " + bizDate + " 已有活跃运行: " + active.getRunId());
            }
        }

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
        run.setProjectGroupId(StrUtil.trimToNull(projectGroupId));
        run.setWfName(wfName);
        run.setTriggerType(triggerType);
        run.setTriggerParams(params == null ? null : JSONUtil.toJsonStr(params));
        run.setRunStatus("PENDING");
        run.setCreateTime(now);
        run.setStartTime(now);
        run.setScheduleJobId(scheduleJobId);
        run.setScheduleLogId(scheduleLogId);
        run.setRequestId(requestId);
        run.setBizDate(bizDate);
        run.setRequestParams(requestParams);
        run.setRequestHash(requestHash);
        run.setScheduledAt(scheduledAt);
        try {
            runMapper.insert(run);
        } catch (DuplicateKeyException e) {
            // 并发同键：唯一约束 uk_wf_run_request 兜底，回查既有运行后按 hash 判定
            WorkflowRun existing = runMapper.selectOne(new LambdaQueryWrapper<WorkflowRun>()
                    .eq(WorkflowRun::getWfId, wfId)
                    .eq(WorkflowRun::getRequestId, requestId)
                    .orderByAsc(WorkflowRun::getId));
            if (existing != null && Objects.equals(existing.getRequestHash(), requestHash)) {
                throw RunRejectedException.duplicate(existing.getId());
            }
            throw RunRejectedException.of("REQUEST_CONFLICT",
                    "同键请求参数不一致（并发创建）: requestId=" + requestId);
        }
        String runId = run.getId();

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
        }

        // 业务日锁：唯一键兜底并发，冲突说明另一请求已抢先创建
        if (StrUtil.isNotBlank(bizDate)) {
            WorkflowBizdateLock lock = new WorkflowBizdateLock();
            lock.setWfId(wfId);
            lock.setBizDate(bizDate);
            lock.setRunId(runId);
            try {
                lockMapper.insert(lock);
            } catch (DuplicateKeyException e) {
                WorkflowBizdateLock active = lockMapper.selectOne(new LambdaQueryWrapper<WorkflowBizdateLock>()
                        .eq(WorkflowBizdateLock::getWfId, wfId)
                        .eq(WorkflowBizdateLock::getBizDate, bizDate));
                throw RunRejectedException.active(active == null ? null : active.getRunId(),
                        "业务日 " + bizDate + " 已有活跃运行（并发创建冲突）");
            }
        }
        return runId;
    }

    /**
     * 提交 DAG 执行；执行器拒绝提交时保留可查询的失败终态并释放业务日锁，避免长期停在 PENDING。
     */
    public void executeAsync(String runId, String wfId, Map<String, Object> params, String bizDate) {
        List<WorkflowNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<WorkflowNode>()
                .eq(WorkflowNode::getWfId, wfId)
                .orderByAsc(WorkflowNode::getCreateTime));
        List<WorkflowEdge> edges = edgeMapper.selectList(new LambdaQueryWrapper<WorkflowEdge>()
                .eq(WorkflowEdge::getWfId, wfId));
        submit(runId, nodes, edges, params, bizDate);
    }

    private void submit(String runId, List<WorkflowNode> nodes, List<WorkflowEdge> edges,
                        Map<String, Object> params, String bizDate) {
        try {
            graphExecutor.execute(() -> {
                if (!lifecycle.start(runId, LocalDateTime.now())) return;
                activeGraphs.add(runId);
                try {
                    executeGraph(runId, nodes, edges, params, bizDate);
                } finally {
                    activeGraphs.remove(runId);
                }
            });
        } catch (RejectedExecutionException e) {
            lifecycle.rejectQueued(runId, LocalDateTime.now());
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void refreshRunHeartbeats() {
        for (String runId : activeGraphs) {
            try {
                lifecycle.heartbeat(runId, LocalDateTime.now());
            } catch (RuntimeException e) {
                log.warn("续约工作流心跳失败 runId={}", runId, e);
            }
        }
    }

    /** 运行受理拒绝异常：携带拒绝码，便于服务层转换为可查询的结构化结果 */
    public static class RunRejectedException extends RuntimeException {

        private final String code;
        private final String activeRunId;
        private final String existingRunId;
        private final boolean duplicate;

        private RunRejectedException(String code, String message, String activeRunId,
                                     String existingRunId, boolean duplicate) {
            super(message);
            this.code = code;
            this.activeRunId = activeRunId;
            this.existingRunId = existingRunId;
            this.duplicate = duplicate;
        }

        public static RunRejectedException of(String code, String message) {
            return new RunRejectedException(code, message, null, null, false);
        }

        /** 同业务日已有活跃运行 */
        public static RunRejectedException active(String activeRunId, String message) {
            return new RunRejectedException("RUN_ALREADY_ACTIVE", message, activeRunId, null, false);
        }

        /** 同 requestId 同参数：幂等重复，existingRunId 为既有运行 */
        public static RunRejectedException duplicate(String existingRunId) {
            return new RunRejectedException("DUPLICATE", "同键请求已受理", null, existingRunId, true);
        }

        public String getCode() {
            return code;
        }

        public String getActiveRunId() {
            return activeRunId;
        }

        public String getExistingRunId() {
            return existingRunId;
        }

        public boolean isDuplicate() {
            return duplicate;
        }
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
                              Map<String, Object> params, String bizDate) {
        Map<String, WorkflowNode> nodeMap = new HashMap<>();
        for (WorkflowNode n : nodes) {
            nodeMap.put(n.getNodeCode(), n);
        }
        Map<String, WorkflowNodeRun> nodeRuns = new LinkedHashMap<>();
        for (WorkflowNodeRun nr : nodeRunMapper.selectList(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getRunId, runId))) {
            nodeRuns.put(nr.getNodeCode(), nr);
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
        AtomicBoolean unconfirmed = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
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
        while (!interrupted.get() && finished.get() < nodes.size()) {
            if (!ready.isEmpty()) {
                String code = ready.poll();
                nodeTaskExecutor.execute(() -> {
                    if (interrupted.get()) return;
                    try {
                        runOne(runId, code, nodeMap.get(code), nodeRuns.get(code), params, bizDate,
                                adj, remaining, ready, done, started, finished, latch, lock, unconfirmed);
                    } catch (RuntimeException e) {
                        interrupted.set(true);
                        log.error("工作流节点协调中断 runId={}, node={}", runId, code, e);
                    }
                });
            } else {
                // 无就绪节点：等待运行中的节点完成 / 失败事件推进
                if (latch.getCount() <= 0) {
                    break;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        if (interrupted.get()) return;
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        List<WorkflowNodeRun> results = nodeRuns(runId);
        boolean anyFailed = results.stream().anyMatch(node -> "FAILED".equals(node.getStatus()));
        boolean unresolved = unconfirmed.get();
        long duration = results.stream().map(WorkflowNodeRun::getDurationMs).filter(Objects::nonNull)
                .mapToLong(Long::longValue).sum();
        String status = unresolved ? "RECOVERY_REQUIRED" : anyFailed ? "FAILED" : "SUCCESS";
        String reason = unresolved ? "外部任务结果未知，需核查执行和写入状态"
                : anyFailed ? "存在节点执行失败" : null;
        lifecycle.finish(runId, status, reason, duration, unresolved, LocalDateTime.now());
    }

    private void runOne(String runId, String code, WorkflowNode node, WorkflowNodeRun nodeRun,
                        Map<String, Object> params, String bizDate, Map<String, List<String>> adj,
                        Map<String, Integer> remaining, Queue<String> ready, Set<String> done, Set<String> started,
                        AtomicInteger finished, CountDownLatch latch, Object lock, AtomicBoolean unconfirmed) {
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
                LocalDateTime now = LocalDateTime.now();
                if (!lifecycle.startNode(runId, nodeRun.getId(), now)) {
                    synchronized (lock) {
                        done.add(code);
                        finished.incrementAndGet();
                        failFast(runId, adj, ready, done, started, finished, latch, lock);
                        latch.countDown();
                    }
                    return;
                }
                nodeRun.setStatus("RUNNING");
                nodeRun.setStartTime(now);
                if (attempt > 0) {
                    nodeRun.setLogText(truncate("第 " + attempt + " 次执行失败，第 " + (attempt + 1) + " 次重试中", 4000));
                }
                nodeRunMapper.updateById(nodeRun);

                try {
                    NodeExecutor executor = executorFactory.get(node.getNodeType());
                    result = executor.execute(buildContext(node, params, runId, nodeRun.getId(), bizDate));
                    ok = result != null && result.isSuccess();
                    if (ok) {
                        break;
                    }
                    errMsg = "节点执行失败（退出码非 0）";
                    log.warn("节点执行失败，将重试 node={} attempt={}/{}", node.getNodeName(), attempt + 1, maxAttempts);
                } catch (PipelineApiClient.SubmissionUnconfirmedException e) {
                    unconfirmed.set(true);
                    errMsg = truncate(e.getMessage(), 2000);
                    break;
                } catch (Throwable e) {
                    ok = false;
                    errMsg = truncate(e.getMessage(), 2000);
                    if (Thread.currentThread().isInterrupted()) {
                        unconfirmed.set(true);
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

    /** 组装节点执行上下文并做 ${param} 替换；业务日期由运行上下文注入，节点静态参数不能改写 */
    private NodeContext buildContext(WorkflowNode node, Map<String, Object> params,
                                     String runId, String nodeRunId, String bizDate) {
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
                .bizDate(bizDate)
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
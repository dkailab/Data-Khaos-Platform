package com.datakhaos.workflow.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.workflow.entity.WorkflowDef;
import com.datakhaos.workflow.entity.WorkflowEdge;
import com.datakhaos.workflow.entity.WorkflowExternalRecovery;
import com.datakhaos.workflow.entity.WorkflowNode;
import com.datakhaos.workflow.entity.WorkflowNodeRun;
import com.datakhaos.workflow.entity.WorkflowRun;
import com.datakhaos.workflow.executor.DolphinSchedulerBinding;
import com.datakhaos.workflow.executor.DolphinSchedulerClient;
import com.datakhaos.workflow.executor.DolphinSchedulerNodeExecutor;
import com.datakhaos.workflow.executor.NodeType;
import com.datakhaos.workflow.mapper.WorkflowDefMapper;
import com.datakhaos.workflow.mapper.WorkflowEdgeMapper;
import com.datakhaos.workflow.mapper.WorkflowExternalRecoveryMapper;
import com.datakhaos.workflow.mapper.WorkflowNodeMapper;
import com.datakhaos.workflow.mapper.WorkflowNodeRunMapper;
import com.datakhaos.workflow.mapper.WorkflowRunMapper;
import com.datakhaos.workflow.runner.WorkflowRunLifecycle;
import com.datakhaos.workflow.runner.WorkflowRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowRecoveryServiceTest {
    private final WorkflowRun run = new WorkflowRun();
    private final WorkflowDef def = new WorkflowDef();
    private final WorkflowNode node = new WorkflowNode();
    private final WorkflowNodeRun nodeRun = new WorkflowNodeRun();
    private final WorkflowNodeRun downstreamRun = new WorkflowNodeRun();
    private final List<WorkflowNode> nodes = new ArrayList<>();
    private final List<WorkflowEdge> edges = new ArrayList<>();
    private final Map<String, WorkflowExternalRecovery> recoveries = new HashMap<>();
    private final FakeClient client = new FakeClient(new ObjectMapper());
    private final FakeAccess access = new FakeAccess();
    private final FakeLifecycle lifecycle = new FakeLifecycle();
    private final FakeRunner runner = new FakeRunner();
    private WorkflowRecoveryService service;

    @BeforeEach
    void init() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "recovery"),
                WorkflowExternalRecovery.class);
        run.setId("run-1");
        run.setWfId("wf-1");
        run.setProjectGroupId("p1");
        run.setRunStatus("FAILED");
        run.setTriggerParams("{}");
        run.setBizDate("2026-11-28");
        def.setId("wf-1");
        def.setProjectId("p1");
        node.setId("node-1");
        node.setWfId("wf-1");
        node.setNodeCode("ds");
        node.setNodeName("离线 DAG");
        node.setNodeType(NodeType.DOLPHINSCHEDULER);
        nodes.add(node);
        WorkflowNode downstream = new WorkflowNode();
        downstream.setId("node-2");
        downstream.setWfId("wf-1");
        downstream.setNodeCode("downstream");
        downstream.setNodeType(NodeType.SHELL);
        nodes.add(downstream);
        WorkflowEdge edge = new WorkflowEdge();
        edge.setWfId("wf-1");
        edge.setFromCode("ds");
        edge.setToCode("downstream");
        edges.add(edge);

        nodeRun.setId("node-run-1");
        nodeRun.setRunId("run-1");
        nodeRun.setWfId("wf-1");
        nodeRun.setNodeCode("ds");
        nodeRun.setNodeType(NodeType.DOLPHINSCHEDULER);
        nodeRun.setRefType(NodeType.DOLPHINSCHEDULER);
        nodeRun.setRefId("17");
        nodeRun.setStatus("FAILED");
        nodeRun.setStartTime(LocalDateTime.now().minusMinutes(3));
        downstreamRun.setId("node-run-2");
        downstreamRun.setRunId("run-1");
        downstreamRun.setWfId("wf-1");
        downstreamRun.setNodeCode("downstream");
        downstreamRun.setNodeType(NodeType.SHELL);
        downstreamRun.setStatus("SKIPPED");

        WorkflowNodeRunMapper nodeRunMapper = proxy(WorkflowNodeRunMapper.class, (proxy, method, args) ->
                switch (method.getName()) {
                    case "selectById" -> nodeRun;
                    case "selectList" -> List.of(downstreamRun);
                    default -> unsupported(method);
                });
        WorkflowNodeMapper nodeMapper = proxy(WorkflowNodeMapper.class, (proxy, method, args) ->
                method.getName().equals("selectList") ? nodes : unsupported(method));
        WorkflowEdgeMapper edgeMapper = proxy(WorkflowEdgeMapper.class, (proxy, method, args) ->
                method.getName().equals("selectList") ? edges : unsupported(method));
        WorkflowDefMapper defMapper = proxy(WorkflowDefMapper.class, (proxy, method, args) ->
                method.getName().equals("selectById") ? def : unsupported(method));
        WorkflowRunMapper runMapper = proxy(WorkflowRunMapper.class, (proxy, method, args) ->
                method.getName().equals("selectById") ? run : unsupported(method));
        WorkflowExternalRecoveryMapper recoveryMapper = proxy(WorkflowExternalRecoveryMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "find" -> recoveries.get(args[0] + "|" + args[1]);
                    case "selectById" -> recoveries.values().stream()
                            .filter(row -> row.getId().equals(args[0])).findFirst().orElse(null);
                    case "insert" -> {
                        WorkflowExternalRecovery recovery = (WorkflowExternalRecovery) args[0];
                        if (recovery.getId() == null) {
                            recovery.setId("recovery-1");
                        }
                        String key = recovery.getNodeRunId() + "|" + recovery.getAction();
                        if (recoveries.containsKey(key)) {
                            throw new DuplicateKeyException("duplicate recovery");
                        }
                        recoveries.put(key, recovery);
                        yield 1;
                    }
                    case "update" -> 1;
                    case "pollable" -> recoveries.values().stream()
                            .filter(row -> "ACCEPTED".equals(row.getStatus()) || "POLLING".equals(row.getStatus()))
                            .toList();
                    default -> unsupported(method);
                });

        DolphinSchedulerNodeExecutor executor = new DolphinSchedulerNodeExecutor(null, null, null, new ObjectMapper()) {
            @Override
            public DolphinSchedulerBinding bindingFor(WorkflowNodeRun ignored, com.datakhaos.workflow.executor.NodeContext context) {
                return new DolphinSchedulerBinding(10, 20, 1, "wf-1", "ds", false);
            }
        };
        service = new WorkflowRecoveryService(runMapper, nodeRunMapper, nodeMapper, edgeMapper, defMapper,
                access, recoveryMapper, lifecycle, runner, client, executor, new ObjectMapper());
    }

    @Test
    void permissionFailureStopsBeforeAnyRemoteCall() {
        access.error = new BusinessException(ResultCode.UNAUTHORIZED);
        assertThrows(BusinessException.class, () -> service.recover("run-1", "node-run-1"));
        assertEquals(0, client.definitionQueries.get());
        assertEquals(0, client.instanceQueries.get());
        assertEquals(0, client.posts.get());
    }

    @Test
    void unassociatedNodeIsRejectedBeforeRemoteCall() {
        nodeRun.setRefType(null);
        nodeRun.setRefId(null);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.recover("run-1", "node-run-1"));
        assertTrue(error.getMessage().contains("不是已关联实例"));
        assertEquals(0, client.definitionQueries.get());
        assertEquals(0, client.posts.get());
    }

    @Test
    void runningRunIsRejectedBeforeRemoteCall() {
        run.setRunStatus("RUNNING");
        assertThrows(BusinessException.class, () -> service.recover("run-1", "node-run-1"));
        assertEquals(0, client.definitionQueries.get());
        assertEquals(0, client.posts.get());
    }

    @Test
    void remoteRunningStateIsRejectedWithoutPost() {
        client.state = "RUNNING_EXECUTION";
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.recover("run-1", "node-run-1"));
        assertTrue(error.getMessage().contains("仍在执行"));
        assertEquals(0, client.posts.get());
    }

    @Test
    void remoteSuccessStateIsRejectedWithoutPost() {
        client.state = "SUCCESS";
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.recover("run-1", "node-run-1"));
        assertTrue(error.getMessage().contains("已成功"));
        assertEquals(0, client.posts.get());
    }

    @Test
    void repeatedRecoverySubmitsOnlyOnce() {
        client.state = "FAILURE";
        var first = service.recover("run-1", "node-run-1");
        assertEquals("ACCEPTED", first.status());
        assertEquals(1, client.posts.get());
        assertEquals(17L, first.remoteInstanceId());

        client.state = "FAILURE";
        var second = service.recover("run-1", "node-run-1");
        assertEquals("ACCEPTED", second.status());
        assertEquals(1, client.posts.get());
    }

    @Test
    void uncertainSubmitWithRunningRemoteIsAcceptedWithoutDuplicatePost() {
        client.state = "FAILURE";
        client.submitThrows = true;
        var result = service.recover("run-1", "node-run-1");
        assertEquals("ACCEPTED", result.status());
        assertEquals(1, client.posts.get());
        assertTrue(result.message().contains("已开始执行"));
    }

    @Test
    void pollSuccessMarksNodeAndResumesLocalDownstream() {
        client.state = "SUCCESS";
        WorkflowExternalRecovery recovery = new WorkflowExternalRecovery();
        recovery.setId("recovery-1");
        recovery.setRunId("run-1");
        recovery.setNodeRunId("node-run-1");
        recovery.setWfId("wf-1");
        recovery.setProjectId("p1");
        recovery.setRuntime("DOLPHINSCHEDULER");
        recovery.setRemoteProjectCode(10L);
        recovery.setRemoteInstanceId(17L);
        recovery.setAction("START_FAILURE_TASK_PROCESS");
        recovery.setStatus("ACCEPTED");
        recovery.setCreateTime(LocalDateTime.now().minusMinutes(1));
        recovery.setUpdateTime(LocalDateTime.now().minusSeconds(10));
        recoveries.put("node-run-1|START_FAILURE_TASK_PROCESS", recovery);

        service.pollOne("recovery-1");

        assertEquals("SUCCEEDED", recovery.getStatus());
        assertEquals("RESUMING", recovery.getLocalResumeStatus());
        assertEquals(1, lifecycle.prepared.get());
        assertEquals(1, lifecycle.recovered.get());
        assertEquals(List.of("downstream"), lifecycle.resetCodes);
        assertEquals(1, runner.resumed.get());
        assertNull(runner.finishedSuccess);
    }

    @Test
    void pollFailureDoesNotResumeLocalDownstream() {
        client.state = "FAILURE";
        WorkflowExternalRecovery recovery = new WorkflowExternalRecovery();
        recovery.setId("recovery-1");
        recovery.setRunId("run-1");
        recovery.setNodeRunId("node-run-1");
        recovery.setWfId("wf-1");
        recovery.setAction("START_FAILURE_TASK_PROCESS");
        recovery.setStatus("ACCEPTED");
        recovery.setCreateTime(LocalDateTime.now().minusMinutes(1));
        recovery.setUpdateTime(LocalDateTime.now().minusSeconds(10));
        recoveries.put("node-run-1|START_FAILURE_TASK_PROCESS", recovery);

        service.pollOne("recovery-1");

        assertEquals("FAILED", recovery.getStatus());
        assertEquals(0, lifecycle.prepared.get());
        assertEquals(0, runner.resumed.get());
    }

    private static Object unsupported(java.lang.reflect.Method method) {
        throw new UnsupportedOperationException(method.getName());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static class FakeClient extends DolphinSchedulerClient {
        private final AtomicInteger posts = new AtomicInteger();
        private final AtomicInteger instanceQueries = new AtomicInteger();
        private final AtomicInteger definitionQueries = new AtomicInteger();
        private volatile String state = "FAILURE";
        private volatile boolean submitThrows;

        FakeClient(ObjectMapper json) {
            super(json, "", "");
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public RemoteInstance queryInstance(DolphinSchedulerBinding binding, String instanceId) {
            instanceQueries.incrementAndGet();
            return new RemoteInstance(state, 2);
        }

        @Override
        public RemoteDefinition queryDefinition(DolphinSchedulerBinding binding) {
            definitionQueries.incrementAndGet();
            return new RemoteDefinition(1, "ONLINE", 10);
        }

        @Override
        public void recoverFailure(DolphinSchedulerBinding binding, String instanceId) {
            posts.incrementAndGet();
            if (submitThrows) {
                state = "RUNNING_EXECUTION";
                throw new IllegalStateException("timeout");
            }
            state = "RUNNING_EXECUTION";
        }
    }

    private static class FakeAccess extends WorkflowProjectAccess {
        private RuntimeException error;

        FakeAccess() {
            super(null, null);
        }

        @Override
        public void requireAction(String projectId, String capability) {
            if (error != null) {
                throw error;
            }
        }
    }

    private static class FakeLifecycle extends WorkflowRunLifecycle {
        private final AtomicInteger prepared = new AtomicInteger();
        private final AtomicInteger recovered = new AtomicInteger();
        private List<String> resetCodes = List.of();

        FakeLifecycle() {
            super(null);
        }

        @Override
        public boolean prepareResume(String runId, LocalDateTime now) {
            prepared.incrementAndGet();
            return true;
        }

        @Override
        public int markNodeRecovered(String nodeRunId, String logText, long durationMs, LocalDateTime now) {
            recovered.incrementAndGet();
            return 1;
        }

        @Override
        public int resetSkippedNodes(String runId, List<String> nodeCodes, LocalDateTime now) {
            resetCodes = nodeCodes;
            return nodeCodes.size();
        }

        @Override
        public void failResume(String runId, String reason, LocalDateTime now) {
        }
    }

    private static class FakeRunner extends WorkflowRunner {
        private final AtomicInteger resumed = new AtomicInteger();
        private Boolean finishedSuccess;

        FakeRunner() {
            super(null, null, null, null, null, null, null, null, null);
        }

        @Override
        public void resumeAsync(String runId, String wfId, Map<String, Object> params, String bizDate,
                                Set<String> resumeScope) {
            resumed.incrementAndGet();
        }
    }
}

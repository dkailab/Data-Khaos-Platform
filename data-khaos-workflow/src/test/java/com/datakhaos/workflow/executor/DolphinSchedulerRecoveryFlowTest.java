package com.datakhaos.workflow.executor;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.datakhaos.workflow.entity.WorkflowDef;
import com.datakhaos.workflow.entity.WorkflowEdge;
import com.datakhaos.workflow.entity.WorkflowExternalRecovery;
import com.datakhaos.workflow.entity.WorkflowNode;
import com.datakhaos.workflow.entity.WorkflowNodeRun;
import com.datakhaos.workflow.entity.WorkflowRun;
import com.datakhaos.workflow.mapper.WorkflowDefMapper;
import com.datakhaos.workflow.mapper.WorkflowEdgeMapper;
import com.datakhaos.workflow.mapper.WorkflowExternalRecoveryMapper;
import com.datakhaos.workflow.mapper.WorkflowNodeMapper;
import com.datakhaos.workflow.mapper.WorkflowNodeRunMapper;
import com.datakhaos.workflow.mapper.WorkflowRunMapper;
import com.datakhaos.workflow.runner.WorkflowRunLifecycle;
import com.datakhaos.workflow.runner.WorkflowRunner;
import com.datakhaos.workflow.service.DolphinSchedulerBindingResolver;
import com.datakhaos.workflow.service.WorkflowProjectAccess;
import com.datakhaos.workflow.service.WorkflowRecoveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模拟 Open API 上的完整恢复链路：远端失败 → 工作台恢复 → 远端成功 →
 * 本地节点回写并续跑下游。验证阶段 2 的核心验收语义，不依赖真实 DolphinScheduler。
 */
class DolphinSchedulerRecoveryFlowTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, WorkflowNodeRun> nodeRuns = new HashMap<>();
    private final Map<String, WorkflowExternalRecovery> recoveries = new HashMap<>();
    private final AtomicInteger posts = new AtomicInteger();
    private final AtomicInteger recoveryPosts = new AtomicInteger();
    private final AtomicInteger nodeUpdates = new AtomicInteger();
    private final AtomicInteger prepared = new AtomicInteger();
    private final AtomicInteger resumed = new AtomicInteger();
    private volatile String remoteState = "FAILURE";
    private volatile int remoteRunTimes = 1;
    private HttpServer server;
    private WorkflowNodeRun dsRun;
    private WorkflowNodeRun downstreamRun;
    private DolphinSchedulerNodeExecutor executor;
    private WorkflowRecoveryService recoveryService;

    @BeforeEach
    void init() throws IOException {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "flow"),
                WorkflowNodeRun.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "flow"),
                WorkflowExternalRecovery.class);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dolphinscheduler/projects/10/executors/start-workflow-instance", exchange -> {
            posts.incrementAndGet();
            respond(exchange, "{\"code\":0,\"data\":[17]}");
        });
        server.createContext("/dolphinscheduler/projects/10/executors/execute", exchange -> {
            recoveryPosts.incrementAndGet();
            remoteState = "RUNNING_EXECUTION";
            remoteRunTimes++;
            respond(exchange, "{\"code\":0,\"data\":null}");
        });
        server.createContext("/dolphinscheduler/projects/10/workflow-instances/17", exchange ->
                respond(exchange, "{\"code\":0,\"data\":{\"state\":\"" + remoteState
                        + "\",\"runTimes\":" + remoteRunTimes + "}}"));
        server.createContext("/dolphinscheduler/projects/10/workflow-definition/20", exchange ->
                respond(exchange, "{\"code\":0,\"data\":{\"workflowDefinition\":{\"version\":1,"
                        + "\"releaseState\":\"ONLINE\",\"projectCode\":10}}}"));
        server.start();

        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        run.setWfId("wf-1");
        run.setProjectGroupId("p1");
        run.setRunStatus("FAILED");
        run.setBizDate("2026-11-28");
        run.setTriggerParams("{}");

        WorkflowDef def = new WorkflowDef();
        def.setId("wf-1");
        def.setProjectId("p1");

        WorkflowNode node = new WorkflowNode();
        node.setId("node-1");
        node.setWfId("wf-1");
        node.setNodeCode("ds");
        node.setNodeType(NodeType.DOLPHINSCHEDULER);

        WorkflowNode downstream = new WorkflowNode();
        downstream.setId("node-2");
        downstream.setWfId("wf-1");
        downstream.setNodeCode("downstream");
        downstream.setNodeType(NodeType.SHELL);

        dsRun = new WorkflowNodeRun();
        dsRun.setId("node-run-1");
        dsRun.setRunId("run-1");
        dsRun.setWfId("wf-1");
        dsRun.setNodeCode("ds");
        dsRun.setNodeType(NodeType.DOLPHINSCHEDULER);
        dsRun.setStatus("PENDING");
        dsRun.setStartTime(LocalDateTime.now().minusMinutes(3));
        nodeRuns.put(dsRun.getId(), dsRun);

        downstreamRun = new WorkflowNodeRun();
        downstreamRun.setId("node-run-2");
        downstreamRun.setRunId("run-1");
        downstreamRun.setWfId("wf-1");
        downstreamRun.setNodeCode("downstream");
        downstreamRun.setNodeType(NodeType.SHELL);
        downstreamRun.setStatus("SKIPPED");
        nodeRuns.put(downstreamRun.getId(), downstreamRun);

        WorkflowEdge edge = new WorkflowEdge();
        edge.setWfId("wf-1");
        edge.setFromCode("ds");
        edge.setToCode("downstream");

        WorkflowNodeRunMapper nodeRunMapper = proxy(WorkflowNodeRunMapper.class, (proxy, method, args) ->
                switch (method.getName()) {
                    case "selectById" -> nodeRuns.get(args[0]);
                    case "selectList" -> List.of(downstreamRun);
                    case "update" -> {
                        // 执行器的两次更新：领取提交（SUBMITTING）与写入远端实例编号。
                        nodeUpdates.incrementAndGet();
                        if (dsRun.getRefId() == null) {
                            dsRun.setRefType(NodeType.DOLPHINSCHEDULER);
                            dsRun.setRefId(DolphinSchedulerNodeExecutor.SUBMITTING);
                        } else {
                            dsRun.setRefId("17");
                        }
                        yield 1;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        WorkflowExternalRecoveryMapper recoveryMapper = proxy(WorkflowExternalRecoveryMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "find" -> recoveries.get(args[0] + "|" + args[1]);
                    case "selectById" -> recoveries.values().stream()
                            .filter(row -> row.getId().equals(args[0])).findFirst().orElse(null);
                    case "insert" -> {
                        WorkflowExternalRecovery row = (WorkflowExternalRecovery) args[0];
                        row.setId("recovery-1");
                        recoveries.put(row.getNodeRunId() + "|" + row.getAction(), row);
                        yield 1;
                    }
                    case "update" -> 1;
                    case "pollable" -> recoveries.values().stream()
                            .filter(row -> "ACCEPTED".equals(row.getStatus()) || "POLLING".equals(row.getStatus()))
                            .toList();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        WorkflowRunMapper runMapper = proxy(WorkflowRunMapper.class, (proxy, method, args) -> run);
        WorkflowDefMapper defMapper = proxy(WorkflowDefMapper.class, (proxy, method, args) -> def);
        WorkflowNodeMapper nodeMapper = proxy(WorkflowNodeMapper.class,
                (proxy, method, args) -> List.of(node, downstream));
        WorkflowEdgeMapper edgeMapper = proxy(WorkflowEdgeMapper.class,
                (proxy, method, args) -> List.of(edge));

        DolphinSchedulerClient client = new DolphinSchedulerClient(json,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/dolphinscheduler", "test-token");
        DolphinSchedulerBindingResolver bindings = new DolphinSchedulerBindingResolver(
                null, json, "wf-1", "ds", 10, 20, 1);
        executor = new DolphinSchedulerNodeExecutor(nodeRunMapper, client, bindings, json);

        WorkflowRunLifecycle lifecycle = new WorkflowRunLifecycle(null) {
            @Override
            public boolean prepareResume(String runId, LocalDateTime now) {
                prepared.incrementAndGet();
                run.setRunStatus("RUNNING");
                return true;
            }

            @Override
            public int markNodeRecovered(String nodeRunId, String logText, long durationMs, LocalDateTime now) {
                dsRun.setStatus("SUCCESS");
                return 1;
            }

            @Override
            public int resetSkippedNodes(String runId, List<String> nodeCodes, LocalDateTime now) {
                nodeCodes.forEach(code -> nodeRuns.values().stream()
                        .filter(item -> code.equals(item.getNodeCode()))
                        .forEach(item -> item.setStatus("PENDING")));
                return nodeCodes.size();
            }

            @Override
            public void failResume(String runId, String reason, LocalDateTime now) {
                run.setRunStatus("RECOVERY_REQUIRED");
            }
        };
        WorkflowRunner runner = new WorkflowRunner(null, null, null, null, null, null, null, null, null) {
            @Override
            public void resumeAsync(String runId, String wfId, Map<String, Object> params, String bizDate,
                                    Set<String> resumeScope) {
                resumed.incrementAndGet();
                downstreamRun.setStatus("SUCCESS");
                run.setRunStatus("SUCCESS");
            }
        };
        WorkflowProjectAccess access = new WorkflowProjectAccess(null, null) {
            @Override
            public void requireAction(String projectId, String capability) {
            }
        };
        recoveryService = new WorkflowRecoveryService(runMapper, nodeRunMapper, nodeMapper, edgeMapper,
                defMapper, access, recoveryMapper, lifecycle, runner, client, executor, json);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void failedRemoteNodeIsRecoveredAndLocalDownstreamResumes() throws Exception {
        WorkflowNode contextNode = new WorkflowNode();
        contextNode.setWfId("wf-1");
        contextNode.setNodeCode("ds");
        contextNode.setNodeType(NodeType.DOLPHINSCHEDULER);
        NodeContext context = NodeContext.builder().node(contextNode)
                .runId("run-1").nodeRunId("node-run-1").bizDate("2026-11-28").timeoutSeconds(1L).build();

        // 1) 远端节点执行失败，本地节点由运行器标记为 FAILED。
        assertFalse(executor.execute(context).isSuccess());

        // 复现运行器写入的状态；映射与实例编号保持执行器提交后的值。
        dsRun.setStatus("FAILED");
        assertEquals("17", dsRun.getRefId());
        assertEquals(NodeType.DOLPHINSCHEDULER, dsRun.getRefType());
        assertEquals(1, posts.get());

        // 2) 从工作台发起恢复。
        var accepted = recoveryService.recover("run-1", "node-run-1");
        assertEquals("ACCEPTED", accepted.status());
        assertEquals(17L, accepted.remoteInstanceId());
        assertEquals(1, recoveryPosts.get());
        assertEquals("17", dsRun.getRefId());

        // 3) 远端恢复完成后轮询收敛，本地下游续跑。
        remoteState = "SUCCESS";
        recoveryService.pollOne("recovery-1");

        var latest = recoveryService.latest("run-1", "node-run-1");
        // 注意：内存假 mapper 返回同一对象，只断言方法显式回写的字段；
        // remoteStateAfter 等由 SQL SET 写入的字段由真实 Mapper 重新读取后返回。
        assertEquals("SUCCEEDED", latest.status());
        assertEquals("RESUMING", latest.localResumeStatus());
        assertEquals(1, prepared.get());
        assertEquals(1, resumed.get());
        assertEquals("SUCCESS", dsRun.getStatus());
        assertEquals("SUCCESS", downstreamRun.getStatus());
        assertEquals(2, remoteRunTimes);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

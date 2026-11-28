package com.datakhaos.workflow.executor;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.workflow.entity.WorkflowNode;
import com.datakhaos.workflow.entity.WorkflowNodeRun;
import com.datakhaos.workflow.mapper.WorkflowNodeRunMapper;
import com.datakhaos.workflow.service.DolphinSchedulerBindingResolver;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DolphinSchedulerNodeExecutorTest {
    private final WorkflowNodeRun run = new WorkflowNodeRun();
    private final WorkflowNode node = new WorkflowNode();
    private final AtomicInteger posts = new AtomicInteger();
    private final AtomicInteger gets = new AtomicInteger();
    private final AtomicInteger recoveries = new AtomicInteger();
    private final AtomicReference<String> form = new AtomicReference<>();
    private final AtomicReference<String> recoveryForm = new AtomicReference<>();
    private volatile String response = "{\"code\":0,\"data\":[17]}";
    private volatile String state = "SUCCESS";
    private volatile String definitionVersion = "1";
    private HttpServer server;
    private DolphinSchedulerClient client;
    private DolphinSchedulerNodeExecutor executor;

    @BeforeEach
    void start() throws IOException {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "pilot"),
                WorkflowNodeRun.class);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dolphinscheduler/projects/10/executors/start-workflow-instance", exchange -> {
            posts.incrementAndGet();
            form.set(URLDecoder.decode(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8));
            respond(exchange, response);
        });
        server.createContext("/dolphinscheduler/projects/10/executors/execute", exchange -> {
            recoveries.incrementAndGet();
            recoveryForm.set(URLDecoder.decode(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8), StandardCharsets.UTF_8));
            respond(exchange, "{\"code\":0,\"data\":null}");
        });
        server.createContext("/dolphinscheduler/projects/10/workflow-instances/17", exchange -> {
            gets.incrementAndGet();
            respond(exchange, "{\"code\":0,\"data\":{\"state\":\"" + state + "\",\"runTimes\":2}}");
        });
        server.createContext("/dolphinscheduler/projects/10/workflow-definition/20", exchange -> respond(exchange,
                "{\"code\":0,\"data\":{\"workflowDefinition\":{\"version\":" + definitionVersion
                        + ",\"releaseState\":\"ONLINE\",\"projectCode\":10}}}"));
        server.start();
        WorkflowNodeRunMapper mapper = (WorkflowNodeRunMapper) Proxy.newProxyInstance(
                WorkflowNodeRunMapper.class.getClassLoader(), new Class<?>[] {WorkflowNodeRunMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectById" -> run;
                    case "update" -> {
                        run.setRefType(NodeType.DOLPHINSCHEDULER);
                        run.setRefId(run.getRefId() == null ? "SUBMITTING" : "17");
                        yield 1;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ObjectMapper json = new ObjectMapper();
        client = new DolphinSchedulerClient(json,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/dolphinscheduler", "test-token");
        DolphinSchedulerBindingResolver bindings = new DolphinSchedulerBindingResolver(
                null, json, "workflow-1", "pilot", 10, 20, 1);
        executor = new DolphinSchedulerNodeExecutor(mapper, client, bindings, json);
        run.setId("node-run-1");
        run.setRunId("run-1");
        run.setWfId("workflow-1");
        run.setNodeCode("pilot");
        run.setNodeType(NodeType.DOLPHINSCHEDULER);
        node.setWfId("workflow-1");
        node.setNodeCode("pilot");
        node.setNodeType(NodeType.DOLPHINSCHEDULER);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void submitsOnceAndPollsTheSameInstanceOnRetry() throws Exception {
        assertTrue(executor.execute(context("2026-11-28")).isSuccess());
        assertTrue(executor.execute(context("2026-11-28")).isSuccess());
        assertEquals(1, posts.get());
        assertEquals(2, gets.get());
        assertTrue(form.get().contains("workflowDefinitionCode=20"));
        assertTrue(form.get().contains("\"bizDate\":\"2026-11-28\""));
        assertTrue(form.get().contains("\"dataKhaosNodeRunId\":\"node-run-1\""));
    }

    @Test
    void uncertainSubmissionIsNeverPostedAgain() {
        response = "{\"code\":0,\"data\":[]}";
        assertThrows(DolphinSchedulerNodeExecutor.UnconfirmedExecutionException.class,
                () -> executor.execute(context("2026-11-28")));
        assertThrows(DolphinSchedulerNodeExecutor.UnconfirmedExecutionException.class,
                () -> executor.execute(context("2026-11-28")));
        assertEquals(1, posts.get());
        assertEquals(0, gets.get());
    }

    @Test
    void reportsRemoteFailureWithoutResubmission() throws Exception {
        run.setRefType(NodeType.DOLPHINSCHEDULER);
        run.setRefId("17");
        state = "FAILURE";
        assertFalse(executor.execute(context("2026-11-28")).isSuccess());
        assertEquals(0, posts.get());
    }

    @Test
    void readsBoundInstanceStateWithoutSubmitting() {
        run.setRefType(NodeType.DOLPHINSCHEDULER);
        run.setRefId("17");
        state = "RUNNING_EXECUTION";
        assertEquals("RUNNING_EXECUTION", executor.queryState(run));
        run.setRefId("SUBMITTING");
        assertThrows(BusinessException.class, () -> executor.queryState(run));
        assertEquals(1, gets.get());
        assertEquals(0, posts.get());
    }

    @Test
    void readsTheOriginalInstanceAfterRemoteRecovery() {
        state = "PAUSE";
        assertThrows(DolphinSchedulerNodeExecutor.UnconfirmedExecutionException.class,
                () -> executor.execute(context("2026-11-28")));
        state = "SUCCESS";
        assertTrue(executor.execute(context("2026-11-28")).isSuccess());
        assertEquals(1, posts.get());
        assertEquals(2, gets.get());
    }

    @Test
    void interruptedPollingRetainsTheInstanceAndInterruptFlag() {
        run.setRefType(NodeType.DOLPHINSCHEDULER);
        run.setRefId("17");
        state = "RUNNING_EXECUTION";
        Thread.currentThread().interrupt();
        try {
            assertThrows(DolphinSchedulerNodeExecutor.UnconfirmedExecutionException.class,
                    () -> executor.execute(context("2026-11-28")));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals("17", run.getRefId());
            assertEquals(0, posts.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsUnboundWorkflowBeforeCallingRemote() {
        run.setWfId("other");
        assertThrows(BusinessException.class, () -> executor.execute(context("2026-11-28")));
        assertEquals(0, posts.get());
    }

    @Test
    void rejectsDefinitionVersionMismatchBeforeSubmitting() {
        definitionVersion = "2";
        BusinessException error = assertThrows(BusinessException.class,
                () -> executor.execute(context("2026-11-28")));
        assertTrue(error.getMessage().contains("版本不一致"));
        assertEquals(0, posts.get());
    }

    @Test
    void recoveryAcceptsResponseWithoutData() {
        DolphinSchedulerBinding binding = new DolphinSchedulerBinding(10, 20, 1, "workflow-1", "pilot", true);
        client.recoverFailure(binding, "17");
        assertEquals(1, recoveries.get());
        assertTrue(recoveryForm.get().contains("workflowInstanceId=17"));
        assertTrue(recoveryForm.get().contains("executeType=START_FAILURE_TASK_PROCESS"));
    }

    private NodeContext context(String date) {
        return NodeContext.builder().node(node).runId("run-1").nodeRunId("node-run-1")
                .timeoutSeconds(1L).bizDate(date).build();
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

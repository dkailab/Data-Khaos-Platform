package com.datakhaos.workflow.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.AuthUser;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.workflow.entity.FlinkDeployment;
import com.datakhaos.workflow.entity.FlinkDeployment.State;
import com.datakhaos.workflow.flink.FlinkRuntimeClient;
import com.datakhaos.workflow.mapper.FlinkDeploymentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FlinkSubmissionRecoveryTest {
    private static final String SESSION = "55789b76-6383-4dc2-bd77-78a67ebae73c";
    private static final String OPERATION = "aa43f536-2663-4bf5-a803-b9c35e752951";
    private static final String JOB = "11111111111111111111111111111111";
    private final List<Map<String, Object>> updates = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();
    private final FlinkDeployment row = new FlinkDeployment();
    private FlinkDeploymentService service;
    private boolean newSubmission;
    private boolean inserted;
    private boolean gatewayUnavailable;
    private boolean jobDetailUnavailable;
    private boolean denied;
    private boolean updateWon = true;
    private Optional<FlinkRuntimeClient.EngineJob> overview = Optional.empty();
    private Optional<String> gatewayJobId = Optional.empty();

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                FlinkDeployment.class);
        MetadataHolder.set(AuthUser.builder().id("operator").build());
        var runtime = new FlinkRuntimeClient(new ObjectMapper(), "retail", "http://gateway", "http://jobmanager") {
            @Override
            public String open(String name, String recoveryPath) {
                return SESSION;
            }

            @Override
            public void awaitSetup(String session, String statement, long deadline) {
                assertEquals(SESSION, session);
            }

            @Override
            public String submitInsert(String session, String statement, long deadline) {
                calls.add("submit");
                assertEquals(1, updates.size());
                assertTrue(updates.get(0).containsValue(State.SUBMITTING));
                return OPERATION;
            }

            @Override
            public Optional<String> awaitJobId(String session, String operation, long deadline) {
                calls.add("await");
                assertEquals(2, updates.size());
                assertTrue(updates.get(1).containsValue(SESSION));
                assertTrue(updates.get(1).containsValue(OPERATION));
                return Optional.empty();
            }

            @Override
            public SubmissionResult submissionResult(String session, String operation) {
                calls.add("result");
                assertEquals(SESSION, session);
                assertEquals(OPERATION, operation);
                if (gatewayUnavailable) throw new RuntimeFailure("gateway unavailable");
                return new SubmissionResult(gatewayJobId, gatewayJobId.isEmpty());
            }

            @Override
            public EngineJob job(String id) {
                calls.add("job");
                assertEquals(JOB, id);
                if (jobDetailUnavailable) throw new RuntimeFailure("job details unavailable");
                return new EngineJob(id, "FINISHED");
            }

            @Override
            public Optional<EngineJob> findByName(String name) {
                calls.add("overview");
                assertEquals("dk-flink-run-1", name);
                return overview;
            }

            @Override
            public void close(String session) {
                calls.add("close");
                assertEquals(SESSION, session);
            }
        };

        row.setId("run-1");
        row.setProjectId("retail");
        row.setAssetId("orders");
        row.setVersionNumber(1);
        row.setState(State.RECONCILING);
        row.setRowVersion(7);
        row.setClusterKey(runtime.clusterKey());
        row.setGatewaySessionHandle(SESSION);
        row.setGatewayOperationHandle(OPERATION);

        FlinkDeploymentMapper mapper = (FlinkDeploymentMapper) Proxy.newProxyInstance(
                FlinkDeploymentMapper.class.getClassLoader(), new Class<?>[]{FlinkDeploymentMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectOne" -> newSubmission && !inserted ? null : row;
                    case "insert" -> {
                        inserted = true;
                        ((FlinkDeployment) args[0]).setId("run-1");
                        yield 1;
                    }
                    case "update" -> {
                        var wrapper = (LambdaUpdateWrapper<?>) args[1];
                        assertTrue(wrapper.getSqlSet().contains("gateway_session_handle"));
                        assertTrue(wrapper.getSqlSet().contains("gateway_operation_handle"));
                        assertTrue(wrapper.getSqlSegment().contains("row_version"));
                        updates.add(wrapper.getParamNameValuePairs());
                        yield updateWon ? 1 : 0;
                    }
                    default -> throw new AssertionError(method.getName());
                });
        var access = new WorkflowProjectAccess(null, null) {
            @Override
            public void requireAction(String project, String capability) {
                assertEquals("retail", project);
                assertTrue(capability.equals("task:develop")
                        || (newSubmission && capability.equals("task:schedule")));
                if (denied) throw new BusinessException(ResultCode.FORBIDDEN);
            }
        };
        var studio = new FlinkStudioService(null, null, access, new ObjectMapper()) {
            @Override
            public String exportSql(String projectId, String assetId, int version) {
                return "INSERT INTO target SELECT * FROM source;";
            }
        };
        service = new FlinkDeploymentService(mapper, null, studio, access, runtime);
    }

    @AfterEach
    void clearUser() {
        MetadataHolder.clear();
    }

    @Test
    void persistsReceiptBeforePollingAndRetainsItDuringReconciliation() {
        newSubmission = true;
        service.submit("retail", "orders", 1, "22a14241-f6a6-4a0e-a1cd-6954eb071d90");
        assertEquals(List.of("submit", "await"), calls);
        assertEquals(3, updates.size());
        assertTrue(updates.get(2).containsValue(State.RECONCILING));
        assertTrue(updates.get(2).containsValue(SESSION));
        assertTrue(updates.get(2).containsValue(OPERATION));
    }

    @Test
    void recoversJobFromPersistedReceiptAndClosesSessionAfterWritingTerminalState() {
        gatewayJobId = Optional.of(JOB);
        refresh();
        assertEquals(List.of("result", "job", "close"), calls);
        assertResolved(State.FINISHED);
    }

    @Test
    void unavailableJobDetailsStillPreserveConfirmedGatewayJobId() {
        gatewayJobId = Optional.of(JOB);
        jobDetailUnavailable = true;
        refresh();
        assertEquals(List.of("result", "job", "close"), calls);
        assertResolved(State.SUBMITTED);
    }

    @Test
    void expiredGatewaySessionFallsBackToJobName() {
        gatewayUnavailable = true;
        overview = Optional.of(new FlinkRuntimeClient.EngineJob(JOB, "RUNNING"));
        refresh();
        assertEquals(List.of("result", "overview"), calls);
        assertResolved(State.SUBMITTED);
    }

    @Test
    void noEvidenceLeavesTheActiveLockInPlace() {
        gatewayUnavailable = true;
        refresh();
        assertEquals(List.of("result", "overview"), calls);
        assertTrue(updates.isEmpty());
    }

    @Test
    void oldRunWithoutReceiptUsesNameLookup() {
        row.setGatewaySessionHandle(null);
        row.setGatewayOperationHandle(null);
        overview = Optional.of(new FlinkRuntimeClient.EngineJob(JOB, "FINISHED"));
        refresh();
        assertEquals(List.of("overview"), calls);
        assertResolved(State.FINISHED);
    }

    @Test
    void losingVersionCheckDoesNotCloseTheSession() {
        gatewayJobId = Optional.of(JOB);
        updateWon = false;
        refresh();
        assertEquals(List.of("result", "job"), calls);
    }

    @Test
    void rejectsCallerWithoutDevelopCapabilityBeforeGatewayAccess() {
        denied = true;
        assertThrows(BusinessException.class, this::refresh);
        assertTrue(calls.isEmpty());
        assertTrue(updates.isEmpty());
    }

    private void refresh() {
        service.refresh("retail", "orders", "run-1");
    }

    private void assertResolved(State state) {
        assertEquals(1, updates.size());
        assertTrue(updates.get(0).containsValue(state));
        assertTrue(updates.get(0).containsValue(JOB));
        assertFalse(updates.get(0).containsValue(SESSION));
        assertFalse(updates.get(0).containsValue(OPERATION));
    }
}

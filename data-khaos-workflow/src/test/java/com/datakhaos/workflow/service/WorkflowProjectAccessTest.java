package com.datakhaos.workflow.service;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.R;
import com.datakhaos.permission.api.model.ProjectWorkspaceDto;
import com.datakhaos.workflow.dto.BackfillRequest;
import com.datakhaos.workflow.dto.RunRequest;
import com.datakhaos.workflow.entity.WorkflowDef;
import com.datakhaos.workflow.mapper.WorkflowBackfillBatchMapper;
import com.datakhaos.workflow.mapper.WorkflowDefMapper;
import com.datakhaos.workflow.runner.WorkflowRunner;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowProjectAccessTest {
    private static final String PROJECT = "retail";
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final AtomicInteger lookups = new AtomicInteger();
    private final AtomicInteger submissions = new AtomicInteger();
    private boolean outage;
    private R<ProjectWorkspaceDto> response;
    private WorkflowProjectAccess access;
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        var beans = new StaticListableBeanFactory(Map.of("request", request));
        access = new WorkflowProjectAccess(new RestTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> ResponseEntity<T> exchange(String url, HttpMethod method, HttpEntity<?> entity,
                                                  ParameterizedTypeReference<T> type, Object... args) {
                lookups.incrementAndGet();
                assertEquals("Bearer sample-token", entity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
                assertEquals(PROJECT, args[0]);
                if (outage) throw new ResourceAccessException("permission unavailable");
                return (ResponseEntity<T>) ResponseEntity.ok(response);
            }
        }, beans.getBeanProvider(HttpServletRequest.class));

        WorkflowDef definition = new WorkflowDef();
        definition.setId("orders");
        definition.setName("订单任务");
        definition.setProjectId(PROJECT);
        definition.setStatus(1);
        WorkflowDefMapper definitions = (WorkflowDefMapper) Proxy.newProxyInstance(WorkflowDefMapper.class.getClassLoader(),
                new Class<?>[]{WorkflowDefMapper.class}, (proxy, method, args) -> {
                    assertEquals("selectById", method.getName());
                    return definition;
                });
        WorkflowRunner runner = new WorkflowRunner(null, null, null, null, null, null, null, null, null) {
            @Override
            public String createRunGraph(String wfId, String triggerType, Map<String, Object> params, String wfName,
                                         String projectId, String jobId, String logId, String requestId,
                                         String bizDate, String requestParams, String requestHash,
                                         LocalDateTime scheduledAt, java.util.List<String> startCodes) {
                assertEquals(requestId.startsWith("range-1:") ? "BACKFILL" : "MANUAL", triggerType);
                assertEquals(PROJECT, projectId);
                assertEquals(bizDate, params.get("bizDate"));
                return "run-" + submissions.incrementAndGet();
            }

            @Override
            public void executeAsync(String runId, String wfId, Map<String, Object> params, String bizDate,
                                     java.util.List<String> startCodes) {
                assertTrue(runId.startsWith("run-"));
            }
        };
        WorkflowBackfillBatchMapper batches = new WorkflowBackfillBatchMapper() {
            @Override
            public void reserve(String wfId, String requestId, java.time.LocalDate from,
                                java.time.LocalDate to, String hash) {
            }

            @Override
            public boolean matches(String wfId, String requestId, java.time.LocalDate from,
                                   java.time.LocalDate to, String hash) {
                return true;
            }
        };
        WorkflowUpstreamService upstreams = new WorkflowUpstreamService(null, null, null, null) {
            @Override
            public java.util.List<String> blockers(String wfId, String bizDate) {
                return java.util.List.of();
            }

            @Override
            public Map<String, Object> mergeParams(String wfId, Map<String, Object> params, String bizDate) {
                return params;
            }
        };
        service = new WorkflowService(definitions, null, null, null, runner, access, batches, upstreams, null, null);
    }

    @Test
    void rejectsMissingTokenAndScopeMismatchWithoutCallingPermission() {
        assertEquals(401, assertThrows(BusinessException.class,
                () -> access.requireAction(PROJECT, "task:develop")).getCode());
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer sample-token");
        request.addHeader("X-Project-Group-Id", "marketing");
        assertEquals(404, assertThrows(BusinessException.class,
                () -> access.requireAction(PROJECT, "task:develop")).getCode());
        assertEquals(0, lookups.get());
    }

    @Test
    void failsClosedOnPermissionOutageOrIncorrectProject() {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer sample-token");
        outage = true;
        assertEquals(5000, assertThrows(BusinessException.class,
                () -> service.runByRequest("orders", day())).getCode());
        outage = false;
        response = R.ok(new ProjectWorkspaceDto("marketing", "营销", false, "LEAD", List.of("task:develop")));
        assertEquals(5000, assertThrows(BusinessException.class,
                () -> service.runByRequest("orders", day())).getCode());
        assertEquals(0, submissions.get());
    }

    @Test
    void manualRunAndBackfillUseDifferentCapabilitiesAndRetainAcceptanceContract() {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer sample-token");
        response = R.ok(new ProjectWorkspaceDto(PROJECT, "零售", true, "DEV", List.of("task:develop")));
        assertEquals("run-1", service.runByRequest("orders", day()).getRunId());
        assertEquals("FORBIDDEN", service.backfill("orders", range()).getItems().get(0).getRejectCode());
        assertEquals(1, submissions.get());

        response = R.ok(new ProjectWorkspaceDto(PROJECT, "零售", true, "LEAD", List.of("task:develop", "task:schedule")));
        assertEquals(List.of("run-2", "run-3"), service.backfill("orders", range()).getItems().stream()
                .map(item -> item.getRunId()).toList());
    }

    @Test
    void revokedMembershipDoesNotSubmitAnyRun() {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer sample-token");
        response = R.fail(404, "project unavailable");
        assertEquals("PROJECT_FORBIDDEN", service.runByRequest("orders", day()).getRejectCode());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.triggerManual("orders", Map.of())).getCode());
        assertEquals(0, submissions.get());
    }

    private RunRequest day() {
        var value = new RunRequest();
        value.setRequestId("day-1");
        value.setBizDate("2026-11-19");
        return value;
    }

    private BackfillRequest range() {
        var value = new BackfillRequest();
        value.setRequestId("range-1");
        value.setFromDate("2026-11-18");
        value.setToDate("2026-11-19");
        return value;
    }
}

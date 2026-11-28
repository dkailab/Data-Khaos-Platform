package com.datakhaos.workflow.service;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.workflow.entity.WorkflowDef;
import com.datakhaos.workflow.entity.WorkflowNode;
import com.datakhaos.workflow.executor.DolphinSchedulerBinding;
import com.datakhaos.workflow.executor.NodeType;
import com.datakhaos.workflow.mapper.WorkflowRevisionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 发布版本映射：已受理运行读取发布快照，草稿编辑不改变历史运行的远端定义；
 * 版本不一致、映射缺失由提交入口给出明确拒绝。
 */
class DolphinSchedulerBindingResolverTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Map<Integer, String> snapshots = new HashMap<>();
    private final WorkflowDef def = new WorkflowDef();
    private final WorkflowNode node = new WorkflowNode();
    private DolphinSchedulerBindingResolver resolver;

    @BeforeEach
    void init() {
        def.setId("wf-1");
        def.setProjectId("p1");
        node.setId("node-1");
        node.setWfId("wf-1");
        node.setNodeCode("ds");
        node.setNodeName("离线 DAG");
        node.setNodeType(NodeType.DOLPHINSCHEDULER);
        WorkflowRevisionMapper revisions = (WorkflowRevisionMapper) Proxy.newProxyInstance(
                WorkflowRevisionMapper.class.getClassLoader(), new Class<?>[] {WorkflowRevisionMapper.class},
                (proxy, method, args) -> {
                    if (!"graphJson".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return snapshots.get(args[1]);
                });
        resolver = new DolphinSchedulerBindingResolver(revisions, json, "", "", 0, 0, 0);
    }

    @Test
    void publishedRunKeepsV1MappingAfterDraftEdit() throws Exception {
        snapshots.put(1, graph("ds", 20, 1));
        def.setPublishedRevision(1);
        node.setConfigJson(config(30, 2));

        DolphinSchedulerBinding binding = onlyBinding(resolver.resolveForRun(def, List.of(node)));

        assertEquals(20L, binding.definitionCode());
        assertEquals(1, binding.definitionVersion());
        assertFalse(binding.legacy());
    }

    @Test
    void newRunUsesV2AfterSecondPublish() {
        snapshots.put(1, graph("ds", 20, 1));
        snapshots.put(2, graph("ds", 30, 2));
        def.setPublishedRevision(2);
        node.setConfigJson(config(30, 2));

        DolphinSchedulerBinding binding = onlyBinding(resolver.resolveForRun(def, List.of(node)));

        assertEquals(30L, binding.definitionCode());
        assertEquals(2, binding.definitionVersion());
    }

    @Test
    void missingMappingIsRejectedBeforeRunningNonDsNodes() {
        node.setConfigJson("{}");
        BusinessException error = assertThrows(BusinessException.class,
                () -> resolver.resolveForRun(def, List.of(node)));
        assertTrue(error.getMessage().contains("未绑定远端项目和工作流定义"));
    }

    @Test
    void snapshotWithoutTheDsNodeRequiresRepublish() {
        snapshots.put(1, graph("other", 20, 1));
        def.setPublishedRevision(1);
        BusinessException error = assertThrows(BusinessException.class,
                () -> resolver.resolveForRun(def, List.of(node)));
        assertTrue(error.getMessage().contains("请重新发布"));
    }

    @Test
    void missingSnapshotRejectsTheRun() {
        def.setPublishedRevision(3);
        BusinessException error = assertThrows(BusinessException.class,
                () -> resolver.resolveForRun(def, List.of(node)));
        assertTrue(error.getMessage().contains("V3"));
    }

    @Test
    void legacyEnvironmentBindingIsMarkedInRunContext() {
        resolver = new DolphinSchedulerBindingResolver(legacyRevisions(), json, "wf-1", "ds", 10, 20, 1);
        snapshots.put(1, graph("ds", 0, 0));
        def.setPublishedRevision(1);
        node.setConfigJson("{}");

        DolphinSchedulerBinding binding = onlyBinding(resolver.resolveForRun(def, List.of(node)));

        assertTrue(binding.legacy());
        assertEquals(20L, binding.definitionCode());
    }

    @Test
    void storedContextIsAuditOnlyAndDoesNotMutateWithConfig() throws Exception {
        snapshots.put(1, graph("ds", 20, 1));
        def.setPublishedRevision(1);
        node.setConfigJson(config(30, 2));
        JsonNode first = json.readTree(onlyBinding(resolver.resolveForRun(def, List.of(node))).toJson(json));
        node.setConfigJson(config(40, 3));
        JsonNode second = json.readTree(onlyBinding(resolver.resolveForRun(def, List.of(node))).toJson(json));
        assertEquals(first.path("definitionCode").asLong(), second.path("definitionCode").asLong());
        assertEquals(first.path("definitionVersion").asInt(), second.path("definitionVersion").asInt());
    }

    private DolphinSchedulerBinding onlyBinding(Map<String, String> contexts) {
        assertEquals(1, contexts.size());
        DolphinSchedulerBinding binding = DolphinSchedulerBinding.fromJson(contexts.get("ds"), json);
        assertNotNull(binding);
        return binding;
    }

    private WorkflowRevisionMapper legacyRevisions() {
        return (WorkflowRevisionMapper) Proxy.newProxyInstance(
                WorkflowRevisionMapper.class.getClassLoader(), new Class<?>[] {WorkflowRevisionMapper.class},
                (proxy, method, args) -> "graphJson".equals(method.getName()) ? snapshots.get(args[1]) : null);
    }

    private String graph(String nodeCode, long definitionCode, int version) {
        return "{\"nodes\":[{\"nodeCode\":\"" + nodeCode + "\",\"configJson\":"
                + jsonQuote(config(definitionCode, version)) + "}]}";
    }

    private String config(long definitionCode, int version) {
        return "{\"remoteProjectCode\":10,\"remoteDefinitionCode\":" + definitionCode
                + ",\"remoteDefinitionVersion\":" + version + "}";
    }

    private String jsonQuote(String value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

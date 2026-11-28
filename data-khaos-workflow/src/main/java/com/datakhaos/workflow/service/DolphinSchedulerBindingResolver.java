package com.datakhaos.workflow.service;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.workflow.entity.WorkflowDef;
import com.datakhaos.workflow.entity.WorkflowNode;
import com.datakhaos.workflow.executor.DolphinSchedulerBinding;
import com.datakhaos.workflow.executor.NodeType;
import com.datakhaos.workflow.mapper.WorkflowRevisionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 DolphinScheduler 节点在本次运行中应使用的远端映射。
 *
 * <p>发布后运行优先读取发布版本快照中的节点配置，避免草稿编辑改变历史版本映射；
 * 未发布工作流读取当前节点配置。旧版单节点试点可按环境变量回退，但回退映射会
 * 显式写入节点运行记录，便于审计。</p>
 */
@Slf4j
@Component
public class DolphinSchedulerBindingResolver {

    private final WorkflowRevisionMapper revisions;
    private final ObjectMapper json;
    private final String legacyWorkflowId;
    private final String legacyNodeCode;
    private final long legacyProjectCode;
    private final long legacyDefinitionCode;
    private final Integer legacyDefinitionVersion;

    public DolphinSchedulerBindingResolver(
            WorkflowRevisionMapper revisions,
            ObjectMapper json,
            @Value("${workflow.dolphinscheduler.workflow-id:${WORKFLOW_DOLPHINSCHEDULER_WORKFLOW_ID:}}") String legacyWorkflowId,
            @Value("${workflow.dolphinscheduler.node-code:${WORKFLOW_DOLPHINSCHEDULER_NODE_CODE:}}") String legacyNodeCode,
            @Value("${workflow.dolphinscheduler.project-code:${WORKFLOW_DOLPHINSCHEDULER_PROJECT_CODE:0}}") long legacyProjectCode,
            @Value("${workflow.dolphinscheduler.definition-code:${WORKFLOW_DOLPHINSCHEDULER_DEFINITION_CODE:0}}") long legacyDefinitionCode,
            @Value("${workflow.dolphinscheduler.definition-version:${WORKFLOW_DOLPHINSCHEDULER_DEFINITION_VERSION:0}}") int legacyDefinitionVersion) {
        this.revisions = revisions;
        this.json = json;
        this.legacyWorkflowId = StrUtil.trimToEmpty(legacyWorkflowId);
        this.legacyNodeCode = StrUtil.trimToEmpty(legacyNodeCode);
        this.legacyProjectCode = legacyProjectCode;
        this.legacyDefinitionCode = legacyDefinitionCode;
        this.legacyDefinitionVersion = legacyDefinitionVersion > 0 ? legacyDefinitionVersion : null;
    }

    /**
     * 为一次运行解析全部 DolphinScheduler 节点映射。
     *
     * @return nodeCode -> refContext JSON；无 DolphinScheduler 节点时为空
     */
    public Map<String, String> resolveForRun(WorkflowDef def, List<WorkflowNode> nodes) {
        Map<String, String> contexts = new HashMap<>();
        List<WorkflowNode> dsNodes = nodes.stream()
                .filter(node -> NodeType.DOLPHINSCHEDULER.equals(node.getNodeType()))
                .toList();
        if (dsNodes.isEmpty()) {
            return contexts;
        }
        JsonNode snapshot = snapshot(def);
        for (WorkflowNode node : dsNodes) {
            DolphinSchedulerBinding binding = resolve(node, snapshot);
            if (binding == null || !binding.valid()) {
                throw new BusinessException(ResultCode.VALIDATE_FAILED,
                        "DolphinScheduler 节点「" + node.getNodeName() + "」未绑定远端项目和工作流定义，请先配置并发布");
            }
            contexts.put(node.getNodeCode(), binding.toJson(json));
        }
        return contexts;
    }

    /** 供恢复等场景按当前定义解析单个节点映射。 */
    public DolphinSchedulerBinding resolveForNode(WorkflowDef def, WorkflowNode node) {
        if (node == null || !NodeType.DOLPHINSCHEDULER.equals(node.getNodeType())) {
            return null;
        }
        return resolve(node, snapshot(def));
    }

    /** 旧版环境变量绑定；仅当工作流与节点编码都匹配时生效。 */
    public DolphinSchedulerBinding legacyBinding(String wfId, String nodeCode) {
        if (StrUtil.isBlank(legacyWorkflowId) || StrUtil.isBlank(legacyNodeCode)
                || !legacyWorkflowId.equals(wfId) || !legacyNodeCode.equals(nodeCode)
                || legacyProjectCode <= 0 || legacyDefinitionCode <= 0) {
            return null;
        }
        return new DolphinSchedulerBinding(legacyProjectCode, legacyDefinitionCode,
                legacyDefinitionVersion, wfId, nodeCode, true);
    }

    private DolphinSchedulerBinding resolve(WorkflowNode node, JsonNode snapshot) {
        Map<String, Object> config = parseConfig(node.getConfigJson());
        if (snapshot == null) {
            DolphinSchedulerBinding configured = DolphinSchedulerBinding.fromConfig(
                    config, node.getWfId(), node.getNodeCode());
            return configured != null ? configured : legacyBinding(node.getWfId(), node.getNodeCode());
        }
        JsonNode snapshotNode = findSnapshotNode(snapshot, node.getNodeCode());
        if (snapshotNode == null) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED,
                    "发布版本不包含 DolphinScheduler 节点「" + node.getNodeName() + "」，请重新发布");
        }
        Map<String, Object> snapshotConfig = parseConfig(snapshotNode.path("configJson").asText(""));
        DolphinSchedulerBinding configured = DolphinSchedulerBinding.fromConfig(
                snapshotConfig, node.getWfId(), node.getNodeCode());
        return configured != null ? configured : legacyBinding(node.getWfId(), node.getNodeCode());
    }

    private JsonNode snapshot(WorkflowDef def) {
        Integer revision = def.getPublishedRevision();
        if (revision == null || revision <= 0) {
            return null;
        }
        String graph = revisions.graphJson(def.getId(), revision);
        if (StrUtil.isBlank(graph)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED,
                    "工作流发布版本 V" + revision + " 不存在，请重新发布后再运行");
        }
        try {
            JsonNode root = json.readTree(graph);
            return root == null ? null : root.path("nodes");
        } catch (Exception e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "无法读取工作流发布版本，请重新发布");
        }
    }

    private JsonNode findSnapshotNode(JsonNode nodes, String nodeCode) {
        if (nodes == null || !nodes.isArray()) {
            return null;
        }
        for (JsonNode node : nodes) {
            if (nodeCode.equals(node.path("nodeCode").asText())) {
                return node;
            }
        }
        return null;
    }

    private Map<String, Object> parseConfig(String configJson) {
        if (StrUtil.isBlank(configJson)) {
            return Map.of();
        }
        try {
            return json.readValue(configJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "DolphinScheduler 节点配置不是合法 JSON");
        }
    }
}

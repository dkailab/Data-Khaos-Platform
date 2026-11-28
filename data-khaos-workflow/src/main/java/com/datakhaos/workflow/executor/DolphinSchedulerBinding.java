package com.datakhaos.workflow.executor;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * 一次运行实际使用的 DolphinScheduler 映射。
 *
 * <p>配置来自工作流节点 configJson（发布后固化到版本快照）：
 * {@code remoteProjectCode / remoteDefinitionCode / remoteDefinitionVersion}。
 * 旧版单节点试点可用环境变量回退，回退绑定会标记 legacy 并写入运行记录。</p>
 */
public final class DolphinSchedulerBinding {

    public static final String RUNTIME = "DOLPHINSCHEDULER";
    public static final String CONFIG_PROJECT_CODE = "remoteProjectCode";
    public static final String CONFIG_DEFINITION_CODE = "remoteDefinitionCode";
    public static final String CONFIG_DEFINITION_VERSION = "remoteDefinitionVersion";

    private final long projectCode;
    private final long definitionCode;
    private final Integer definitionVersion;
    private final String workflowId;
    private final String nodeCode;
    private final boolean legacy;

    public DolphinSchedulerBinding(long projectCode, long definitionCode, Integer definitionVersion,
                                   String workflowId, String nodeCode, boolean legacy) {
        this.projectCode = projectCode;
        this.definitionCode = definitionCode;
        this.definitionVersion = definitionVersion;
        this.workflowId = workflowId;
        this.nodeCode = nodeCode;
        this.legacy = legacy;
    }

    public long projectCode() {
        return projectCode;
    }

    public long definitionCode() {
        return definitionCode;
    }

    public Integer definitionVersion() {
        return definitionVersion;
    }

    public String workflowId() {
        return workflowId;
    }

    public String nodeCode() {
        return nodeCode;
    }

    public boolean legacy() {
        return legacy;
    }

    public boolean valid() {
        return projectCode > 0 && definitionCode > 0
                && StrUtil.isNotBlank(workflowId) && StrUtil.isNotBlank(nodeCode);
    }

    public DolphinSchedulerBinding withDefinitionVersion(Integer version) {
        return new DolphinSchedulerBinding(projectCode, definitionCode, version, workflowId, nodeCode, legacy);
    }

    /** 从节点配置解析；缺少项目/定义编码时返回 null，交由调用方决定回退或拒绝。 */
    public static DolphinSchedulerBinding fromConfig(Map<String, Object> config, String workflowId, String nodeCode) {
        if (config == null) {
            return null;
        }
        long projectCode = longValue(config.get(CONFIG_PROJECT_CODE));
        long definitionCode = longValue(config.get(CONFIG_DEFINITION_CODE));
        if (projectCode <= 0 || definitionCode <= 0) {
            return null;
        }
        Integer version = intValue(config.get(CONFIG_DEFINITION_VERSION));
        return new DolphinSchedulerBinding(projectCode, definitionCode, version, workflowId, nodeCode, false);
    }

    public String toJson(ObjectMapper json) {
        ObjectNode node = json.createObjectNode();
        node.put("runtime", RUNTIME);
        node.put("projectCode", projectCode);
        node.put("definitionCode", definitionCode);
        if (definitionVersion != null) {
            node.put("definitionVersion", definitionVersion);
        }
        node.put("workflowId", workflowId);
        node.put("nodeCode", nodeCode);
        node.put("legacy", legacy);
        try {
            return json.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化 DolphinScheduler 映射", e);
        }
    }

    public static DolphinSchedulerBinding fromJson(String text, ObjectMapper json) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            JsonNode node = json.readTree(text);
            if (node == null || !node.isObject()) {
                return null;
            }
            if (!RUNTIME.equals(node.path("runtime").asText(RUNTIME))) {
                return null;
            }
            long projectCode = node.path("projectCode").asLong(0);
            long definitionCode = node.path("definitionCode").asLong(0);
            if (projectCode <= 0 || definitionCode <= 0) {
                return null;
            }
            Integer version = node.hasNonNull("definitionVersion") ? node.path("definitionVersion").asInt() : null;
            return new DolphinSchedulerBinding(projectCode, definitionCode, version,
                    node.path("workflowId").asText(null), node.path("nodeCode").asText(null),
                    node.path("legacy").asBoolean(false));
        } catch (Exception e) {
            return null;
        }
    }

    private static long longValue(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Integer intValue(Object value) {
        if (value == null || StrUtil.isBlank(String.valueOf(value))) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(String.valueOf(value).trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

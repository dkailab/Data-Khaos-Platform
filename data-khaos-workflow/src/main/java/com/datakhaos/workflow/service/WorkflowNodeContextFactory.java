package com.datakhaos.workflow.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.datakhaos.workflow.entity.WorkflowNode;
import com.datakhaos.workflow.executor.NodeContext;
import com.datakhaos.workflow.executor.NodeType;
import com.datakhaos.workflow.executor.WorkflowParamResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 构建节点执行上下文：合并工作流参数与节点级参数、解析外部 SQL、做 {@code ${param}} 替换。
 *
 * <p>规则：
 * <ul>
 *   <li>节点参数覆盖工作流参数，但业务日期 {@code bizDate} 始终由运行上下文决定；</li>
 *   <li>SQL / DATA_OP 支持「外部任务」引用 SQL 文件，文件内容同样参与参数替换；</li>
 *   <li>执行器只拿到最终内容与配置，不感知参数来源。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class WorkflowNodeContextFactory {

    private final SqlTaskContentResolver sqlTaskContentResolver;

    public NodeContext build(WorkflowNode node, Map<String, Object> runtimeParams,
                             String runId, String nodeRunId, String bizDate) {
        Map<String, Object> params = mergeNodeParams(node.getNodeParams(), runtimeParams);
        if (StrUtil.isNotBlank(bizDate)) {
            // PR2A 约束：静态参数不能改写运行业务日期
            params.put("bizDate", bizDate);
        }
        Map<String, Object> config = parseConfig(node.getConfigJson());
        String datasourceId = WorkflowParamResolver.resolve(text(config.get("datasourceId")), params);
        String content = null;

        if (NodeType.SQL.equals(node.getNodeType()) || NodeType.DATA_OP.equals(node.getNodeType())) {
            SqlTaskContentResolver.ResolvedSql resolved = sqlTaskContentResolver.resolve(config, datasourceId);
            content = WorkflowParamResolver.resolve(resolved.content(), params);
            datasourceId = resolved.datasourceId();
            if (StrUtil.isNotBlank(resolved.sourceNote())) {
                config.put("__sqlSourceNote", resolved.sourceNote());
            }
        } else if (NodeType.SHELL.equals(node.getNodeType()) || NodeType.PYTHON.equals(node.getNodeType())) {
            content = WorkflowParamResolver.resolve(text(config.get("script")), params);
        } else if (NodeType.NESTED.equals(node.getNodeType())) {
            config.put("workflowId", WorkflowParamResolver.resolve(text(config.get("workflowId")), params));
        }

        return NodeContext.builder()
                .node(node)
                .content(content)
                .datasourceId(StrUtil.isBlank(datasourceId) ? null : datasourceId)
                .config(config)
                .params(params)
                .timeoutSeconds(node.getTimeout() == null || node.getTimeout() <= 0 ? null : node.getTimeout().longValue())
                .runId(runId)
                .nodeRunId(nodeRunId)
                .bizDate(bizDate)
                .build();
    }

    /** 供前置条件等场景使用：只合并参数，不解析执行内容。 */
    public Map<String, Object> paramsFor(WorkflowNode node, Map<String, Object> runtimeParams) {
        return mergeNodeParams(node.getNodeParams(), runtimeParams);
    }

    /** 节点参数 JSON 覆盖运行参数；解析失败直接报错，避免参数静默丢失。 */
    static Map<String, Object> mergeNodeParams(String nodeParams, Map<String, Object> runtimeParams) {
        Map<String, Object> merged = new HashMap<>();
        if (runtimeParams != null) {
            merged.putAll(runtimeParams);
        }
        if (StrUtil.isNotBlank(nodeParams)) {
            try {
                JSONObject object = JSONUtil.parseObj(nodeParams);
                object.forEach(merged::put);
            } catch (Exception e) {
                throw new IllegalArgumentException("节点参数不是合法 JSON: " + e.getMessage());
            }
        }
        return merged;
    }

    static Map<String, Object> parseConfig(String configJson) {
        Map<String, Object> config = new HashMap<>();
        if (StrUtil.isNotBlank(configJson)) {
            try {
                config.putAll(JSONUtil.parseObj(configJson));
            } catch (Exception e) {
                throw new IllegalArgumentException("节点配置不是合法 JSON: " + e.getMessage());
            }
        }
        return config;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

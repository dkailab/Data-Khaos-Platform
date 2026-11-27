package com.datakhaos.workflow.dto;

/**
 * 上游任务流依赖配置项。
 *
 * @param upstreamWfId   上游任务流ID
 * @param dependStrategy STRICT / LOOSE / OFF
 * @param paramMapping   参数映射 JSON，如 {"bizDate":"bizDate"}
 * @param enabled        是否启用，null 视为启用
 */
public record WorkflowUpstreamRequest(String upstreamWfId, String dependStrategy,
                                      String paramMapping, Boolean enabled) {
}

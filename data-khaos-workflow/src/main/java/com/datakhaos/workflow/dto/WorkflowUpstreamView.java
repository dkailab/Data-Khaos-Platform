package com.datakhaos.workflow.dto;

import com.datakhaos.workflow.entity.WorkflowDef;
import com.datakhaos.workflow.entity.WorkflowUpstream;

/**
 * 上游依赖展示视图：带上游任务流名称与状态，避免前端二次查询。
 */
public record WorkflowUpstreamView(String id, String upstreamWfId, String upstreamName, String upstreamCode,
                                   Integer upstreamStatus, String dependStrategy, String paramMapping,
                                   boolean enabled) {

    public static WorkflowUpstreamView of(WorkflowUpstream upstream, WorkflowDef definition) {
        return new WorkflowUpstreamView(upstream.getId(), upstream.getUpstreamWfId(),
                definition == null ? "(已删除)" : definition.getName(),
                definition == null ? null : definition.getCode(),
                definition == null ? null : definition.getStatus(),
                upstream.getDependStrategy(), upstream.getParamMapping(),
                upstream.getEnabled() != null && upstream.getEnabled() == 1);
    }
}

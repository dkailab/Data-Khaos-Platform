package com.datakhaos.workflow.dto;

import com.datakhaos.workflow.entity.WorkflowNode;
import com.datakhaos.workflow.entity.WorkflowNodeRun;

import java.time.LocalDateTime;

/**
 * 任务列表项：节点定义 + 最近一次运行的节点状态，用于工作台「任务列表」。
 */
public record WorkflowTaskView(String nodeCode, String nodeName, String nodeType, String description,
                               int timeoutSeconds, int retryCount, boolean skipped, boolean independent,
                               String executorGroup, String lastStatus, LocalDateTime lastStartTime,
                               Long lastDurationMs, String lastErrorMessage) {

    public static WorkflowTaskView of(WorkflowNode node, WorkflowNodeRun last) {
        return new WorkflowTaskView(node.getNodeCode(), node.getNodeName(), node.getNodeType(),
                node.getDescription(),
                node.getTimeout() == null ? 0 : node.getTimeout(),
                node.getRetryCount() == null ? 0 : node.getRetryCount(),
                node.getSkipFlag() != null && node.getSkipFlag() == 1,
                node.getIndependentSchedule() != null && node.getIndependentSchedule() == 1,
                node.getExecutorGroup(),
                last == null ? null : last.getStatus(),
                last == null ? null : last.getStartTime(),
                last == null ? null : last.getDurationMs(),
                last == null ? null : last.getErrorMessage());
    }
}

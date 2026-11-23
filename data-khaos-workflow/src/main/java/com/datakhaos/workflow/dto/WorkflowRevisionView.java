package com.datakhaos.workflow.dto;

import com.datakhaos.workflow.entity.WorkflowRevision;

import java.time.LocalDateTime;

public record WorkflowRevisionView(String id, int revisionNumber, String note, String createdBy,
                                   LocalDateTime createTime, int nodeCount, int edgeCount) {
    public static WorkflowRevisionView of(WorkflowRevision revision) {
        return new WorkflowRevisionView(revision.getId(), revision.getRevisionNumber(), revision.getNote(),
                revision.getCreatedBy(), revision.getCreateTime(), revision.getNodeCount(), revision.getEdgeCount());
    }
}

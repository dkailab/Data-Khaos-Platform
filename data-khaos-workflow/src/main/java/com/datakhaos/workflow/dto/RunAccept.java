package com.datakhaos.workflow.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 工作流运行受理结果：区分「已受理」「幂等重复」「被拒绝」。
 *
 * <p>字段语义与 workflow-api 的 RunAcceptDto 对齐，便于跨服务透传。</p>
 */
@Data
public class RunAccept implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String STATE_ACCEPTED = "ACCEPTED";
    public static final String STATE_DUPLICATE = "DUPLICATE";
    public static final String STATE_REJECTED = "REJECTED";

    private String runId;
    private String bizDate;
    private String state;
    private String rejectCode;
    private String rejectMessage;
    /** 冲突时的活跃运行ID（RUN_ALREADY_ACTIVE） */
    private String activeRunId;

    public static RunAccept accepted(String runId, String bizDate) {
        RunAccept a = new RunAccept();
        a.setRunId(runId);
        a.setBizDate(bizDate);
        a.setState(STATE_ACCEPTED);
        return a;
    }

    public static RunAccept duplicate(String runId, String bizDate) {
        RunAccept a = new RunAccept();
        a.setRunId(runId);
        a.setBizDate(bizDate);
        a.setState(STATE_DUPLICATE);
        return a;
    }

    public static RunAccept rejected(String rejectCode, String rejectMessage, String bizDate) {
        RunAccept a = new RunAccept();
        a.setState(STATE_REJECTED);
        a.setRejectCode(rejectCode);
        a.setRejectMessage(rejectMessage);
        a.setBizDate(bizDate);
        return a;
    }
}

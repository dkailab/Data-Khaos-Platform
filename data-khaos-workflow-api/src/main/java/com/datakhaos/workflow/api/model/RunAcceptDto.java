package com.datakhaos.workflow.api.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 工作流运行受理结果：区分「已受理」「幂等重复」「被拒绝」三种语义。
 *
 * <p>调用方（调度/补数）据 {@code state} 决定是否继续等待运行终态：</p>
 * <ul>
 *   <li>{@code ACCEPTED} — 本次新建运行，{@code runId} 可用</li>
 *   <li>{@code DUPLICATE} — 同 {@code requestId} 且参数一致，返回已受理的 {@code runId}（幂等重放）</li>
 *   <li>{@code REJECTED} — 未创建运行，{@code rejectCode} 说明原因</li>
 * </ul>
 */
@Data
public class RunAcceptDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /** ACCEPTED / DUPLICATE / REJECTED */
    public static final String STATE_ACCEPTED = "ACCEPTED";
    public static final String STATE_DUPLICATE = "DUPLICATE";
    public static final String STATE_REJECTED = "REJECTED";

    private String runId;

    private String bizDate;

    private String state;

    /** RUN_ALREADY_ACTIVE / REQUEST_CONFLICT / PROJECT_FORBIDDEN / INVALID_DATE / RANGE_EXCEEDED / FORBIDDEN */
    private String rejectCode;

    private String rejectMessage;

    /** 冲突时的活跃运行ID（RUN_ALREADY_ACTIVE） */
    private String activeRunId;

    public static RunAcceptDto accepted(String runId, String bizDate) {
        RunAcceptDto dto = new RunAcceptDto();
        dto.setRunId(runId);
        dto.setBizDate(bizDate);
        dto.setState(STATE_ACCEPTED);
        return dto;
    }

    public static RunAcceptDto duplicate(String runId, String bizDate) {
        RunAcceptDto dto = new RunAcceptDto();
        dto.setRunId(runId);
        dto.setBizDate(bizDate);
        dto.setState(STATE_DUPLICATE);
        return dto;
    }

    public static RunAcceptDto rejected(String rejectCode, String rejectMessage, String bizDate) {
        RunAcceptDto dto = new RunAcceptDto();
        dto.setState(STATE_REJECTED);
        dto.setRejectCode(rejectCode);
        dto.setRejectMessage(rejectMessage);
        dto.setBizDate(bizDate);
        return dto;
    }
}

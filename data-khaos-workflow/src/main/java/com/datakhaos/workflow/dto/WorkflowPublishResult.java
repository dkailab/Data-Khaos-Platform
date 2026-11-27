package com.datakhaos.workflow.dto;

import java.time.LocalDateTime;

/**
 * 发布结果：发布动作自动生成版本快照并绑定到版本号。
 */
public record WorkflowPublishResult(String wfId, int status, int revisionNumber, LocalDateTime publishedAt) {
}

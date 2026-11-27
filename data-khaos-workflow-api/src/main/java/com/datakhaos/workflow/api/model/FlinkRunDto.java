package com.datakhaos.workflow.api.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实时作业运行视图（跨服务契约）。
 *
 * <p>只暴露调度方需要判断的状态：是否已提交、引擎作业 ID、失败原因。
 * 调度触发以 {@code SUBMITTED} 视为提交成功，不等待流作业结束。</p>
 */
public record FlinkRunDto(String id, int version, String state, String engineJobId, String message,
                          LocalDateTime createTime, LocalDateTime updateTime) implements Serializable {
}

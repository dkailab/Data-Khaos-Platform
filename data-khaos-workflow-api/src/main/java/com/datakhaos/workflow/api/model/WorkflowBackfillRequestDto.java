package com.datakhaos.workflow.api.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 区间补数请求（跨服务传输用）。
 */
@Data
public class WorkflowBackfillRequestDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private String requestId;
    private String fromDate;
    private String toDate;
    private Map<String, Object> params;
}

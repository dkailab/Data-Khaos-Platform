package com.datakhaos.workflow.api.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 带业务日期的运行请求（跨服务传输用）。
 */
@Data
public class WorkflowRunRequestDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private String requestId;
    private String bizDate;
    private Map<String, Object> params;
}

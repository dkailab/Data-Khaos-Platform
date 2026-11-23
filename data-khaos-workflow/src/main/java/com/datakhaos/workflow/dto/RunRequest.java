package com.datakhaos.workflow.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 带业务日期的手动运行请求。
 */
@Data
public class RunRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 请求幂等键（调用方生成，重试沿用原键） */
    private String requestId;

    /** 业务日期 YYYY-MM-DD */
    private String bizDate;

    /** 运行参数（可选，覆盖工作流参数模板） */
    private Map<String, Object> params;
}

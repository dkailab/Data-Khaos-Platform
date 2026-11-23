package com.datakhaos.workflow.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 区间补数请求：含首尾日期，首版最多 31 天。
 */
@Data
public class BackfillRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 补数批次键（调用方生成，重发沿用原键） */
    private String requestId;

    /** 起始业务日期 YYYY-MM-DD（含） */
    private String fromDate;

    /** 结束业务日期 YYYY-MM-DD（含） */
    private String toDate;

    /** 运行参数（可选） */
    private Map<String, Object> params;
}

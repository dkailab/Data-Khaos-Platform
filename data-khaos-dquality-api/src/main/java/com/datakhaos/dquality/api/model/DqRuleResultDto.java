package com.datakhaos.dquality.api.model;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 规则执行结果明细 DTO
 */
@Data
public class DqRuleResultDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String snapshotId;

    private String ruleId;

    /** 规则名称（冗余） */
    private String ruleName;

    /** 规则类型（冗余） */
    private String ruleType;

    /** 0失败 1通过 */
    private Integer passed;

    /** 错误码：NO_PERMISSION / RULE_SQL_FAILED / RULE_ERROR，正常执行为 null */
    private String errorCode;

    /** 实际值（如空值率、丢失率） */
    private BigDecimal actualValue;

    /** 阈值 */
    private BigDecimal threshold;

    /** 违规样本（前 N 行 JSON） */
    private String sampleRows;

    /** 结果说明 */
    private String message;
}
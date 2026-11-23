package com.datakhaos.dquality.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 规则执行结果（明细）
 */
@Data
@TableName("dquality_rule_result")
public class DqRuleResult implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String snapshotId;

    private String ruleId;

    /** 0失败 1通过 */
    private Integer passed;

    /** 规则执行异常码（为空表示规则已正常判定，passed=0 即规则不满足） */
    private String errorCode;

    /** 实际值（如空值率） */
    private BigDecimal actualValue;

    /** 阈值 */
    private BigDecimal threshold;

    /** 违规样本 JSON */
    private String sampleRows;

    /** 结果说明 */
    private String message;
}
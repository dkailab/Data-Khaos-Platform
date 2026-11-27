package com.datakhaos.dquality.api.model;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 质量规则 DTO（契约模块，供其他服务引用）
 */
@Data
public class DqRuleDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    /** 项目组ID（权限隔离）；空=全局模板 */
    private String projectGroupId;

    private String ruleCode;

    private String ruleName;

    /** 规则模板类型 NOT_NULL / UNIQUE / VALUE_RANGE / CUSTOM_SQL / CUSTOM_PROBE / PIPELINE_COUNT */
    private String ruleType;

    private String datasourceId;

    private String databaseName;

    private String tableName;

    private String columnName;

    /** 规则配置 JSON（阈值/表达式/自定义SQL） */
    private String ruleConfig;

    /** 权重（评分用） */
    private Integer weight = 1;

    /** 告警阈值（如空值率 0.05） */
    private java.math.BigDecimal alertThreshold;

    /** 0停用 1启用 */
    private Integer status = 1;
}
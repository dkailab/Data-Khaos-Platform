package com.datakhaos.dquality.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 质量规则
 */
@Data
@TableName("dquality_rule")
public class DqRule implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 项目组ID（权限隔离）；空=全局模板 */
    private String projectGroupId;

    private String ruleCode;

    private String ruleName;

    /** NOT_NULL / UNIQUE / VALUE_RANGE / CUSTOM_SQL / CUSTOM_PROBE */
    private String ruleType;

    private String datasourceId;

    private String databaseName;

    private String tableName;

    private String columnName;

    /** 业务日期列（提供且运行时传入 bizDate 时按该列过滤） */
    private String dateColumn;

    /** 规则配置 JSON */
    private String ruleConfig;

    private Integer weight;

    private BigDecimal alertThreshold;

    /** 0停用 1启用 */
    private Integer status;

    private String createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
package com.datakhaos.dquality.api.model;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 稽核快照 DTO（每次执行结果）
 */
@Data
public class DqSnapshotDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    /** 项目组隔离 */
    private String projectGroupId;

    private String taskId;

    /** 任务名称（冗余展示） */
    private String taskName;

    /** 来源工作流运行ID */
    private String workflowRunId;

    /** 来源工作流节点运行ID */
    private String workflowNodeRunId;

    /** 业务日期 YYYY-MM-DD */
    private String bizDate;

    /** 结论 PASS/FAIL/ERROR */
    private String conclusion;

    /** ERROR 错误码 */
    private String errorCode;

    private String datasourceId;

    private String databaseName;

    private String tableName;

    /** 质量评分 0-100 */
    private BigDecimal score;

    /** 规则总数 */
    private Integer ruleTotal;

    /** 通过数 */
    private Integer rulePass;

    /** 失败数 */
    private Integer ruleFail;

    /** 明细 JSON（各规则结果） */
    private String detail;

    /** 耗时(ms) */
    private Long costMs;

    /** MANUAL / SCHEDULE */
    private String triggerType;

    private LocalDateTime createTime;
}
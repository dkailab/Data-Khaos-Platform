package com.datakhaos.approval.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.datakhaos.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 权限申请表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("app_apply")
public class AppApply extends BaseEntity {

    /** 申请人ID */
    private String applicantId;

    /** 申请类型：TABLE / REPORT / DATASOURCE / MENU / WAREHOUSE_API / FLINK_JOB */
    private String applyType;

    /** 申请目标ID（TABLE 时为数据源ID；WAREHOUSE_API 时为版本ID；FLINK_JOB 时为作业资产ID） */
    private String targetId;

    /** 目标修订号：审批只对当时那一版有效，改稿后需重新审批 */
    private Integer targetRevision;

    /** 申请目标名称（TABLE 时为 database.table） */
    private String targetName;

    /** 申请理由 */
    private String reason;

    /** 0:待审批 1:通过 2:驳回 3:已撤销 */
    private Integer status;

    /** 当前审批人ID */
    private String currentApprover;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

package com.datakhaos.approval.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 权限申请请求
 */
@Data
public class ApplyRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 申请类型：TABLE / REPORT / DATASOURCE / MENU / MART / WAREHOUSE_API / FLINK_JOB */
    private String applyType;

    /** 申请目标ID（TABLE 时为数据源ID） */
    private String targetId;

    /** 目标修订号：审批绑定到具体版本，改稿后自动失效 */
    private Integer targetRevision;

    /** 申请目标名称（TABLE 时为 database.table） */
    private String targetName;

    /** 申请理由 */
    private String reason;
}

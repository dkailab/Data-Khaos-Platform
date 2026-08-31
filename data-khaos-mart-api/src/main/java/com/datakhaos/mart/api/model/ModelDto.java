package com.datakhaos.mart.api.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 模型定义
 */
@Data
public class ModelDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String modelName;

    /** 项目组ID（权限隔离） */
    private String projectGroupId;

    /** 数仓分层ID */
    private String layerId;

    private String modelCode;

    /** STAR / SNOWFLAKE */
    private String modelType = "STAR";

    private String datasourceId;

    /** 主事实表 */
    private String factTable;

    private String description;

    /** 0:草稿 1:已发布 2:下线 */
    private Integer status = 0;

    private Integer version = 1;
}

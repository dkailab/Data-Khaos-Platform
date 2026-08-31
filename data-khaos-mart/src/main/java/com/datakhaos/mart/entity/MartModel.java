package com.datakhaos.mart.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.datakhaos.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 模型定义表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mart_model")
public class MartModel extends BaseEntity {

    /** 模型名称 */
    private String modelName;

    /** 项目组ID（权限隔离） */
    private String projectGroupId;

    /** 数仓分层ID（ODS/DWD/DWS/ADS） */
    private String layerId;

    /** 模型编码 */
    private String modelCode;

    /** STAR / SNOWFLAKE */
    private String modelType;

    /** 数据源ID */
    private String datasourceId;

    /** 主事实表（语义查询的 FROM 表） */
    private String factTable;

    /** 描述 */
    private String description;

    /** 0:草稿 1:已发布 2:下线 */
    private Integer status;

    /** 版本 */
    private Integer version;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

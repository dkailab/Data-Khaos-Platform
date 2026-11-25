package com.datakhaos.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.datakhaos.workflow.flink.FlinkDefinition.FunctionType;
import com.datakhaos.common.entity.BaseEntity;
import com.datakhaos.workflow.flink.FlinkDefinition.Mode;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("flink_asset")
public class FlinkAsset extends BaseEntity {
    private String projectId;
    private Kind kind;
    private String code;
    private String name;
    private Integer currentVersion;
    private Mode currentMode;
    @TableField(exist = false)
    private FunctionType functionType;
    private LocalDateTime updateTime;

    public enum Kind { JOB, FUNCTION, JAR_JOB }
}

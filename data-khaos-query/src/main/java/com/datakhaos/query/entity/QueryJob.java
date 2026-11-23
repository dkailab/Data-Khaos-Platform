package com.datakhaos.query.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.datakhaos.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("query_job")
public class QueryJob extends BaseEntity {
    private String userId;
    private String requestId;
    private String datasourceId;
    private String databaseName;
    private String sqlText;
    private QueryJobStatus status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime resultExpiresAt;
    private Integer rowCount;
    private String errorMessage;

    @TableField(select = false)
    private String resultJson;
}

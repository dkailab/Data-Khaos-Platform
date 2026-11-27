package com.datakhaos.query.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("warehouse_api_version")
public class WarehouseApiVersion {
    public enum Status { DRAFT, PUBLISHED, OFFLINE }

    /** BATCH=离线分区发布（走质量证据）；REALTIME=实时 ADS（走观测时间与时效判定）。 */
    public enum DataMode { BATCH, REALTIME }

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String projectId;
    private String apiCode;
    private int apiVersion;
    private String apiName;
    private int maxRows;
    private DataMode dataMode;
    private String realtimeAssetId;
    private String realtimeBatchAssetId;
    private String observedTimezone;
    private String amountUnit;
    private int freshnessFreshSeconds;
    private int freshnessStaleSeconds;
    /** 分区静默窗口（秒）：作业推进距今不足该值时结果判为 IN_PROGRESS。0 表示关闭，存量默认。 */
    private int quietSeconds;
    private Status status;
    private int revision;
    private String createdBy;
    private LocalDateTime createTime;
    private String updatedBy;
    private LocalDateTime updateTime;
    private String publishedBy;
    private LocalDateTime publishedAt;
    private String offlinedBy;
    private LocalDateTime offlinedAt;
}

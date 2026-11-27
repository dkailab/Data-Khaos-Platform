package com.datakhaos.query.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
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
    /** 批流对账容差（0~1），null/0 表示 STRICT 精确对账。 */
    private BigDecimal reconcileTolerance;
    // ---- 实时契约的列映射：为空时走默认地区成交额口径，存量版本行为不变 ----
    /** 地区/维度列，默认 region。 */
    private String realtimeKeyColumn;
    /** 金额列（默认 gmv_cents），按 valueDivisor 换算为元。 */
    private String realtimeValueColumn;
    /** 订单数列，默认 paid_order_count。 */
    private String realtimeCountColumn;
    /** 观测时间列，默认 observed_at。 */
    private String realtimeObservedColumn;
    /** 业务日期列，默认 biz_date。 */
    private String realtimeDateColumn;
    /** 金额换算除数：100 表示金额存分，1 表示已是元。 */
    private Integer realtimeValueDivisor;
    /** 推进信号表；为空时取结果表名 + _events。 */
    private String realtimeProgressTable;
    /** 推进表业务日期列，默认与结果表一致。 */
    private String realtimeProgressDateColumn;
    /** 推进时间列，默认 advanced_at。 */
    private String realtimeProgressAdvancedColumn;
    /** 推进事件数列，默认 event_count。 */
    private String realtimeProgressCountColumn;
    /** 推进最大事件时间列，默认 max_event_time。 */
    private String realtimeProgressEventTimeColumn;
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

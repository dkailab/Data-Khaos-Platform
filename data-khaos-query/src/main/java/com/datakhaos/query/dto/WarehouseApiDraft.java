package com.datakhaos.query.dto;

import com.datakhaos.query.entity.WarehouseApiVersion;

import java.math.BigDecimal;

/**
 * API 版本草稿的写入模型。
 *
 * <p>创建时省略数据模式使用 BATCH。编辑时省略数据模式只更新名称和行数上限；
 * 显式提供数据模式时替换契约。声明为 REALTIME 时校验实时资产与时效阈值。
 *
 * @param name                  API 名称
 * @param maxRows               最大返回地区数
 * @param dataMode              BATCH（默认）或 REALTIME
 * @param realtimeAssetId       实时 ADS 资产（REALTIME 必填）
 * @param realtimeBatchAssetId  对账基线批 ADS 资产（可选）
 * @param observedTimezone      结果观测时间的解释时区，默认 Asia/Shanghai
 * @param amountUnit            金额单位，默认 CNY（固定两位小数）
 * @param freshnessFreshSeconds FRESH 阈值，默认 60
 * @param freshnessStaleSeconds STALE 阈值，默认 300，须大于 FRESH
 * @param quietSeconds          分区静默窗口，默认 0（关闭）。大于 0 时作业刚推进的结果判为 IN_PROGRESS
 * @param reconcileTolerance    批流对账容差（0~1），省略或 0 表示 STRICT 精确对账，仅对 REALTIME 生效
 * @param realtimeMapping       列映射与推进信号表；省略时使用默认地区成交额口径
 */
public record WarehouseApiDraft(String name, int maxRows, WarehouseApiVersion.DataMode dataMode,
                                String realtimeAssetId, String realtimeBatchAssetId, String observedTimezone,
                                String amountUnit, int freshnessFreshSeconds, int freshnessStaleSeconds,
                                int quietSeconds, BigDecimal reconcileTolerance,
                                RealtimeMapping realtimeMapping) {

    /**
     * 实时契约的列映射。所有字段可空：为空时按默认地区成交额口径解释。
     *
     * @param keyColumn               地区列，默认 region
     * @param valueColumn             金额列，默认 gmv_cents
     * @param countColumn             订单数列，默认 paid_order_count
     * @param observedColumn          观测时间列，默认 observed_at
     * @param dateColumn              业务日期列，默认 biz_date
     * @param valueDivisor            金额换算除数，默认 100（分→元），1 表示列已是元
     * @param progressTable           推进信号表，默认结果表名 + _events
     * @param progressDateColumn      推进表业务日期列，默认与结果表一致
     * @param progressAdvancedColumn  推进时间列，默认 advanced_at
     * @param progressCountColumn     推进事件数列，默认 event_count
     * @param progressEventTimeColumn 最大事件时间列，默认 max_event_time
     */
    public record RealtimeMapping(String keyColumn, String valueColumn, String countColumn,
                                  String observedColumn, String dateColumn, Integer valueDivisor,
                                  String progressTable, String progressDateColumn,
                                  String progressAdvancedColumn, String progressCountColumn,
                                  String progressEventTimeColumn) {}

    /** 存量调用：无容差、无自定义映射。 */
    public WarehouseApiDraft(String name, int maxRows, WarehouseApiVersion.DataMode dataMode,
                             String realtimeAssetId, String realtimeBatchAssetId, String observedTimezone,
                             String amountUnit, int freshnessFreshSeconds, int freshnessStaleSeconds,
                             int quietSeconds) {
        this(name, maxRows, dataMode, realtimeAssetId, realtimeBatchAssetId, observedTimezone,
                amountUnit, freshnessFreshSeconds, freshnessStaleSeconds, quietSeconds, null, null);
    }

    /** 存量调用：无自定义映射。 */
    public WarehouseApiDraft(String name, int maxRows, WarehouseApiVersion.DataMode dataMode,
                             String realtimeAssetId, String realtimeBatchAssetId, String observedTimezone,
                             String amountUnit, int freshnessFreshSeconds, int freshnessStaleSeconds,
                             int quietSeconds, BigDecimal reconcileTolerance) {
        this(name, maxRows, dataMode, realtimeAssetId, realtimeBatchAssetId, observedTimezone,
                amountUnit, freshnessFreshSeconds, freshnessStaleSeconds, quietSeconds, reconcileTolerance, null);
    }
}

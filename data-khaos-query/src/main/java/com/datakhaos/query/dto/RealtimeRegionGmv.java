package com.datakhaos.query.dto;

import java.util.List;

/**
 * 实时 ADS 契约：在离线契约之外，额外固化「结果观测时间」与「时效语义」。
 *
 * <p>两类状态分开表达，避免把「没有数据」和「数据过期」混成一个字段：
 * <ul>
 *   <li>{@code state} 回答「有没有结果」：PRODUCED / EMPTY / NOT_YET_PRODUCED；</li>
 *   <li>{@code freshness} 回答「结果有多新」：FRESH / LAGGING / STALE / UNKNOWN。</li>
 * </ul>
 *
 * @param bizDate    业务日期 YYYY-MM-DD（契约时区下的自然日）
 * @param dataMode   固定为 REALTIME，便于调用方区分两种契约
 * @param timezone   observed_at 的解释时区，原样回显，调用方无需猜测
 * @param amountUnit 金额单位，固定 CNY，金额一律两位小数
 * @param state      结果可用性，见类注释
 * @param observedAt 本业务日结果的最大观测时间（ISO-8601 带偏移），未知时为 null
 * @param lagSeconds 观测时间到本次调用的秒数，未知时为 null
 * @param freshness  时效状态，见类注释
 * @param thresholds 本次判定所用的阈值，便于调用方复算
 * @param progress   作业推进信号，用于解释 state=EMPTY 的依据
 * @param truncated  是否因 maxRows 截断
 * @param rowCount   返回的地区行数
 * @param regions    地区明细
 */
public record RealtimeRegionGmv(String bizDate, String dataMode, String timezone, String amountUnit,
                                String state, String observedAt, Long lagSeconds, String freshness,
                                FreshnessThresholds thresholds, Progress progress,
                                boolean truncated, int rowCount, List<RegionRow> regions) {

    public static final String MODE = "REALTIME";

    /** 时效阈值（秒），与 API 版本上的配置一致。 */
    public record FreshnessThresholds(int freshSeconds, int staleSeconds) {}

    /**
     * 作业推进证据：来自独立于成交结果的事件推进表。
     * advancedAt 为空表示该业务日尚未被作业消费过。
     */
    public record Progress(String advancedAt, Long lagSeconds, long eventCount, String maxEventTime) {}

    /** 地区明细：金额按元返回两位小数，orderCount 为已支付订单数。 */
    public record RegionRow(String region, String gmv, long orderCount, String observedAt) {}
}

package com.datakhaos.query.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 实时与批 ADS 的逐地区对账结果。
 *
 * <p>两种模式各自附带观测时间：实时看结果级 observed_at，批看批 ADS 的 observed_at，
 * 这样「数值一致」与「哪一侧更新」可以同时被判断，而不是只比金额。
 *
 * <p>对账模式：STRICT 要求逐地区精确相等；TOLERANCE 允许金额与订单数的相对偏差不超过
 * {@code tolerance}（如 0.05 = 5%），用于大数据量下「最终一致但存在微小差异」的场景。
 * 任一侧截断或尚未定稿时，两种模式都不判一致。
 *
 * @param bizDate   业务日期
 * @param matched   按本次模式判定是否一致
 * @param realtime  实时侧证据
 * @param batch     批侧证据
 * @param regions   逐地区差异明细，一致的地区也会列出以便核对
 * @param mode      STRICT 或 TOLERANCE，随响应回显便于调用方复算
 * @param tolerance 本次使用的容差，STRICT 时为 null
 * @param matchRate 通过逐地区判定的地区数 / 地区并集数，无地区时为 1
 */
public record RealtimeReconcile(String bizDate, boolean matched, Side realtime, Side batch,
                                List<RegionDiff> regions, String mode, BigDecimal tolerance, BigDecimal matchRate) {

    /** 存量调用：严格模式，无容差配置。 */
    public RealtimeReconcile(String bizDate, boolean matched, Side realtime, Side batch, List<RegionDiff> regions) {
        this(bizDate, matched, realtime, batch, regions, MODE_STRICT, null,
                matched ? BigDecimal.ONE : BigDecimal.ZERO);
    }

    public static final String MODE_STRICT = "STRICT";
    public static final String MODE_TOLERANCE = "TOLERANCE";

    /**
     * 一侧的时效与版本证据。
     *
     * @param truncated            本侧结果是否因 maxRows 截断；任一侧截断时不判 matched
     * @param processingLagSeconds 处理时钟滞后（仅实时侧有值），批侧为 null；与 lagSeconds 含义不同，勿合并
     */
    public record Side(String state, String observedAt, Long lagSeconds, String freshness,
                       int regionCount, String amountUnit, boolean truncated, Long processingLagSeconds) {
        /** 兼容无截断信息、无处理信号的旧调用。 */
        public Side(String state, String observedAt, Long lagSeconds, String freshness,
                    int regionCount, String amountUnit) {
            this(state, observedAt, lagSeconds, freshness, regionCount, amountUnit, false, null);
        }
    }

    /**
     * 逐地区比对结果。
     *
     * @param verdict           MATCHED / WITHIN_TOLERANCE / ONLY_REALTIME / ONLY_BATCH / MISMATCH
     * @param gmvDiffRate       金额相对偏差率，精确一致或只有一侧有数据时为 null
     * @param orderDiffRate     订单数相对偏差率，精确一致或只有一侧有数据时为 null
     */
    public record RegionDiff(String region, String realtimeGmv, String batchGmv,
                             Long realtimeOrderCount, Long batchOrderCount, String verdict,
                             BigDecimal gmvDiffRate, BigDecimal orderDiffRate) {

        /** 存量调用：精确判定，无偏差率。 */
        public RegionDiff(String region, String realtimeGmv, String batchGmv,
                          Long realtimeOrderCount, Long batchOrderCount, String verdict) {
            this(region, realtimeGmv, batchGmv, realtimeOrderCount, batchOrderCount, verdict, null, null);
        }
    }
}

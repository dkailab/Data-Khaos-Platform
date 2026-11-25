package com.datakhaos.query.dto;

import java.util.List;

/**
 * 实时与批 ADS 的逐地区对账结果。
 *
 * <p>两种模式各自附带观测时间：实时看结果级 observed_at，批看批 ADS 的 observed_at，
 * 这样「数值一致」与「哪一侧更新」可以同时被判断，而不是只比金额。
 *
 * @param bizDate   业务日期
 * @param matched   双方已产出、未截断且地区集合、金额、订单数均一致
 * @param realtime  实时侧证据
 * @param batch     批侧证据
 * @param regions   逐地区差异明细，一致的地区也会列出以便核对
 */
public record RealtimeReconcile(String bizDate, boolean matched, Side realtime, Side batch,
                                List<RegionDiff> regions) {

    /** 一侧的时效与版本证据。 */
    public record Side(String state, String observedAt, Long lagSeconds, String freshness,
                       int regionCount, String amountUnit, boolean truncated) {}

    /**
     * 逐地区比对结果。
     *
     * @param verdict 一致 MATCHED / 仅实时 ONLY_REALTIME / 仅批 ONLY_BATCH / 数值不一致 MISMATCH
     */
    public record RegionDiff(String region, String realtimeGmv, String batchGmv,
                             Long realtimeOrderCount, Long batchOrderCount, String verdict) {}
}

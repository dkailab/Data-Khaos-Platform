package com.datakhaos.query.dto;

import com.datakhaos.query.entity.WarehouseApiVersion;

/**
 * API 版本草稿的写入模型。
 *
 * <p>除名称与行数上限外，其余字段均可省略：省略时回落为离线契约（BATCH）的既有行为，
 * 因此老调用方不需要改动。声明为 REALTIME 时才校验实时资产与时效阈值。
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
 */
public record WarehouseApiDraft(String name, int maxRows, WarehouseApiVersion.DataMode dataMode,
                                String realtimeAssetId, String realtimeBatchAssetId, String observedTimezone,
                                String amountUnit, int freshnessFreshSeconds, int freshnessStaleSeconds) {
}

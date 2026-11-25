package com.datakhaos.query.dto;

import com.datakhaos.query.entity.WarehouseApiVersion;

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
 */
public record WarehouseApiDraft(String name, int maxRows, WarehouseApiVersion.DataMode dataMode,
                                String realtimeAssetId, String realtimeBatchAssetId, String observedTimezone,
                                String amountUnit, int freshnessFreshSeconds, int freshnessStaleSeconds) {
}

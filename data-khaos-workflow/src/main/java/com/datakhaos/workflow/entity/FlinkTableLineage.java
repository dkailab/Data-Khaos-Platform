package com.datakhaos.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.datakhaos.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Flink SQL 表级血缘（按作业版本保存一次解析结果）。
 *
 * <p>名称可能无法映射到元数据资产（Kafka topic、未同步的库表），
 * 此时 assetId 为空但名称仍保留，便于在作业侧展示「解析到了但没对上资产」。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("flink_table_lineage")
public class FlinkTableLineage extends BaseEntity {

    private String projectId;

    private String assetId;

    private Integer assetVersion;

    /** 最近一次触发解析的运行 ID；手动刷新时为空 */
    private String runId;

    private String sourceName;

    private String sourceAssetId;

    private String targetName;

    private String targetAssetId;

    /** 固定 FLINK，便于元数据侧区分批 SQL 与实时作业 */
    private String relationType;
}

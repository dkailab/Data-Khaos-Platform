package com.datakhaos.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.datakhaos.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("flink_asset_version")
public class FlinkAssetVersion extends BaseEntity {
    private String assetId;
    private Integer versionNumber;
    private String name;
    private String note;
    private String createdBy;
    private Integer restoredFrom;

    @TableField(select = false)
    private String definitionJson;
}

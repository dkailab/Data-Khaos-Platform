package com.datakhaos.metadata.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.datakhaos.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据字典项表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("meta_dict_item")
public class MetaDictItem extends BaseEntity {

    /** 所属字典类型ID（meta_dict_type.id） */
    private String typeId;

    /** 字典项编码（类型内唯一） */
    private String itemCode;

    /** 字典项名称 */
    private String itemName;

    /** 字典项值 */
    private String itemValue;

    /** 状态 1:启用 0:停用 */
    private Integer status;

    /** 排序 */
    private Integer sortOrder;

    /** 描述 */
    private String description;
}
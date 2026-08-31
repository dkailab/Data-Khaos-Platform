package com.datakhaos.metadata.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.datakhaos.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据字典类型表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("meta_dict_type")
public class MetaDictType extends BaseEntity {

    /** 字典类型编码（唯一） */
    private String typeCode;

    /** 字典类型名称 */
    private String typeName;

    /** 描述 */
    private String description;

    /** 状态 1:启用 0:停用 */
    private Integer status;

    /** 排序 */
    private Integer sortOrder;
}
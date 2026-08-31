package com.datakhaos.metadata.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.datakhaos.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据标准配置表（字段级标准）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("meta_standard")
public class MetaStandard extends BaseEntity {

    /** 标准编码（唯一） */
    private String stdCode;

    /** 标准名称 */
    private String stdName;

    /** 标准分类（如 元数据类/编码类/格式类） */
    private String category;

    /** 数据类型 */
    private String dataType;

    /** 长度 */
    private Integer dataLength;

    /** 精度 */
    private Integer dataPrecision;

    /** 小数位 */
    private Integer dataScale;

    /** 单位 */
    private String unit;

    /** 取值范围/枚举（JSON 数组或逗号分隔） */
    private String enumRange;

    /** 格式/编码规则 */
    private String formatRule;

    /** 描述 */
    private String description;

    /** 状态 1:启用 0:停用 */
    private Integer status;

    /** 排序 */
    private Integer sortOrder;
}
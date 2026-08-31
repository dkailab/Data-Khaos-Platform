package com.datakhaos.mart.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 语义查询请求：基于「模型 + 指标 + 维度 + 筛选 + 排序」生成 SQL 并执行（BI 画布核心）。
 * 指标聚合语义内嵌于指标 expression，无需前端指定聚合函数。
 */
@Data
public class MartQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模型ID */
    private String modelId;

    /** 指标（至少一个） */
    private List<MetricRef> metrics;

    /** 维度 */
    private List<DimRef> dimensions;

    /** 筛选条件 */
    private List<FilterRef> filters;

    /** 排序 */
    private List<SortRef> sorts;

    /** 行数限制（默认 1000，上限 10000） */
    private Integer limit;

    @Data
    public static class MetricRef implements Serializable {
        private static final long serialVersionUID = 1L;
        private String metricCode;
    }

    @Data
    public static class DimRef implements Serializable {
        private static final long serialVersionUID = 1L;
        private String dimCode;
        /** 时间维度粒度：Y / M / D（仅 TIME 维度生效） */
        private String grain;
        /** 层级下钻列（覆盖维度的 sourceColumn） */
        private String levelColumn;
    }

    @Data
    public static class FilterRef implements Serializable {
        private static final long serialVersionUID = 1L;
        private String dimCode;
        /** EQ / NE / GT / GTE / LT / LTE / LIKE / IN / NOT_IN / BETWEEN */
        private String operator;
        /** 条件值（BETWEEN 取前两个，IN 全量） */
        private List<String> values;
    }

    @Data
    public static class SortRef implements Serializable {
        private static final long serialVersionUID = 1L;
        /** 投影别名：维度 dimCode 或指标 metricCode */
        private String code;
        /** ASC / DESC */
        private String direction;
    }
}
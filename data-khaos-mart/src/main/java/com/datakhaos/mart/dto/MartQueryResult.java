package com.datakhaos.mart.dto;

import com.datakhaos.datasource.api.model.QueryResult;
import lombok.Data;

import java.io.Serializable;

/**
 * 语义查询响应：生成的 SQL + 查询结果。
 */
@Data
public class MartQueryResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 生成的查询 SQL */
    private String sql;

    /** 查询结果 */
    private QueryResult result;

    /** 结果是否被行数上限截断 */
    private boolean truncated;

    /** 返回行数 */
    private int originalRowCount;
}